package com.example.agent.observability.model;

import java.util.List;

/**
 * 内容安全检测 trace 事件（event: safety，P4-17）。
 *
 * 一次检测一行，回合内可多条（多个通道/多次工具结果）。backend 透传前端（前端忽略）
 * 并落 prompt_safety_record 表，供监控期误报复盘与 P4-16 审计后台使用。
 *
 * @param channel   检测通道：input / rag / tool_output / output / outbound_http
 * @param score     规则加权总分
 * @param verdict   判定：benign / suspicious / malicious
 * @param detector  判定来源：rule / llm / dlp
 * @param action    实际动作：none / warn / block（monitor 模式恒 none，决策意图由 score/verdict 体现）
 * @param llmReason LLM 分类器理由（detector=llm 时）
 * @param ruleHits  命中规则明细（ruleId + category + weight）
 * @param excerpt   命中片段（截断，复盘用；不存全量原文）
 * @param latencyMs 本次检测耗时
 */
public record SafetyTraceEvent(String channel,
                               int score,
                               String verdict,
                               String detector,
                               String action,
                               String llmReason,
                               List<RuleHitView> ruleHits,
                               String excerpt,
                               long latencyMs) implements TraceEvent {

    /** 命中规则明细（跨服务协议，字段名与 backend SafetyData 对齐） */
    public record RuleHitView(String ruleId, String category, int weight) {
    }
}