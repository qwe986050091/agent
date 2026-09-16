package com.example.agent.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;

import java.util.List;

/**
 * 带编号来源标注的内容注入器（P1-9.4）。
 *
 * 替代 langchain4j 默认拼接：文档片段按注入顺序编号 [1][2]… 并附来源文件名，
 * 同时注入引用规则 prompt，要求模型在用到资料的句子末尾标注对应编号，资料不足时明确说明而非编造。
 * 历史对话片段仅作语境参考，不编号、不要求引用。
 *
 * 编号顺序与 {@link HybridContentRetriever} 的输出顺序（也是观测 rag 事件片段顺序）一致，
 * 前端据此把回答中的 [n] 映射回召回面板第 n 个文档片段。
 */
public class NumberedCitationContentInjector implements ContentInjector {

    private static final String CITATION_RULES = """

            【知识库资料使用规则】
            1. 以下编号片段来自用户自己的知识库，请优先依据它们回答；如果资料不足以支撑结论，\
            直接说明"现有资料中没有相关内容"，不要凭常识编造。
            2. 回答中凡使用了编号片段的内容，必须在该句末尾（标点之前）标注来源编号，\
            形如 [1]；多段共同支撑时并列标注，如 [1][2]；只能使用下方真实存在的编号，禁止杜撰。
            3. 文末"参考历史对话"仅用于保持语境连贯，不要对它标注编号。
            4. 完全没用到知识库资料时，回答中不要出现编号标注。
            """;

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (contents == null || contents.isEmpty()) {
            return chatMessage;
        }
        // 注入点只会收到用户消息；其他类型（理论上不会出现）按空原文处理
        String original = (chatMessage instanceof UserMessage um && um.hasSingleText())
                ? um.singleText() : "";

        StringBuilder docs = new StringBuilder();
        StringBuilder conversations = new StringBuilder();
        int number = 0;
        for (Content content : contents) {
            TextSegment segment = content.textSegment();
            if (segment == null) {
                continue;
            }
            String type = segment.metadata() == null
                    ? null : segment.metadata().getString(RagService.META_TYPE);
            String text = segment.text() == null ? "" : segment.text();
            if (RagService.TYPE_CONVERSATION.equals(type)) {
                conversations.append("- ").append(text.strip()).append('\n');
            } else {
                number++;
                String source = segment.metadata() == null
                        ? null : segment.metadata().getString(RagService.META_SOURCE);
                docs.append('\n').append('[').append(number).append("] ")
                        .append(source == null || source.isBlank() ? "知识片段" : "《" + source + "》")
                        .append("：\n").append(text.strip()).append('\n');
            }
        }

        StringBuilder body = new StringBuilder();
        if (original != null && !original.isBlank()) {
            body.append(original);
        }
        if (number > 0) {
            body.append(CITATION_RULES);
            body.append("\n===== 知识库资料开始 =====");
            body.append(docs);
            body.append("===== 知识库资料结束 =====");
        }
        if (!conversations.isEmpty()) {
            body.append("\n===== 参考历史对话（无需标注编号） =====\n")
                    .append(conversations);
        }
        return UserMessage.from(body.toString());
    }
}
