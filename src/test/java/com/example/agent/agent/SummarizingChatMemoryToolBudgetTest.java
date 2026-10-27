package com.example.agent.agent;

import com.example.agent.context.ToolResultGovernor;
import com.example.agent.context.ToolResultProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SummarizingChatMemory} × {@link ToolResultGovernor} 集成单测（P0）。
 *
 * 验证治理发生在「工具结果写入记忆」的时点：模型下一轮看到的就是受限后的内容；
 * 同时固化 P1 的结构化摘要要求（压缩后保留任务状态，避免压缩点断上下文）。
 */
class SummarizingChatMemoryToolBudgetTest {

    private static final int MAX = 40;
    private static final int KEEP = 20;

    /** 记录摘要请求的假模型（与 SummarizingChatMemoryThresholdTest 同款判定） */
    static class RecordingSummarizer implements ChatModel {
        ChatRequest summarizeRequest;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            boolean isSummary = !chatRequest.messages().isEmpty()
                    && chatRequest.messages().get(0) instanceof SystemMessage sm
                    && sm.text().contains("压缩成一段");
            if (isSummary) {
                summarizeRequest = chatRequest;
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("【任务目标】测试\n【未完成】无"))
                    .build();
        }
    }

    private static ToolResultProperties props(int perResult, int total) {
        ToolResultProperties props = new ToolResultProperties();
        props.setMaxCharsPerResult(perResult);
        props.setMaxTotalCharsPerTurn(total);
        props.setHeadChars(20);
        props.setTailChars(10);
        return props;
    }

    private static String text(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return sb.toString();
    }

    @Test
    void toolResultIsTruncatedWhenAddedToMemory() {
        RecordingSummarizer model = new RecordingSummarizer();
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 1000));
        SummarizingChatMemory memory = new SummarizingChatMemory("u::1", model, MAX, KEEP, governor);

        memory.add(ToolExecutionResultMessage.from("call-1", "fetchWebPage", text(500)));

        String stored = ((ToolExecutionResultMessage) memory.messages().get(0)).text();
        assertThat(stored).contains(ToolResultGovernor.TRUNCATED_MARK);
        assertThat(stored.length()).isLessThan(500);
        assertThat(governor.truncatedCount()).isEqualTo(1);
    }

    @Test
    void oldToolResultsAreEvictedWhenCumulativeBudgetExceeded() {
        RecordingSummarizer model = new RecordingSummarizer();
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 100));
        SummarizingChatMemory memory = new SummarizingChatMemory("u::2", model, MAX, KEEP, governor);

        memory.add(ToolExecutionResultMessage.from("call-1", "toolA", text(100)));
        memory.add(ToolExecutionResultMessage.from("call-2", "toolB", text(100)));
        memory.add(ToolExecutionResultMessage.from("call-3", "toolC", text(100)));

        assertThat(((ToolExecutionResultMessage) memory.messages().get(0)).text())
                .startsWith(ToolResultGovernor.EVICTED_PREFIX);
        assertThat(((ToolExecutionResultMessage) memory.messages().get(1)).text())
                .startsWith(ToolResultGovernor.EVICTED_PREFIX);
        assertThat(((ToolExecutionResultMessage) memory.messages().get(2)).text()).hasSize(100);
        assertThat(governor.evictedCount()).isEqualTo(2);
    }

    @Test
    void withoutGovernorToolResultsStayIntact() {
        RecordingSummarizer model = new RecordingSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::3", model, MAX, KEEP);

        memory.add(ToolExecutionResultMessage.from("call-1", "toolA", text(500)));

        assertThat(((ToolExecutionResultMessage) memory.messages().get(0)).text()).hasSize(500);
    }

    @Test
    void governorDoesNotBlockSemanticCompression() {
        RecordingSummarizer model = new RecordingSummarizer();
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 1000));
        SummarizingChatMemory memory = new SummarizingChatMemory("u::4", model, MAX, KEEP, governor);

        for (int i = 0; i < MAX + 1; i++) {
            memory.add(i % 2 == 0 ? UserMessage.from("提问" + i) : AiMessage.from("回答" + i));
        }

        assertThat(model.summarizeRequest).isNotNull();
        assertThat(memory.messages()).hasSize(KEEP + 1);
    }

    @Test
    void compressionPromptRequiresStructuredTaskState() {
        RecordingSummarizer model = new RecordingSummarizer();
        SummarizingChatMemory memory = new SummarizingChatMemory("u::5", model, MAX, KEEP);

        for (int i = 0; i < MAX + 1; i++) {
            memory.add(UserMessage.from("第" + i + "条"));
        }

        String prompt = ((SystemMessage) model.summarizeRequest.messages().get(0)).text();
        assertThat(prompt).contains("压缩成一段");
        assertThat(prompt).contains("【任务目标】");
        assertThat(prompt).contains("【未完成】");
        assertThat(prompt).contains("【关键事实】");
    }

    @Test
    void memoryKeepsWorkingAfterGovernance() {
        RecordingSummarizer model = new RecordingSummarizer();
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 100));
        SummarizingChatMemory memory = new SummarizingChatMemory("u::6", model, MAX, KEEP, governor);

        memory.add(UserMessage.from("查一下"));
        memory.add(ToolExecutionResultMessage.from("call-1", "toolA", text(100)));
        memory.add(AiMessage.from("已查到"));

        assertThat(memory.messages()).hasSize(3);
        assertThat(memory.messages().get(0)).isInstanceOf(UserMessage.class);
        assertThat(memory.messages().get(2)).isInstanceOf(AiMessage.class);
    }
}
