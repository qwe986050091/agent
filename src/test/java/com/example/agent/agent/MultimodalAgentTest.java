package com.example.agent.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模态链路关键实证（P1-8）：
 * 1. AiServices 接口入参从 String 模板换成 UserMessage 对象后，LC4j 确实把
 *    TextContent + ImageContent 原样送进 ChatRequest（而不是把对象 toString）；
 * 2. {@link MultimodalUserMessages} 组装结构正确，纯文本路径行为不变。
 */
class MultimodalAgentTest {

    /** 1x1 PNG 的最小合法 base64（只测结构，不做真实视觉调用） */
    private static final String TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC";

    /** 捕获最后一次请求的假 ChatModel */
    static class CapturingChatModel implements ChatModel {
        ChatRequest lastRequest;

        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            this.lastRequest = chatRequest;
            return ChatResponse.builder().aiMessage(AiMessage.from("好的，我看到了图片")).build();
        }
    }

    @Test
    void aiServices_passesImageContentThroughToChatRequest() {
        CapturingChatModel model = new CapturingChatModel();
        SimpleAgent agent = AiServicesBuilderSupport.newSyncAgent(model);

        UserMessage question = MultimodalUserMessages.from("这张图里有什么？",
                List.of(new MultimodalUserMessages.ImageInput("image/png", TINY_PNG)));

        String answer = agent.chat("userA::0", question.contents());

        assertThat(answer).isEqualTo("好的，我看到了图片");
        ChatMessage lastUserMessage = lastUserMessage(model);
        assertThat(lastUserMessage.type()).isEqualTo(ChatMessageType.USER);

        UserMessage um = (UserMessage) lastUserMessage;
        // 关键断言：ImageContent 作为独立 content 块进入了发往模型的请求
        List<TextContent> texts = contentsOfType(um, TextContent.class);
        List<ImageContent> images = contentsOfType(um, ImageContent.class);
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0).text()).isEqualTo("这张图里有什么？");
        assertThat(images).hasSize(1);
        assertThat(images.get(0).image().mimeType()).isEqualTo("image/png");
        assertThat(images.get(0).image().base64Data()).isEqualTo(TINY_PNG);
    }

    @Test
    void textOnlyMessage_hasSingleTextContentAndNoImage() {
        CapturingChatModel model = new CapturingChatModel();
        SimpleAgent agent = AiServicesBuilderSupport.newSyncAgent(model);

        agent.chat("userB::0", MultimodalUserMessages.textOnly("你好").contents());

        UserMessage um = (UserMessage) lastUserMessage(model);
        assertThat(contentsOfType(um, ImageContent.class)).isEmpty();
        assertThat(um.singleText()).isEqualTo("你好");
    }

    private static ChatMessage lastUserMessage(CapturingChatModel model) {
        List<ChatMessage> msgs = model.lastRequest.messages();
        for (int i = msgs.size() - 1; i >= 0; i--) {
            if (msgs.get(i).type() == ChatMessageType.USER) {
                return msgs.get(i);
            }
        }
        throw new AssertionError("请求中没有 UserMessage");
    }

    private static <T> List<T> contentsOfType(UserMessage um, Class<T> type) {
        List<T> result = new ArrayList<>();
        um.contents().forEach(c -> {
            if (type.isInstance(c)) {
                result.add(type.cast(c));
            }
        });
        return result;
    }
}
