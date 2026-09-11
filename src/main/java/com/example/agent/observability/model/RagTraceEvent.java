package com.example.agent.observability.model;

import java.util.List;

/**
 * RAG 检索 trace 事件（event: rag）。
 *
 * @param query    触发检索的用户问题（截断 150 字符）
 * @param segments 召回片段（无命中为空列表，用于命中率统计）
 */
public record RagTraceEvent(String query, List<RagSegment> segments) implements TraceEvent {
}
