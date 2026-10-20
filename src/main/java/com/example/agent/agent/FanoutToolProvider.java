package com.example.agent.agent;

import com.example.agent.observability.AgentScope;
import com.example.agent.observability.DelegationIds;
import com.example.agent.observability.RequestContext;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.observability.model.DelegationTraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fan-out（并行扇出）工具：{@code delegate_parallel}。
 *
 * 主 Agent 一次调用即可把多个<b>互不依赖</b>的子任务并行派发给多个子代理，各自独立完成后
 * 合并结果（{@code 【子代理·名称（描述）】...}）交回主 Agent 汇总。
 *
 * 与 {@link SubAgentToolProvider}（单个委派）的区别：一次调用触发 N 个子代理并发，
 * 适合"多视角/多独立子任务"；有依赖的任务应串行处理。
 *
 * 并发安全依赖：{@link FanoutExecutor} 在每个工作线程绑定 {@link RequestContext}
 * （采集器 + 审批闸门），使并发子代理的 trace 不丢、高危工具审批可用。
 */
public class FanoutToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(FanoutToolProvider.class);

    public static final String TOOL_NAME = "delegate_parallel";
    private static final String PARAM_TASKS = "tasks";
    private static final String FIELD_AGENT = "agent";
    private static final String FIELD_TASK = "task";
    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

    private final FanoutExecutor executor;
    /** agent 名 -> 子代理执行体 */
    private final Map<String, SubAgentToolProvider.SubAgentRunner> runners;
    /** agent 名 -> 短描述（合并结果展示用） */
    private final Map<String, String> labels;
    private final int maxDelegationsPerTurn;

    public FanoutToolProvider(FanoutExecutor executor,
                              Map<String, SubAgentToolProvider.SubAgentRunner> runners,
                              Map<String, String> labels,
                              int maxDelegationsPerTurn) {
        this.executor = executor;
        this.runners = new LinkedHashMap<>(runners);
        this.labels = new LinkedHashMap<>(labels);
        this.maxDelegationsPerTurn = Math.max(1, maxDelegationsPerTurn);
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        String names = candidateNames();
        ToolSpecification spec = ToolSpecification.builder()
                .name(TOOL_NAME)
                .description("并行派发多个子代理，各自独立完成一个子任务，最后合并结果。"
                        + "仅用于互不依赖的子任务（需要等待前一步结果时应改用串行委派）。"
                        + "候选子代理：" + names)
                .parameters(JsonObjectSchema.builder()
                        .addProperty(PARAM_TASKS, JsonArraySchema.builder()
                                .description("要并行派发的子任务列表（互不依赖）")
                                .items(JsonObjectSchema.builder()
                                        .addStringProperty(FIELD_AGENT, "子代理名称，必须是候选之一：" + names)
                                        .addStringProperty(FIELD_TASK, "交给该子代理的完整子任务指令")
                                        .required(FIELD_AGENT, FIELD_TASK)
                                        .build())
                                .build())
                        .required(PARAM_TASKS)
                        .build())
                .build();
        ToolExecutor executorFn = (executionRequest, memoryId) -> delegate(executionRequest, memoryId);
        return ToolProviderResult.builder().add(spec, executorFn).build();
    }

    /** 执行扇出：解析任务 → 校验 → 预算 → 并行执行 → 合并（任何异常都转为可读文本，不炸主循环） */
    private String delegate(ToolExecutionRequest request, Object memoryId) {
        List<FanoutExecutor.FanoutTask> tasks = parseTasks(request.arguments());
        if (tasks.isEmpty()) {
            return "扇出失败：缺少 " + PARAM_TASKS + " 参数或格式不正确"
                    + "（应为 [{\"agent\":\"名称\",\"task\":\"指令\"}]）。候选子代理：" + candidateNames();
        }
        List<String> unknown = tasks.stream()
                .map(FanoutExecutor.FanoutTask::agent)
                .filter(a -> !runners.containsKey(a))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            return "扇出失败：未知子代理 " + unknown + "。候选子代理：" + candidateNames();
        }

        // 回合级全局预算：一次扇出按任务数计入委派次数
        TraceCollector collector = TraceContextHolder.current();
        if (collector != null && collector.delegations() + tasks.size() > maxDelegationsPerTurn) {
            log.warn("[Fanout] 委派预算不足：已用 {}，本次需 {}，上限 {}",
                    collector.delegations(), tasks.size(), maxDelegationsPerTurn);
            return "本回合委派预算不足（上限 " + maxDelegationsPerTurn + "，已用 " + collector.delegations()
                    + "，本次需 " + tasks.size() + " 次），未执行并行扇出。"
                    + "请减少子任务数量，或直接作答。";
        }
        if (collector != null) {
            for (int i = 0; i < tasks.size(); i++) {
                collector.recordDelegation();
            }
        }

        RequestContext ctx = RequestContext.capture();
        String parentId = AgentScope.currentDelegationId();
        List<FanoutExecutor.FanoutOutcome> outcomes = executor.run(ctx, tasks, task -> {
            // 每个扇出任务都是调用树上的一个委派节点（同层兄弟节点）
            SubAgentToolProvider.SubAgentRunner runner = runners.get(task.agent());
            String delegationId = DelegationIds.next();
            long start = System.nanoTime();
            try {
                AgentScope.Scoped<String> scoped = AgentScope.runScopedDetailed(
                        task.agent(), delegationId, () -> runner.run(task.task(), memoryId));
                record(collector, delegationId, parentId, task, true, null,
                        scoped.durationMs(), scoped.tokens());
                return scoped.value();
            } catch (RuntimeException e) {
                record(collector, delegationId, parentId, task, false, e.getMessage(),
                        elapsedMs(start), 0);
                throw e;
            }
        });
        return merge(outcomes);
    }

    /** 写一条委派事件（collector 为空时跳过） */
    private static void record(TraceCollector collector, String delegationId, String parentId,
                               FanoutExecutor.FanoutTask task, boolean success, String error,
                               long durationMs, int tokens) {
        if (collector == null) {
            return;
        }
        collector.recordDelegationEvent(new DelegationTraceEvent(
                delegationId, task.agent(), parentId, truncate(task.task(), 300),
                success, error, durationMs, tokens));
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 合并各子代理结果：成功输出正文，失败/超时显式标注（不掩盖，供主 Agent 如实汇总） */
    private String merge(List<FanoutExecutor.FanoutOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        for (FanoutExecutor.FanoutOutcome o : outcomes) {
            String label = labels.getOrDefault(o.agent(), "");
            if (o.error() != null) {
                sb.append("【子代理·").append(o.agent()).append("（").append(label).append("）·失败】\n")
                        .append(o.error()).append("\n\n");
            } else {
                sb.append("【子代理·").append(o.agent()).append("（").append(label).append("）】\n")
                        .append(o.result() == null ? "" : o.result()).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    /** 解析 tasks 参数：[{"agent":"名","task":"指令"}]；缺字段/非法整体判空（由调用方给可读提示） */
    private List<FanoutExecutor.FanoutTask> parseTasks(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = ARGS_MAPPER.readTree(arguments);
            JsonNode arr = root == null ? null : root.get(PARAM_TASKS);
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<FanoutExecutor.FanoutTask> tasks = new ArrayList<>();
            for (JsonNode node : arr) {
                if (node == null || !node.hasNonNull(FIELD_AGENT) || !node.hasNonNull(FIELD_TASK)) {
                    continue;
                }
                String agent = node.get(FIELD_AGENT).asText().trim();
                String task = node.get(FIELD_TASK).asText();
                if (agent.isEmpty() || task.isBlank()) {
                    continue;
                }
                tasks.add(new FanoutExecutor.FanoutTask(agent, task));
            }
            return tasks;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String candidateNames() {
        return String.join(", ", runners.keySet());
    }
}
