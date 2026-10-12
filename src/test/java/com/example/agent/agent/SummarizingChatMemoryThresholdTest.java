package com.example.agent.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SummarizingChatMemory} 压缩阈值单测（P2-12）。
 *
 * 固化「默认窗口 40/20」的行为契约（生产配置 agent.memory.max-messages=40 / keep-recent=20）：
 *   1. 第 41 条才触发（严格大于），压缩后为 1 条摘要 + 最近 20 条原文
 *   2. 摘要失败/返回空白时不重组、不中断对话（窗口继续滑动）
 *   3. 多轮压缩把旧摘要合并进新材料，摘要始终只有 1 条（不无限堆叠）
 *   4. 非摘要系统指令（人设）原样保留并排在摘要之前
 *
 * 纯单测：假 ChatModel 只记录请求，不连真实 LLM。
 */
class SummarizingChatMemoryThresholdTest {

    private static final int MAX = 40;
    private static final int KEEP = 20;

    /** 可配置的假摘要模型：区分「摘要请求」与普通对话，记录摘要请求体 */
    static class FakeSummarizer implements ChatModel {
        ChatRequest summarizeRequest;
        int summarizeCalls;
        String reply = "用户询问了报销流程，助手给出了三步说明。";
        RuntimeException failure;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            boolean isSummary = !chatRequest.messages().isEmpty()
                    && chatRequest.messages().get(0) instanceof SystemMessage sm
                    && sm.text().contains("压缩成一段");
            if (!isSummary) {
                return ChatResponse.builder().aiMessage(AiMessage.from("收到")).build();
            }
            summarizeCalls++;
            summarizeRequest = chatRequest;
            if (failure != null) {
                throw failure;
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    // ==================== 阈值边界 ====================

    @Test
    void fortyMessagesDoNotTriggerCompression() {
        FakeSummarizer model = new FakeSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::1", model, MAX, KEEP);

        addDialogue(memory, MAX);

        assertThat(memory.messages()).hasSize(MAX);
        assertThat(model.summarizeCalls).isZero();
        assertThat(memory.summaryText()).isNull();
    }

    @Test
    void fortyFirstMessageTriggersCompressionKeepingSummaryPlusRecent() {
        FakeSummarizer model = new FakeSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::2", model, MAX, KEEP);

        addDialogue(memory, MAX + 1);

        assertThat(model.summarizeCalls).isEqualTo(1);
        // 1 条摘要 SystemMessage + 最近 KEEP 条原文
        assertThat(memory.messages()).hasSize(KEEP + 1);
        assertThat(summaryCount(memory)).isEqualTo(1);
        assertThat(memory.messages().get(0)).isInstanceOf(SystemMessage.class);
        // summaryText() 返回去掉引导前缀的纯文本，供 backend 落库
        assertThat(memory.summaryText()).isEqualTo(model.reply);
        // 而记忆内部保存的系统消息带引导前缀（让模型知道这是历史摘要）
        SystemMessage summaryMessage = (SystemMessage) memory.messages().get(0);
        assertThat(summaryMessage.text().trim()).startsWith(SummarizingChatMemory.SUMMARY_PREFIX.trim());
        assertThat(summaryMessage.text()).doesNotContain("SUMMARIZE_PROMPT");
    }

    @Test
    void compressionKeepsOnlyTheMostRecentMessages() {
        FakeSummarizer model = new FakeSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::3", model, MAX, KEEP);

        for (int i = 0; i < MAX + 1; i++) {
            memory.add(UserMessage.from("第" + i + "条"));
        }

        List<ChatMessage> kept = memory.messages().subList(1, memory.messages().size());
        // 保留的是最新的 KEEP 条（第 21..40 条），最早的 21 条已被压进摘要
        assertThat(kept).hasSize(KEEP);
        assertThat(kept.get(0).toString()).contains("第21条");
        assertThat(kept.get(KEEP - 1).toString()).contains("第40条");

        String summarizeMaterial = model.summarizeRequest.messages().toString();
        assertThat(summarizeMaterial).contains("第0条");
        assertThat(summarizeMaterial).doesNotContain("第21条");
    }

    // ==================== 降级：失败不中断对话 ====================

    @Test
    void summarizerFailureKeepsOriginalMessages() {
        FakeSummarizer model = new FakeSummarizer();
        model.failure = new IllegalStateException("LLM 超时");
        SummarizingChatMemory memory = new SummarizingChatMemory("u::4", model, MAX, KEEP);

        addDialogue(memory, MAX + 1);

        // 压缩失败：消息原样保留（窗口继续滑动），不抛出、不产生摘要
        assertThat(model.summarizeCalls).isEqualTo(1);
        assertThat(memory.messages()).hasSize(MAX + 1);
        assertThat(memory.summaryText()).isNull();
    }

    @Test
    void blankSummaryKeepsOriginalMessages() {
        FakeSummarizer model = new FakeSummarizer();
        model.reply = "   ";
        SummarizingChatMemory memory = new SummarizingChatMemory("u::5", model, MAX, KEEP);

        addDialogue(memory, MAX + 1);

        assertThat(memory.messages()).hasSize(MAX + 1);
        assertThat(memory.summaryText()).isNull();
    }

    // ==================== 多轮压缩 ====================

    @Test
    void secondCompressionMergesOldSummaryAndKeepsSingleSummaryMessage() {
        FakeSummarizer model = new FakeSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::6", model, MAX, KEEP);

        addDialogue(memory, MAX + 1);
        assertThat(memory.messages()).hasSize(KEEP + 1);

        // 再攒满一次：压缩后 21 条，再加 20 条 → 41 条再次触发
        addDialogue(memory, KEEP);
        assertThat(model.summarizeCalls).isEqualTo(2);

        // 旧摘要必须作为材料喂给模型（而不是被丢掉）
        String material = model.summarizeRequest.messages().toString();
        assertThat(material).contains("【更早之前的历史摘要】");
        assertThat(material).contains(model.reply);

        // 摘要始终只有一条，不无限堆叠
        assertThat(memory.messages()).hasSize(KEEP + 1);
        assertThat(summaryCount(memory)).isEqualTo(1);
        assertThat(memory.summaryText()).isEqualTo(model.reply);
    }

    @Test
    void nonSummarySystemMessageIsPreservedBeforeSummary() {
        FakeSummarizer model = new FakeSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::7", model, MAX, KEEP);

        memory.add(SystemMessage.from("你是严谨的中文助手。"));
        addDialogue(memory, MAX); // 共 41 条 → 触发

        assertThat(memory.messages()).hasSize(KEEP + 2);
        // 人设原样保留，且排在摘要之前
        assertThat(memory.messages().get(0)).isEqualTo(SystemMessage.from("你是严谨的中文助手。"));
        assertThat(summaryCount(memory)).isEqualTo(1);
        assertThat(((SystemMessage) memory.messages().get(1)).text().trim())
                .startsWith(SummarizingChatMemory.SUMMARY_PREFIX.trim());
    }

    // ==================== restore 与构造校验 ====================

    @Test
    void restoreWithFullHistoryCompressesOnFirstAdd() {
        FakeSummarizer model = new FakeSummarizer();
        List<SummarizingChatMemory.HistoryMessage> history = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            history.add(new SummarizingChatMemory.HistoryMessage(i % 2 == 0 ? "user" : "assistant", "历史" + i));
        }

        SummarizingChatMemory memory = SummarizingChatMemory.restore("u::8", model, MAX, KEEP, null, history);
        assertThat(memory.messages()).hasSize(MAX);

        memory.add(UserMessage.from("本轮新问题"));

        assertThat(model.summarizeCalls).isEqualTo(1);
        assertThat(memory.messages()).hasSize(KEEP + 1);
        assertThat(memory.summaryText()).isEqualTo(model.reply);
    }

    @Test
    void keepRecentMustBeSmallerThanMaxMessages() {
        assertThatThrownBy(() -> new SummarizingChatMemory("u::9", new FakeSummarizer(), 20, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keepRecent");

        assertThatThrownBy(() -> new SummarizingChatMemory("u::9", new FakeSummarizer(), 20, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== 夹具 ====================

    /** 交替加入 user/assistant 消息，凑满 count 条 */
    private static void addDialogue(SummarizingChatMemory memory, int count) {
        for (int i = 0; i < count; i++) {
            memory.add(i % 2 == 0 ? UserMessage.from("提问" + i) : AiMessage.from("回答" + i));
        }
    }

    /** 统计「历史摘要」系统消息条数（压缩后应恒为 ≤1） */
    private static long summaryCount(SummarizingChatMemory memory) {
        String prefix = SummarizingChatMemory.SUMMARY_PREFIX.trim();
        return memory.messages().stream()
                .filter(m -> m instanceof SystemMessage sm && sm.text().trim().startsWith(prefix))
                .count();
    }
}