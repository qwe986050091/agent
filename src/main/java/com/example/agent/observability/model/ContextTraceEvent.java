package com.example.agent.observability.model;

import java.util.List;

/**
 * 上下文构成 trace 事件（event: context，P1-D）。
 *
 * 由 {@code ContextBudget} 在回合装配阶段统计，回合结束前随 usage 之前下发，
 * 供前端「上下文构成」面板展示四段预算与实际用量。
 *
 * @param strategy          裁剪策略：balanced | history-priority
 * @param totalBudgetTokens 四段合计预算
 * @param totalTokens       实际估算总用量（system + summary + retrieval + history）
 * @param overBudget        实际用量是否超出总预算（受保护段导致超限时为 true）
 * @param segments          四段明细（system / summary / retrieval / history）
 */
public record ContextTraceEvent(String strategy,
                                int totalBudgetTokens,
                                int totalTokens,
                                boolean overBudget,
                                List<Segment> segments) implements TraceEvent {

    /**
     * 单段明细。
     *
     * @param key          段标识：system / summary / retrieval / history
     * @param label        中文展示名
     * @param budgetTokens 该段预算
     * @param tokens       该段实际估算用量
     * @param truncated    是否发生了裁剪
     * @param detail       补充说明（保留/丢弃条数等，可为空）
     */
    public record Segment(String key,
                          String label,
                          int budgetTokens,
                          int tokens,
                          boolean truncated,
                          String detail) {
    }
}
