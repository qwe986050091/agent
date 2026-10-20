package com.example.agent.observability.model;

import java.util.List;

/**
 * RAG 检索 trace 事件（event: rag）。
 *
 * @param query    触发检索的用户问题（截断 150 字符）
 * @param segments 召回片段（无命中为空列表，用于命中率统计）
 * @param agentId  发起检索的 Agent 标识：null=主 Agent（顶层），非空=子代理技能名
 *                 （子代理也接 RAG，召回需要归属到发起它的 Agent）
 */
public record RagTraceEvent(String query, List<RagSegment> segments, String agentId) implements TraceEvent {
}
