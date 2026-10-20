package com.example.agent.eval;

import com.example.agent.observability.model.RagTraceEvent;
import com.example.agent.observability.model.SafetyTraceEvent;
import com.example.agent.observability.model.ToolTraceEvent;
import com.example.agent.observability.model.UsageTraceEvent;

import java.util.List;

/**
 * 被测 Agent（SUT）对一条用例的「一回合」产出——评测的观测单元。
 *
 * <p>直接复用 agent 既有的 trace 模型（{@link ToolTraceEvent} / {@link RagTraceEvent} /
 * {@link SafetyTraceEvent} / {@link UsageTraceEvent}）：这正是文档要求的
 * 「runner 复用现有 TraceCollector 拿工具/检索/用量轨迹」——工具选择、RAG 召回、内容安全、
 * token 用量四种轨迹都在这里，打分层只消费本结构，与「怎么拿到」无关（in-process 或 HTTP 均可）。
 *
 * @param blocked  是否被输入侧安全闸门拒绝（400 SAFETY_BLOCKED 等价物）；true 时 answer 通常为空
 * @param error    本回合的异常摘要（如模型超时/工具轮次超限）；成功为 null
 */
public record EvalTurn(String traceId,
                       String answer,
                       String summary,
                       List<ToolTraceEvent> tools,
                       List<RagTraceEvent> retrievals,
                       List<SafetyTraceEvent> safeties,
                       UsageTraceEvent usage,
                       boolean blocked,
                       String error,
                       long latencyMs) {

    public EvalTurn {
        tools = tools == null ? List.of() : List.copyOf(tools);
        retrievals = retrievals == null ? List.of() : List.copyOf(retrievals);
        safeties = safeties == null ? List.of() : List.copyOf(safeties);
        answer = answer == null ? "" : answer;
    }

    /** 失败兜底：invoker 抛异常时构造，保证用例仍能被评分（计为 fail） */
    public static EvalTurn failure(String traceId, String error, long latencyMs) {
        return new EvalTurn(traceId, "", null, List.of(), List.of(), List.of(),
                new UsageTraceEvent(null, 0, 0, 0, 0, false, false, latencyMs), false, error, latencyMs);
    }

    /** 本回合实际调用的工具名（按发生顺序，含子代理归属工具；去重保序） */
    public List<String> toolNames() {
        return tools.stream().map(ToolTraceEvent::name).distinct().toList();
    }
}
