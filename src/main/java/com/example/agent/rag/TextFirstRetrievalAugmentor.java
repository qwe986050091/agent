package com.example.agent.rag;

import com.example.agent.context.ContextBudget;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 多模态安全的检索增强器（P1-8）。
 *
 * LC4j 自带的 {@link dev.langchain4j.rag.DefaultRetrievalAugmentor} 用
 * {@code UserMessage.singleText()} 取检索词——该方法仅当消息「只有一个 TextContent」时
 * 返回文本，携带图片的多模态消息（TextContent + ImageContent）一律返回 null，
 * 进而构造出空 query 导致检索异常。本增强器：
 * <ol>
 *   <li>从 UserMessage 中拼接全部 TextContent 作为检索词（图片不参与检索）；</li>
 *   <li>纯图片回合（无文本）跳过 RAG，不做任何注入；</li>
 *   <li>检索为空时原样返回消息；有结果时交给 {@link ContentInjector} 注入。</li>
 * </ol>
 * 不做 query 变换/多路由（平台始终单检索器：混合检索在 ContentRetriever 内部完成）。
 */
public class TextFirstRetrievalAugmentor implements RetrievalAugmentor {

    private static final Logger log = LoggerFactory.getLogger(TextFirstRetrievalAugmentor.class);

    private final ContentRetriever contentRetriever;
    private final ContentInjector contentInjector;
    /** 请求级上下文预算（P1-D；null = 不裁剪，保持既有行为） */
    private final ContextBudget budget;

    public TextFirstRetrievalAugmentor(ContentRetriever contentRetriever,
                                       ContentInjector contentInjector) {
        this(contentRetriever, contentInjector, null);
    }

    public TextFirstRetrievalAugmentor(ContentRetriever contentRetriever,
                                       ContentInjector contentInjector,
                                       ContextBudget budget) {
        this.contentRetriever = contentRetriever;
        this.contentInjector = contentInjector;
        this.budget = budget;
    }

    @Override
    public AugmentationResult augment(AugmentationRequest augmentationRequest) {
        ChatMessage chatMessage = augmentationRequest.chatMessage();

        String queryText = extractQueryText(chatMessage);
        if (queryText == null || queryText.isBlank()) {
            // 纯图片/无文本回合：不触发 RAG（图片无法被 bge 向量化，历史长记忆检索无意义）
            return AugmentationResult.builder()
                    .chatMessage(chatMessage)
                    .contents(List.of())
                    .build();
        }

        Query query = Query.from(queryText, augmentationRequest.metadata());
        List<Content> contents;
        try {
            contents = contentRetriever.retrieve(query);
        } catch (Exception e) {
            // 检索失败不阻断对话：按无召回处理（与 retriever 内部降级语义一致）
            log.warn("[RAG] 检索异常，跳过注入: {}", e.getMessage());
            return AugmentationResult.builder()
                    .chatMessage(chatMessage)
                    .contents(List.of())
                    .build();
        }
        if (contents == null || contents.isEmpty()) {
            return AugmentationResult.builder()
                    .chatMessage(chatMessage)
                    .contents(List.of())
                    .build();
        }

        // 上下文预算（P1-D）：按检索预算保留高排名片段，尾部超预算片段直接丢弃；
        // 预算极小导致全部丢弃时退化为无召回（对话继续，不注入）。
        List<Content> kept = budget == null ? contents : budget.applyRetrieval(contents);
        if (kept.isEmpty()) {
            return AugmentationResult.builder()
                    .chatMessage(chatMessage)
                    .contents(List.of())
                    .build();
        }

        // LC4j 1.17.2 的 ContentInjector 契约：inject(contents, 原始 ChatMessage)
        ChatMessage augmented = contentInjector.inject(kept, chatMessage);
        return AugmentationResult.builder()
                .chatMessage(augmented)
                .contents(kept)
                .build();
    }

    /** 拼接 UserMessage 中全部 TextContent；非 UserMessage 返回 null */
    private static String extractQueryText(ChatMessage chatMessage) {
        if (!(chatMessage instanceof UserMessage userMessage)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        userMessage.contents().forEach(c -> {
            if (c instanceof TextContent tc) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        });
        return sb.toString().trim();
    }
}
