package com.example.agent.observability.model;

/**
 * 一次对话回合内可观测事件的密封接口（agent → backend SSE/JSON 协议，见 spec 附录）。
 *
 * <ul>
 *   <li>{@link ToolTraceEvent}：一次工具调用（名/参数/结果/成败/耗时）</li>
 *   <li>{@link RagTraceEvent}：一次 RAG 检索（查询 + 召回片段）</li>
 *   <li>{@link UsageTraceEvent}：整轮聚合的 token 用量/模型/轮次/耗时</li>
 *   <li>{@link SafetyTraceEvent}：一次内容安全检测（通道/分数/判定/动作，P4-17）</li>
 * </ul>
 */
public sealed interface TraceEvent
        permits ToolTraceEvent, RagTraceEvent, UsageTraceEvent, TraceResetEvent, SafetyTraceEvent {
}
