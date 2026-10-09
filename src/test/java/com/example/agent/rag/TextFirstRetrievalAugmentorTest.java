package com.example.agent.rag;

import com.example.agent.agent.MultimodalUserMessages;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link TextFirstRetrievalAugmentor} 单测（P1-8）：
 * - 纯图片回合跳过检索（singleText()=null 是默认增强器的踩坑点）；
 * - 带文本的多模态回合用文本检索，且注入后图片保留；
 * - 检索异常不阻断对话。
 */
class TextFirstRetrievalAugmentorTest {

    private static final String TINY_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC";

    private final ContentRetriever retriever = mock(ContentRetriever.class);
    private final TextFirstRetrievalAugmentor augmentor =
            new TextFirstRetrievalAugmentor(retriever, new NumberedCitationContentInjector());

    private static AugmentationRequest requestOf(ChatMessage message) {
        // Metadata 必填非 null（LC4j 1.17.2）；测试只关心 query 文本，内存 id/历史传 null
        return new AugmentationRequest(message,
                dev.langchain4j.rag.query.Metadata.from(message, null, null));
    }

    @Test
    void imageOnlyMessage_skipsRetrievalAndKeepsMessageUntouched() {
        UserMessage imageOnly = MultimodalUserMessages.from("",
                List.of(new MultimodalUserMessages.ImageInput("image/png", TINY_PNG)));

        var result = augmentor.augment(requestOf(imageOnly));

        verifyNoInteractions(retriever);
        assertThat(result.contents()).isEmpty();
        assertThat(result.chatMessage()).isSameAs(imageOnly);
    }

    @Test
    void multimodalText_retrievesByTextAndInjectorKeepsImages() {
        UserMessage question = MultimodalUserMessages.from("报销单上的金额是多少",
                List.of(new MultimodalUserMessages.ImageInput("image/png", TINY_PNG)));
        dev.langchain4j.rag.query.Metadata metadata =
                dev.langchain4j.rag.query.Metadata.from(question, null, null);

        List<Content> recalled = new ArrayList<>();
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(Query.class))).thenAnswer(inv -> {
            Query q = inv.getArgument(0);
            // query 只用文本块拼接（图片不参与 embedding 检索），metadata 原样透传
            assertThat(q.metadata()).isSameAs(metadata);
            assertThat(q.text()).isEqualTo("报销单上的金额是多少");
            recalled.add(Content.from(TextSegment.from("报销上限 500 元")));
            return recalled;
        });

        var result = augmentor.augment(new AugmentationRequest(question, metadata));

        assertThat(result.contents()).hasSize(1);
        UserMessage augmented = (UserMessage) result.chatMessage();
        assertThat(augmented.contents()).anyMatch(c -> c instanceof ImageContent);
        String injectedText = augmented.contents().stream()
                .filter(c -> c instanceof TextContent).map(c -> ((TextContent) c).text())
                .reduce("", String::concat);
        assertThat(injectedText).contains("报销单上的金额是多少").contains("报销上限 500 元");
    }

    @Test
    void retrieverFailure_doesNotBreakConversation() {
        when(retriever.retrieve(org.mockito.ArgumentMatchers.any(Query.class)))
                .thenThrow(new RuntimeException("embedding model down"));

        var result = augmentor.augment(requestOf(UserMessage.from("你好")));

        assertThat(result.contents()).isEmpty();
        assertThat(((UserMessage) result.chatMessage()).singleText()).isEqualTo("你好");
    }
}
