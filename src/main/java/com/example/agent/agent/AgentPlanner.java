package com.example.agent.agent;

import com.example.agent.observability.AgentScope;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Planner（任务分解）：把用户目标拆成「交给哪个子代理 + 子任务指令」的有序步骤。
 *
 * 定位：只做分解，不执行。执行由 {@link PlannerToolProvider} 完成（串行 + 校验回环），
 * 这样 Planner 可以被单测替身直接替换，也便于把「计划」本身作为 trace 展示。
 *
 * 失败策略：任何异常/解析失败都返回 empty，由调用方回退到逐个委派（绝不阻断对话）。
 */
@Component
public class AgentPlanner {

    private static final Logger log = LoggerFactory.getLogger(AgentPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个计划步骤：交给哪个子代理、做什么 */
    public record PlanStep(String agent, String task) {
    }

    /** 一次任务分解结果 */
    public record Plan(String goal, List<PlanStep> steps) {
    }

    /** 候选专家（name 唯一 + 短描述） */
    public record Specialist(String name, String description) {
    }

    /** 分解函数（测试可注入替身） */
    @FunctionalInterface
    public interface PlanDecomposer {
        Optional<Plan> decompose(String goal, List<Specialist> candidates);
    }

    private final ChatModel chatModel;
    private final boolean enabled;
    private final int maxSteps;

    public AgentPlanner(ChatModel chatModel,
                        @Value("${agent.planner.enabled:false}") boolean enabled,
                        @Value("${agent.planner.max-steps:4}") int maxSteps) {
        this.chatModel = chatModel;
        this.enabled = enabled;
        this.maxSteps = Math.max(1, maxSteps);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 规划接口（供 PlannerToolProvider 调用；AgentFactory 以方法引用注入） */
    public Optional<Plan> plan(String goal, List<Specialist> candidates) {
        if (!enabled || goal == null || goal.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            long start = System.nanoTime();
            Result<String> result = decider(candidates).decide(goal);
            recordUsage(result.tokenUsage());
            Optional<Plan> plan = parsePlan(goal, result.content(), candidates, maxSteps);
            log.info("[Planner] 目标「{}」→ {} 步{}", abbreviate(goal),
                    plan.map(p -> p.steps().size()).orElse(0),
                    plan.map(p -> "：" + p.steps().stream()
                            .map(s -> s.agent() + "/" + abbreviate(s.task())).toList()).orElse("（未分解）"));
            log.debug("[Planner] 分解耗时 {}ms", (System.nanoTime() - start) / 1_000_000L);
            return plan;
        } catch (Exception e) {
            log.warn("[Planner] 任务分解失败，回退逐个委派: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 计划编排自身的 LLM 用量也计入整轮成本 */
    private static void recordUsage(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        int input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int output = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        int total = usage.totalTokenCount() == null ? 0 : usage.totalTokenCount();
        TraceCollector collector = TraceContextHolder.current();
        if (collector != null) {
            collector.recordDelegatedUsage(input, output, total);
        }
        AgentScope.addTokens(total > 0 ? total : input + output);
    }

    interface PlanDecider {
        Result<String> decide(@UserMessage String goal);
    }

    private PlanDecider decider(List<Specialist> candidates) {
        String system = systemMessage(candidates);
        return AiServices.builder(PlanDecider.class)
                .chatModel(chatModel)
                .systemMessageProvider(memoryId -> system)
                .build();
    }

    private String systemMessage(List<Specialist> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是多 Agent 系统的任务规划器：把用户目标拆成若干个「可交给子代理独立完成」的步骤。

                输出要求（必须严格遵守）：
                - 只输出一个 JSON 数组，不要解释、不要 Markdown 代码块、不要多余文字。
                - 每个元素形如 {"agent":"子代理名","task":"交给它的完整子任务指令"}。
                - agent 必须是下面候选之一，原样照抄，不得编造。
                - task 必须自包含：写清目标、约束、预期产出（子代理看不到对话历史）。
                - 步骤按执行顺序排列；互不依赖的步骤可以拆成多步，由执行器串行跑。
                - 最多 """).append(maxSteps).append(" 步。\n");
        if (hasVerifier(candidates)) {
            sb.append("- 任务复杂或需要交叉核对时，最后一步交给校验专家，检查前序结果是否自洽、是否回答了目标。\n");
        }
        sb.append("\n候选子代理：\n");
        for (Specialist c : candidates) {
            sb.append("- ").append(c.name()).append("：")
                    .append(c.description() == null ? "" : c.description()).append('\n');
        }
        return sb.toString();
    }

    /** 解析模型输出（容忍代码块/前后废话）；非法步骤丢弃，全部非法则 empty */
    static Optional<Plan> parsePlan(String goal, String raw, List<Specialist> candidates, int maxSteps) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String json = extractJsonArray(raw);
        if (json == null) {
            return Optional.empty();
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (Specialist c : candidates) {
            if (c != null && c.name() != null && !c.name().isBlank()) {
                allowed.add(c.name());
            }
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return Optional.empty();
            }
            List<PlanStep> steps = new ArrayList<>();
            for (JsonNode node : root) {
                if (steps.size() >= maxSteps) {
                    break;
                }
                if (node == null || !node.hasNonNull("agent") || !node.hasNonNull("task")) {
                    continue;
                }
                String agent = node.get("agent").asText().trim();
                String task = node.get("task").asText().trim();
                if (!allowed.contains(agent) || task.isEmpty()) {
                    continue;
                }
                steps.add(new PlanStep(agent, task));
            }
            return steps.isEmpty() ? Optional.empty() : Optional.of(new Plan(goal, List.copyOf(steps)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 从回复中截取第一个 '[' 到最后一个 ']'（模型常包一层代码块或解释） */
    private static String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    static boolean hasVerifier(List<Specialist> candidates) {
        return candidates.stream().anyMatch(c -> c != null && c.name() != null
                && c.name().toLowerCase(java.util.Locale.ROOT).contains("verif"));
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }
}
