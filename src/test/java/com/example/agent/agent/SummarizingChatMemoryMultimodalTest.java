package com.example.agent.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模态记忆测试（P1-8）：
 * - 历史 user 消息带图时，ImageContent 被保留在重建的请求级记忆里；
 * - 纯图片消息（content 空）不能被空白过滤丢掉；
 * - 触发语义压缩时，base64 绝不能进入摘要模型的 prompt。
 */
class SummarizingChatMemoryMultimodalTest {

    private static final String TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC";

    /** 记录摘要调用请求的假模型；对话回合直接返回固定答复 */
    static class RecordingModel implements ChatModel {
        ChatRequest summarizeRequest;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            // 摘要请求的首条是 system（SUMMARIZE_PROMPT）；普通对话无 system
            boolean isSummary = !chatRequest.messages().isEmpty()
                    && chatRequest.messages().get(0) instanceof SystemMessage sm
                    && sm.text().contains("压缩成一段");
            if (isSummary) {
                summarizeRequest = chatRequest;
                return ChatResponse.builder().aiMessage(AiMessage.from("摘要：用户发过一张图。")).build();
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("收到")).build();
        }
    }

    @Test
    void restore_keepsImageContentForImageHistory() {
        SummarizingChatMemory memory = SummarizingChatMemory.restore(
                "u::1", new RecordingModel(), 40, 20, null,
                List.of(new SummarizingChatMemory.HistoryMessage("user", "看看这张图",
                        List.of(new MultimodalUserMessages.ImageInput("image/png", TINY_PNG)))));

        ChatMessage only = memory.messages().get(0);
        assertThat(only.type()).isEqualTo(ChatMessageType.USER);
        UserMessage um = (UserMessage) only;
        assertThat(um.contents()).anyMatch(c -> c instanceof ImageContent);
    }

    @Test
    void restore_imageOnlyBlankTextMessageIsNotDropped() {
        SummarizingChatMemory memory = SummarizingChatMemory.restore(
                "u::2", new RecordingModel(), 40, 20, null,
                List.of(new SummarizingChatMemory.HistoryMessage("user", "  ",
                        List.of(new MultimodalUserMessages.ImageInput("image/jpeg", TINY_PNG)))));

        assertThat(memory.messages()).hasSize(1);
        assertThat(memory.messages().get(0).type()).isEqualTo(ChatMessageType.USER);
    }

    @Test
    void compress_neverSendsBase64ToSummarizer() {
        RecordingModel model = new RecordingModel();
        // 窗口极小：max=4 keep=2，加入第 5 条消息即触发压缩
        SummarizingChatMemory memory = new SummarizingChatMemory("u::3", model, 4, 2);

        memory.add(UserMessage.from("第一条"));
        memory.add(AiMessage.from("好的"));
        memory.add(MultimodalUserMessages.from("第二条带图",
                List.of(new MultimodalUserMessages.ImageInput("image/png", TINY_PNG))));
        memory.add(AiMessage.from("已分析"));
        memory.add(UserMessage.from("第五条，触发压缩"));

        assertThat(model.summarizeRequest).isNotNull();
        String promptText = model.summarizeRequest.messages().stream()
                .map(ChatMessage::toString)
                .reduce("", String::concat);
        assertThat(promptText).doesNotContain(TINY_PNG);
        // 图片以文字标记形式出现在摘要材料中，模型仍知道曾有图片
        assertThat(promptText).contains("图片");
    }
}
