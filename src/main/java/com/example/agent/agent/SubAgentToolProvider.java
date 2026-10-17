package com.example.agent.agent;

import com.example.agent.observability.AgentScope;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 子代理委派工具（Agent-as-Tool 的「那一把工具」）。
 *
 * 对主 Agent 暴露一个动态命名的工具 {@code delegate_<skillName>}，入参只有一个 {@code task}
 * （交给子代理的完整子任务指令）；执行时由 {@link SubAgentRunner} 真正跑子代理的推理循环，
 * 把子代理的结论文本作为工具结果回给主 Agent。
 *
 * 为什么用 {@link ToolProvider}（而非 {@code @Tool} 注解方法）：
 *   工具名需要按技能名动态生成（多个子代理不能同名），且不希望在编译期固定；
 *   这与 MCP 动态工具走的是同一条扩展点（{@code Skill#toolProvider()}），主 Agent 装配零改动。
 *
 * 预算：委派次数计入回合级「全局预算」（{@link TraceCollector#delegations()}），
 * 达上限后不再委派、直接回一条提示，避免多子代理 fan-out 把成本放大。
 */
public class SubAgentToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SubAgentToolProvider.class);

    /** 委派工具的唯一入参名 */
    public static final String TASK_PARAM = "task";

    /** OpenAI 兼容函数名允许的字符集：字母/数字/下划线/连字符 */
    private static final Pattern ILLEGAL_NAME_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    /** OpenAI 兼容函数名长度上限 */
    private static final int MAX_TOOL_NAME_LEN = 64;

    /**
     * 子代理执行体：给定任务指令与会话记忆键，返回子代理的结论文本。
     * 由 {@link SubAgentFactory} 用真实 AiServices 实现；单测可注入 lambda。
     */
    @FunctionalInterface
    public interface SubAgentRunner {
        String run(String task, Object memoryId);
    }

    private static final ObjectMapper ARGS_MAPPER = new ObjectMapper();

    private final String skillName;
    private final String toolName;
    private final String toolDescription;
    private final SubAgentRunner runner;
    private final int maxDelegationsPerTurn;
    private final int maxTaskChars;

    public SubAgentToolProvider(String skillName,
                                String toolDescription,
                                SubAgentRunner runner,
                                int maxDelegationsPerTurn,
                                int maxTaskChars) {
        this.skillName = skillName == null || skillName.isBlank() ? "sub" : skillName.trim();
        this.toolName = toolNameOf(this.skillName);
        this.toolDescription = toolDescription;
        this.runner = runner;
        this.maxDelegationsPerTurn = Math.max(1, maxDelegationsPerTurn);
        this.maxTaskChars = Math.max(1, maxTaskChars);
    }

    /** 该委派工具对主 Agent 可见的工具名（delegate_{skillName}） */
    public String toolName() {
        return toolName;
    }

    /** 子代理执行体（供 fan-out 扇出复用：同一子代理既可按工具委派，也可被并行扇出） */
    public SubAgentRunner runner() {
        return runner;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolSpecification spec = ToolSpecification.builder()
                .name(toolName)
                .description(toolDescription)
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty(TASK_PARAM,
                                "交给子代理的完整子任务指令（含目标、约束与预期产出）；子代理会独立完成并返回结论文本")
                        .required(TASK_PARAM)
                        .build())
                .build();
        ToolExecutor executor = (toolRequest, memoryId) -> delegate(toolRequest, memoryId);
        return ToolProviderResult.builder().add(spec, executor).build();
    }

    /** 委派执行：预算校验 → 取 task → 跑子代理 → 返回结论文本（任何异常都转为可读文本，不炸主循环） */
    private String delegate(ToolExecutionRequest request, Object memoryId) {
        TraceCollector collector = TraceContextHolder.current();
        if (collector != null && collector.delegations() >= maxDelegationsPerTurn) {
            log.warn("[SubAgent] 「{}」委派次数已达回合上限 {}，拒绝本次委派",
                    skillName, maxDelegationsPerTurn);
            return "本回合子代理「" + skillName + "」的委派次数已达上限（" + maxDelegationsPerTurn
                    + " 次），本次委派未执行。请基于已有信息直接作答，或简化任务。";
        }

        String task = extractTask(request.arguments());
        if (task == null || task.isBlank()) {
            return "委派失败：缺少 " + TASK_PARAM + " 参数（需提供交给子代理的完整任务指令）。";
        }
        if (collector != null) {
            collector.recordDelegation();
        }
        try {
            // 进入子代理作用域：其内部执行的所有工具调用会被 trace 标注为归属于本子代理
            String result = AgentScope.runScoped(skillName, () -> runner.run(clamp(task), memoryId));
            return result == null || result.isBlank() ? "子代理未返回有效内容。" : result;
        } catch (Exception e) {
            log.warn("[SubAgent] 「{}」执行失败: {}", skillName, e.getMessage());
            return "子代理「" + skillName + "」执行失败：" + e.getMessage();
        }
    }

    /** 从工具参数 JSON 取 task 文本；参数非对象/解析失败/字段缺失均返回 null（由调用方给出可读提示） */
    private static String extractTask(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = ARGS_MAPPER.readTree(arguments);
            JsonNode task = node == null ? null : node.get(TASK_PARAM);
            return task == null || !task.isTextual() ? null : task.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** 任务指令截断：防止超长任务灌爆子代理上下文（截断处显式留痕，避免子代理误以为完整） */
    private String clamp(String task) {
        if (task.length() <= maxTaskChars) {
            return task;
        }
        return task.substring(0, maxTaskChars) + "\n…（任务指令超过 " + maxTaskChars + " 字，已截断）";
    }

    /** 技能名 → 合法工具名 delegate_{sanitized}，非法字符替换为下划线并限制长度 */
    static String toolNameOf(String skillName) {
        String base = skillName == null ? "" : ILLEGAL_NAME_CHARS.matcher(skillName.trim()).replaceAll("_");
        if (base.isEmpty()) {
            base = "sub";
        }
        String name = "delegate_" + base;
        return name.length() <= MAX_TOOL_NAME_LEN ? name : name.substring(0, MAX_TOOL_NAME_LEN);
    }
}
