package com.example.agent.context;

import com.example.agent.agent.MultimodalUserMessages;
import com.example.agent.agent.SummarizingChatMemory.HistoryMessage;
import com.example.agent.observability.model.ContextTraceEvent;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文预算单测（P1-D）：
 * - 四段预算分配与段内裁剪（摘要截断 / 历史从最旧丢弃 / 检索按排序保留）；
 * - balanced 与 history-priority 两种策略的裁剪顺序差异（A/B 的代码依据）；
 * - 总开关关闭 = 不裁剪（回滚语义）；
 * - token 估算的中英文口径。
 */
class ContextBudgetTest {

    private static String repeat(String s, int n) {
        return s.repeat(n);
    }

    private static HistoryMessage msg(String role, String content) {
        return new HistoryMessage(role, content);
    }

    private static Content content(String text) {
        return Content.from(TextSegment.from(text));
    }

    private static ContextBudget budget(ContextProperties props) {
        return new ContextBudget(props, null);
    }

    @Test
    void underBudget_keepsSummaryHistoryAndAllRetrievalSegments() {
        ContextProperties props = new ContextProperties();
        ContextBudget budget = budget(props);
        budget.recordSystem(repeat("系", 20));
        List<HistoryMessage> history = List.of(
                msg("user", repeat("一", 10)),
                msg("assistant", repeat("二", 10)),
                msg("user", repeat("三", 10)));

        ContextBudget.MemoryPlan plan = budget.applyMemoryInputs(
                repeat("摘", 30), history, repeat("问", 10), List.of());
        List<Content> contents = List.of(content(repeat("片", 20)), content(repeat("段", 20)));
        List<Content> kept = budget.applyRetrieval(contents);

        assertThat(plan.summary()).isEqualTo(repeat("摘", 30));
        assertThat(plan.history()).hasSize(3);
        assertThat(kept).hasSize(2);

        ContextTraceEvent snapshot = budget.snapshot();
        assertThat(snapshot.segments()).hasSize(4);
        assertThat(snapshot.segments()).extracting(ContextTraceEvent.Segment::key)
                .containsExactly("system", "summary", "retrieval", "history");
        assertThat(snapshot.overBudget()).isFalse();
        assertThat(snapshot.segments()).noneMatch(ContextTraceEvent.Segment::truncated);
    }

    @Test
    void historyOverSegmentBudget_dropsOldestButKeepsRecentMessages() {
        ContextProperties props = new ContextProperties();
        props.setHistoryMaxTokens(200);
        props.setMinHistoryMessages(2);
        ContextBudget budget = budget(props);
        budget.recordSystem(repeat("系", 10));

        List<HistoryMessage> history = new ArrayList<>();
        String digits = "一二三四五六";
        for (int i = 1; i <= 6; i++) {
            // 每条 50 个汉字 = 50 token + 4 开销 = 54（中文按 1 token/字符计）
            history.add(msg(i % 2 == 1 ? "user" : "assistant",
                    repeat(String.valueOf(digits.charAt(i - 1)), 50)));
        }

        ContextBudget.MemoryPlan plan = budget.applyMemoryInputs(
                null, history, "问题", List.of());

        // 324 → 丢 3 条后 162 ≤ 200；保留最近 3 条（第 4-6 条）
        assertThat(plan.history()).hasSize(3);
        assertThat(plan.history()).extracting(HistoryMessage::content)
                .containsExactly(repeat("四", 50), repeat("五", 50), repeat("六", 50));

        ContextTraceEvent.Segment historySegment = budget.snapshot().segments().stream()
                .filter(s -> "history".equals(s.key())).findFirst().orElseThrow();
        assertThat(historySegment.truncated()).isTrue();
        assertThat(historySegment.detail()).contains("裁剪 3 条");
    }

    @Test
    void summaryOverSegmentBudget_isTruncated() {
        ContextProperties props = new ContextProperties();
        props.setSummaryMaxTokens(100);
        ContextBudget budget = budget(props);

        ContextBudget.MemoryPlan plan = budget.applyMemoryInputs(
                repeat("摘", 300), List.of(), "", List.of());

        assertThat(plan.summary()).isNotEqualTo(repeat("摘", 300));
        assertThat(plan.summary()).endsWith("（已按上下文预算裁剪）");
        // 含消息开销后不超过段预算
        assertThat(TokenEstimator.estimateMessage(plan.summary())).isLessThanOrEqualTo(100);

        ContextTraceEvent.Segment summarySegment = budget.snapshot().segments().stream()
                .filter(s -> "summary".equals(s.key())).findFirst().orElseThrow();
        assertThat(summarySegment.truncated()).isTrue();
    }

    @Test
    void balancedStrategyTrimsHistoryBeforeSummary() {
        ContextProperties props = tightStrategyProps("balanced");
        ContextBudget budget = budget(props);
        budget.recordSystem(repeat("系", 100));

        ContextBudget.MemoryPlan plan = budget.applyMemoryInputs(
                repeat("摘", 300),
                List.of(msg("user", repeat("旧", 100)), msg("assistant", repeat("新", 100))),
                repeat("问", 10), List.of());

        // balanced：先丢历史最旧一条，再截断摘要
        assertThat(plan.history()).hasSize(1);
        assertThat(plan.history().get(0).content()).isEqualTo(repeat("新", 100));
    }

    @Test
    void historyPriorityStrategyKeepsHistoryAndTrimsSummaryFirst() {
        ContextProperties props = tightStrategyProps("history-priority");
        ContextBudget budget = budget(props);
        budget.recordSystem(repeat("系", 100));

        ContextBudget.MemoryPlan plan = budget.applyMemoryInputs(
                repeat("摘", 300),
                List.of(msg("user", repeat("旧", 100)), msg("assistant", repeat("新", 100))),
                repeat("问", 10), List.of());

        // history-priority：历史完整保留，摘要先被截断
        assertThat(plan.history()).hasSize(2);
        assertThat(TokenEstimator.estimateMessage(plan.summary())).isLessThanOrEqualTo(174);

        ContextTraceEvent snapshot = budget.snapshot();
        assertThat(snapshot.strategy()).isEqualTo("history-priority");
        assertThat(snapshot.segments()).filteredOn(s -> "history".equals(s.key()))
                .allMatch(s -> !s.truncated());
        assertThat(snapshot.segments()).filteredOn(s -> "summary".equals(s.key()))
                .allMatch(ContextTraceEvent.Segment::truncated);
    }

    /** 构造总预算吃紧的配置：system 104 + current 14 + summary 304 + history 208 > 500 */
    private static ContextProperties tightStrategyProps(String strategy) {
        ContextProperties props = new ContextProperties();
        props.setStrategy(strategy);
        props.setTotalBudgetTokens(500);
        props.setSummaryMaxTokens(500);
        props.setHistoryMaxTokens(500);
        props.setMinHistoryMessages(1);
        return props;
    }

    @Test
    void retrievalOverBudgetKeepsTopOrderedSegmentsAndDropsTail() {
        ContextProperties props = new ContextProperties();
        props.setRetrievalMaxTokens(250);
        ContextBudget budget = budget(props);

        // 引用规则固定开销 180 + 每条 12 + 40 字 = 52；只能放下第 1 条（232 ≤ 250）
        List<Content> contents = List.of(
                content(repeat("一", 40)), content(repeat("二", 40)), content(repeat("三", 40)));

        List<Content> kept = budget.applyRetrieval(contents);

        assertThat(kept).containsExactly(contents.get(0));
        ContextTraceEvent.Segment retrievalSegment = budget.snapshot().segments().stream()
                .filter(s -> "retrieval".equals(s.key())).findFirst().orElseThrow();
        assertThat(retrievalSegment.truncated()).isTrue();
        assertThat(retrievalSegment.tokens()).isBetween(1, 250);
        assertThat(retrievalSegment.detail()).contains("候选 3 条").contains("保留 1 条");
    }

    @Test
    void disabled_doesNotTrimAnything() {
        ContextProperties props = new ContextProperties();
        props.setEnabled(false);
        props.setSummaryMaxTokens(10);
        props.setHistoryMaxTokens(10);
        props.setRetrievalMaxTokens(10);
        ContextBudget budget = budget(props);

        List<HistoryMessage> history = List.of(
                msg("user", repeat("一", 100)), msg("assistant", repeat("二", 100)));
        List<Content> contents = List.of(content(repeat("片", 100)), content(repeat("段", 100)));

        ContextBudget.MemoryPlan plan = budget.applyMemoryInputs(
                repeat("摘", 100), history, "问题", List.of());

        assertThat(plan.summary()).isEqualTo(repeat("摘", 100));
        assertThat(plan.history()).hasSize(2);
        assertThat(budget.applyRetrieval(contents)).hasSize(2);
        assertThat(budget.snapshot().segments()).noneMatch(ContextTraceEvent.Segment::truncated);
    }

    @Test
    void imageTokensAreCountedAgainstHistoryBudget() {
        ContextProperties props = new ContextProperties();
        ContextBudget budget = budget(props);
        budget.recordSystem("人设");

        budget.applyMemoryInputs(null, List.of(), "",
                List.of(new MultimodalUserMessages.ImageInput("image/png", "aGVsbG8=")));

        ContextTraceEvent.Segment historySegment = budget.snapshot().segments().stream()
                .filter(s -> "history".equals(s.key())).findFirst().orElseThrow();
        assertThat(historySegment.tokens()).isGreaterThanOrEqualTo(TokenEstimator.IMAGE_TOKENS);
    }

    @Test
    void tokenEstimator_distinguishesCjkAndAscii() {
        assertThat(TokenEstimator.estimate("你好世界")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("hello world")).isEqualTo(3);
        assertThat(TokenEstimator.estimate("")).isZero();
        assertThat(TokenEstimator.estimateMessage("hi")).isEqualTo(1 + TokenEstimator.MESSAGE_OVERHEAD);

        String truncated = TokenEstimator.truncateToTokens(repeat("字", 100), 20);
        assertThat(TokenEstimator.estimate(truncated)).isLessThanOrEqualTo(20);
        assertThat(truncated).endsWith("（已按上下文预算裁剪）");
    }
}
