package com.example.agent.context;

import com.example.agent.agent.MultimodalUserMessages;
import com.example.agent.agent.SummarizingChatMemory.HistoryMessage;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.model.ContextTraceEvent;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.List;

/**
 * 请求级上下文预算管理器（P1-D 上下文工程显式化）。
 *
 * 一个对话回合创建一个实例，跨三个阶段工作：
 * <ol>
 *   <li><b>系统段</b>：{@link #recordSystem(String)} 记录动态 System Prompt（人设 + 技能 + 安全边界）用量；</li>
 *   <li><b>摘要 / 历史段</b>：{@link #applyMemoryInputs} 在重建请求级记忆前裁剪超预算的摘要与历史消息；</li>
 *   <li><b>检索段</b>：{@link #applyRetrieval} 在注入知识库片段前按预算保留高排名片段。</li>
 * </ol>
 * 回合结束调用 {@link #finish()} 生成 {@link ContextTraceEvent} 写入 trace，随 SSE {@code event: context}
 * 或同步响应下发，前端「上下文构成」面板据此展示四段预算与实际用量。
 *
 * <p>裁剪顺序：
 * <ul>
 *   <li>{@code balanced}（默认）：历史 → 摘要 → 检索；检索片段最后裁（优先保事实依据）；</li>
 *   <li>{@code history-priority}：摘要 → 历史 → 检索；优先保最近原文。</li>
 * </ul>
 * system 段受保护（安全边界不可裁剪），超预算只标记不截断；当前用户消息同样不可裁剪，
 * 其用量计入 history 段。
 *
 * <p>所有 token 数为估算值（{@link TokenEstimator}），用于预算控制与展示，不作为计费依据。
 */
public class ContextBudget {

    /** NumberedCitationContentInjector 注入的引用规则 + 边界标记的固定开销 */
    static final int RETRIEVAL_OVERHEAD_TOKENS = 180;

    /** 单个检索片段的编号/来源标题开销 */
    static final int SEGMENT_OVERHEAD_TOKENS = 12;

    private final ContextProperties props;
    private final TraceCollector collector;
    private final String strategy;
    private final boolean enabled;

    /** 系统提示（人设 + 技能说明 + 安全边界） */
    private int systemTokens;

    /** 摘要段实际用量（含摘要 SystemMessage 开销） */
    private int summaryTokens;
    /** 摘要裁剪前的用量（用于判断是否真的发生裁剪） */
    private int summaryOriginalTokens;
    private boolean summaryTruncated;

    /** 历史段实际用量（含本轮用户消息与图片占位） */
    private int historyTokens;
    /** 历史段预算（扣除系统/摘要/本轮后的可用上限） */
    private int historyBudgetEffective = 0;
    /** 参与预算的历史消息条数（不含本轮消息） */
    private int historyMessageCount;
    private int keptHistoryMessages;
    private int droppedHistoryMessages;

    /** 检索段实际用量（含引用规则固定开销） */
    private int retrievalTokens;
    /** 检索段预算（受 retrievalMaxTokens 与总预算剩余量共同约束） */
    private int retrievalBudgetEffective;
    private int retrievalCandidateCount;
    private int keptRetrievalSegments;
    private int droppedRetrievalSegments;

    /** 本回合当前用户消息用量（不可裁剪，计入 history 段） */
    private int currentTokens;

    public ContextBudget(ContextProperties props, TraceCollector collector) {
        this.props = props;
        this.collector = collector;
        this.strategy = props.getStrategy() == null ? "balanced" : props.getStrategy().trim().toLowerCase();
        this.enabled = props.isEnabled();
        this.retrievalBudgetEffective = enabled ? props.getRetrievalMaxTokens() : Integer.MAX_VALUE;
    }

    /** 记录动态 System Prompt 用量（在 planTurn 之后、重建记忆之前调用） */
    public synchronized void recordSystem(String systemMessage) {
        this.systemTokens = TokenEstimator.estimateMessage(systemMessage);
    }

    /**
     * 按预算裁剪摘要与历史消息（在 {@code SummarizingChatMemory.restore} 之前调用）。
     *
     * @param summary        持久化的历史摘要纯文本（可 null）
     * @param history        当前消息之前的近期对话（时间升序，可 null）
     * @param currentMessage 本轮用户消息文本（可 null；纯图片回合为空）
     * @param images         本轮用户图片（计入不可裁剪的当前消息用量）
     * @return 裁剪后的摘要与历史（历史保持原顺序，只从最旧开始丢弃）
     */
    public synchronized MemoryPlan applyMemoryInputs(String summary, List<HistoryMessage> history,
                                                     String currentMessage,
                                                     List<MultimodalUserMessages.ImageInput> images) {
        this.currentTokens = TokenEstimator.estimateMessage(currentMessage) + imageTokens(images);
        List<HistoryMessage> valid = validHistory(history);
        this.historyMessageCount = valid.size();

        String effectiveSummary = summary;
        int summaryCost = summaryCost(effectiveSummary);
        this.summaryOriginalTokens = summaryCost;

        // 1) 段内预算：摘要超限硬裁剪
        if (enabled && summaryCost > props.getSummaryMaxTokens()) {
            effectiveSummary = TokenEstimator.truncateToTokens(
                    effectiveSummary, props.getSummaryMaxTokens() - TokenEstimator.MESSAGE_OVERHEAD);
            summaryCost = summaryCost(effectiveSummary);
            summaryTruncated = summaryCost < summaryOriginalTokens;
        }

        // 2) 段内预算：历史超限从最旧消息开始丢弃（至少保留 minHistoryMessages 条）
        List<HistoryMessage> kept = valid;
        int historyCost = historyCost(valid);
        if (enabled && historyCost > props.getHistoryMaxTokens()) {
            kept = trimHistory(valid, props.getHistoryMaxTokens(), props.getMinHistoryMessages());
            historyCost = historyCost(kept);
        }

        // 3) 总预算：按策略决定先裁哪一段
        if (enabled) {
            int available = props.getTotalBudgetTokens() - systemTokens - currentTokens;
            if (summaryCost + historyCost > available) {
                if ("history-priority".equals(strategy)) {
                    // 先裁摘要，保住最近原文
                    int allowedSummary = Math.max(0, available - historyCost);
                    effectiveSummary = trimSummary(effectiveSummary, summaryCost, allowedSummary);
                    summaryCost = summaryCost(effectiveSummary);
                    if (summaryCost < summaryOriginalTokens) {
                        summaryTruncated = true;
                    }
                    if (historyCost > available) {
                        kept = trimHistory(kept, available, props.getMinHistoryMessages());
                        historyCost = historyCost(kept);
                    }
                } else {
                    // balanced：先裁历史，再裁摘要（检索片段在 augment 阶段最后裁）
                    kept = trimHistory(kept, Math.max(0, available - summaryCost),
                            props.getMinHistoryMessages());
                    historyCost = historyCost(kept);
                    int allowedSummary = Math.max(0, available - historyCost);
                    effectiveSummary = trimSummary(effectiveSummary, summaryCost, allowedSummary);
                    summaryCost = summaryCost(effectiveSummary);
                    if (summaryCost < summaryOriginalTokens) {
                        summaryTruncated = true;
                    }
                }
            }
        }

        this.summaryTokens = summaryCost;
        this.historyTokens = currentTokens + historyCost;
        this.historyBudgetEffective = enabled
                ? Math.max(0, Math.min(props.getHistoryMaxTokens(),
                        props.getTotalBudgetTokens() - systemTokens - currentTokens - summaryTokens))
                : props.getHistoryMaxTokens();
        this.keptHistoryMessages = kept.size();
        this.droppedHistoryMessages = valid.size() - kept.size();
        return new MemoryPlan(blankToNull(effectiveSummary), kept);
    }

    /**
     * 检索段可用预算：先扣除 system / summary / history 已用，再受 retrievalMaxTokens 上限约束。
     * 总开关关闭时返回 Integer.MAX_VALUE（不裁剪）。
     */
    public synchronized int retrievalBudget() {
        if (!enabled) {
            return Integer.MAX_VALUE;
        }
        int remaining = props.getTotalBudgetTokens() - systemTokens - summaryTokens - historyTokens;
        return Math.max(0, Math.min(props.getRetrievalMaxTokens(), remaining));
    }

    /**
     * 按预算保留检索片段：保持原顺序（即召回/精排排序），从尾部丢弃。
     * 引用规则固定开销先占位，剩余额度才分配给片段。
     */
    public synchronized List<Content> applyRetrieval(List<Content> contents) {
        List<Content> candidates = contents == null ? List.of() : contents;
        this.retrievalCandidateCount = candidates.size();
        this.retrievalBudgetEffective = retrievalBudget();
        if (candidates.isEmpty()) {
            this.retrievalTokens = 0;
            this.keptRetrievalSegments = 0;
            this.droppedRetrievalSegments = 0;
            return List.of();
        }
        if (!enabled) {
            this.retrievalTokens = RETRIEVAL_OVERHEAD_TOKENS + segmentsCost(candidates);
            this.keptRetrievalSegments = candidates.size();
            this.droppedRetrievalSegments = 0;
            return candidates;
        }
        List<Content> kept = new ArrayList<>();
        int used = RETRIEVAL_OVERHEAD_TOKENS;
        for (Content content : candidates) {
            int cost = SEGMENT_OVERHEAD_TOKENS + TokenEstimator.estimate(textOf(content));
            if (used + cost > retrievalBudgetEffective) {
                break;
            }
            kept.add(content);
            used += cost;
        }
        this.retrievalTokens = kept.isEmpty() ? 0 : used;
        this.keptRetrievalSegments = kept.size();
        this.droppedRetrievalSegments = candidates.size() - kept.size();
        return kept;
    }

    /** 生成上下文构成快照（不做裁剪，供单测/同步响应使用） */
    public synchronized ContextTraceEvent snapshot() {
        int total = systemTokens + summaryTokens + historyTokens + retrievalTokens;
        List<ContextTraceEvent.Segment> segments = List.of(
                new ContextTraceEvent.Segment("system", "系统提示（含安全边界）",
                        props.getSystemMaxTokens(), systemTokens,
                        systemTokens > props.getSystemMaxTokens(),
                        "人设 + 技能说明 + 安全边界（受保护，不裁剪）"),
                new ContextTraceEvent.Segment("summary", "历史摘要",
                        props.getSummaryMaxTokens(), summaryTokens, summaryTruncated,
                        summaryTokens == 0 ? "无历史摘要"
                                : summaryTruncated ? "超出预算，已裁剪" : "正常"),
                new ContextTraceEvent.Segment("retrieval", "知识库片段",
                        effectiveRetrievalBudget(), retrievalTokens, droppedRetrievalSegments > 0,
                        retrievalCandidateCount == 0 ? "本轮未触发检索"
                                : "候选 " + retrievalCandidateCount + " 条，保留 "
                                        + keptRetrievalSegments + " 条"
                                        + (droppedRetrievalSegments > 0
                                        ? "，裁剪 " + droppedRetrievalSegments + " 条" : "")),
                new ContextTraceEvent.Segment("history", "历史对话（含本轮）",
                        historyBudgetEffective, historyTokens, droppedHistoryMessages > 0,
                        historyMessageCount == 0 ? "无历史消息"
                                : "历史 " + historyMessageCount + " 条，保留 "
                                        + keptHistoryMessages + " 条"
                                        + (droppedHistoryMessages > 0
                                        ? "，裁剪 " + droppedHistoryMessages + " 条" : ""))
        );
        return new ContextTraceEvent(strategy, props.getTotalBudgetTokens(), total,
                total > props.getTotalBudgetTokens(), segments);
    }

    /** 回合结束：生成事件并写入请求级 trace（无 collector 时仅返回快照） */
    public synchronized ContextTraceEvent finish() {
        ContextTraceEvent event = snapshot();
        if (collector != null) {
            collector.recordContext(event);
        }
        return event;
    }

    private int effectiveRetrievalBudget() {
        if (!enabled) {
            return props.getRetrievalMaxTokens();
        }
        return retrievalBudgetEffective == Integer.MAX_VALUE
                ? props.getRetrievalMaxTokens() : retrievalBudgetEffective;
    }

    private static String trimSummary(String summary, int currentCost, int allowedTokens) {
        if (summary == null || summary.isBlank() || currentCost <= allowedTokens) {
            return summary;
        }
        return TokenEstimator.truncateToTokens(summary,
                allowedTokens - TokenEstimator.MESSAGE_OVERHEAD);
    }

    private static int summaryCost(String summary) {
        return summary == null || summary.isBlank() ? 0
                : TokenEstimator.estimateMessage(summary);
    }

    private static int historyCost(List<HistoryMessage> history) {
        int sum = 0;
        for (HistoryMessage m : history) {
            sum += messageCost(m);
        }
        return sum;
    }

    private static int segmentsCost(List<Content> contents) {
        int sum = 0;
        for (Content c : contents) {
            sum += SEGMENT_OVERHEAD_TOKENS + TokenEstimator.estimate(textOf(c));
        }
        return sum;
    }

    private static int messageCost(HistoryMessage m) {
        if (m == null) {
            return 0;
        }
        return TokenEstimator.estimateMessage(m.content()) + imageTokens(m.images());
    }

    private static int imageTokens(List<MultimodalUserMessages.ImageInput> images) {
        return images == null ? 0 : images.size() * TokenEstimator.IMAGE_TOKENS;
    }

    /** 与 SummarizingChatMemory.restore 的过滤规则保持一致（纯文本空白且无图片的消息丢弃） */
    private static List<HistoryMessage> validHistory(List<HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<HistoryMessage> out = new ArrayList<>();
        for (HistoryMessage m : history) {
            if (m == null) {
                continue;
            }
            boolean noText = m.content() == null || m.content().isBlank();
            boolean noImage = m.images() == null || m.images().isEmpty();
            if (!noText || !noImage) {
                out.add(m);
            }
        }
        return out;
    }

    /** 从最旧消息开始丢弃，直到满足预算或只剩 minKeep 条 */
    private static List<HistoryMessage> trimHistory(List<HistoryMessage> messages,
                                                    int maxTokens, int minKeep) {
        if (messages.isEmpty()) {
            return messages;
        }
        int keepAtLeast = Math.max(0, Math.min(minKeep, messages.size()));
        int total = historyCost(messages);
        int drop = 0;
        while (total > maxTokens && messages.size() - drop > keepAtLeast) {
            total -= messageCost(messages.get(drop));
            drop++;
        }
        return drop == 0 ? List.copyOf(messages) : List.copyOf(messages.subList(drop, messages.size()));
    }

    private static String textOf(Content content) {
        if (content == null || content.textSegment() == null) {
            return "";
        }
        String text = content.textSegment().text();
        return text == null ? "" : text;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * 记忆重建方案：裁剪后的摘要与历史消息。
     *
     * @param summary 裁剪后的摘要（null/空 = 不注入摘要）
     * @param history 裁剪后保留的历史（保持时间升序）
     */
    public record MemoryPlan(String summary, List<HistoryMessage> history) {
    }
}
