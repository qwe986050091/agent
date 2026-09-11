package com.example.agent.observability.model;

/**
 * 主模型失败、整条链将用备用模型重放前发出的重置信号（event: trace-reset）。
 *
 * 工具往返不产生 token：主模型可能已执行过工具/触发过 RAG 后才失败，
 * fallback 会重放整条链。接收方（backend/前端）收到本事件必须清空本 trace
 * 已累积的 tool/rag/usage，避免重复面板、重复审计与重复计费。
 */
public record TraceResetEvent() implements TraceEvent {
}
