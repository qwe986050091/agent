package com.example.agent.rag;

import com.example.agent.agent.MultimodalUserMessages;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NumberedCitationContentInjector} 单测（P1-9.4）：
 * 文档片段编号与来源、历史对话不编号、规则注入、空内容原样返回。
 */
class NumberedCitationContentInjectorTest {

    private final NumberedCitationContentInjector injector = new NumberedCitationContentInjector();

    private Content doc(String docId, String source, String text) {
        Metadata md = new Metadata()
                .put(RagService.META_TYPE, RagService.TYPE_DOCUMENT)
                .put(RagService.META_DOC_ID, docId)
                .put(RagService.META_SOURCE, source);
        return Content.from(TextSegment.from(text, md));
    }

    private Content conversation(String text) {
        Metadata md = new Metadata().put(RagService.META_TYPE, RagService.TYPE_CONVERSATION);
        return Content.from(TextSegment.from(text, md));
    }

    @Test
    void injectsNumberedDocsWithRulesAndUnnumberedConversation() {
        UserMessage question = UserMessage.from("退款要多久？");
        List<Content> contents = List.of(
                doc("d1", "退款政策.pdf", "退款 3 个工作日内到账"),
                conversation("用户：上次买过东西"),
                doc("d2", "FAQ.md", "特殊商品不支持七天无理由"));

        ChatMessage result = injector.inject(contents, question);

        String text = ((UserMessage) result).singleText();
        assertTrue(text.startsWith("退款要多久？"), text);
        assertTrue(text.contains("【知识库资料使用规则】"), "应注入引用规则");
        assertTrue(text.contains("[1] 《退款政策.pdf》："), "文档1应带编号与来源\n" + text);
        assertTrue(text.contains("退款 3 个工作日内到账"));
        assertTrue(text.contains("[2] 《FAQ.md》："), "文档2编号应连续（对话不占号）\n" + text);
        assertTrue(text.contains("特殊商品不支持七天无理由"));
        assertTrue(text.contains("参考历史对话"), "历史对话应有独立分区");
        assertTrue(text.contains("用户：上次买过东西"));
        assertTrue(!text.contains("[3]"), "历史对话不应编号");
    }

    @Test
    void emptyContentsReturnsSameMessage() {
        UserMessage question = UserMessage.from("你好");
        assertSame(question, injector.inject(List.of(), question));
        assertSame(question, injector.inject(null, question));
    }

    @Test
    void onlyConversationDoesNotIncludeRules() {
        ChatMessage result = injector.inject(
                List.of(conversation("旧对话内容")), UserMessage.from("在吗"));
        String text = ((UserMessage) result).singleText();
        assertTrue(text.contains("在吗"));
        assertTrue(text.contains("参考历史对话"));
        assertTrue(!text.contains("知识库资料使用规则"), "纯历史对话不需要引用规则");
        assertTrue(!text.contains("[1]"));
    }

    @Test
    void multimodalMessage_preservesImagesAndAppendsDocs() {
        // P1-8：图片回合命中 RAG 时，注入资料不能把 ImageContent 丢掉
        String tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC";
        UserMessage question = MultimodalUserMessages.from("结合知识库看看这张单据",
                List.of(new MultimodalUserMessages.ImageInput("image/png", tinyPng)));

        ChatMessage result = injector.inject(List.of(doc("d1", "单据规范.pdf", "电子单据需含编号")), question);

        UserMessage um = (UserMessage) result;
        // 图片原样保留
        List<ImageContent> images = um.contents().stream()
                .filter(c -> c instanceof ImageContent).map(c -> (ImageContent) c).toList();
        assertTrue(images.size() == 1, "图片块必须保留");
        assertTrue(images.get(0).image().base64Data().equals(tinyPng));
        // 文本块含原问题 + 注入资料（取文本拼接判断）
        String text = um.contents().stream()
                .filter(c -> c instanceof dev.langchain4j.data.message.TextContent)
                .map(c -> ((dev.langchain4j.data.message.TextContent) c).text())
                .reduce("", String::concat);
        assertTrue(text.contains("结合知识库看看这张单据"));
        assertTrue(text.contains("[1] 《单据规范.pdf》"));
    }

    @Test
    void nonUserMessageStillInjectsDocsWithoutThrowing() {
        // 防御性：理论上注入点只发生在用户消息上；非用户消息按空原文拼资料，不抛异常
        ChatMessage result = injector.inject(
                List.of(doc("d1", "a.txt", "片段")), SystemMessage.from("系统消息文本"));
        assertTrue(((UserMessage) result).singleText().contains("[1] 《a.txt》"));
    }
}
