package com.example.agent.observability;

import com.example.agent.observability.model.RagSegment;
import com.example.agent.rag.RagService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 可观测 RAG 检索器：装饰业务 {@link ContentRetriever}，把每次检索的查询与召回片段
 * （来源/docId/类型/文本/相似度）写入当前线程的 {@link TraceCollector}（即时发
 * SSE rag 事件 + 回合末随同步 JSON 返回），无命中时空列表（用于命中率统计）。
 *
 * 任何采集异常都不阻断对话：检索本身异常时返回空列表（RAG 优雅降级）。
 */
public class ObservabilityRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityRetriever.class);

    private final ContentRetriever delegate;

    public ObservabilityRetriever(ContentRetriever delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<Content> retrieve(Query query) {
        TraceCollector collector = TraceContextHolder.current();
        String queryText = query == null ? "" : query.text();
        try {
            List<Content> contents = delegate.retrieve(query);
            if (collector != null) {
                collector.recordRag(queryText, toSegments(contents));
            }
            return contents;
        } catch (Exception e) {
            // 检索失败不阻断对话（delegate 通常已兜底，这里再保一道）
            log.warn("[Trace] RAG 检索失败，按无召回处理: {}", e.getMessage());
            if (collector != null) {
                collector.recordRag(queryText, List.of());
            }
            return List.of();
        }
    }

    private List<RagSegment> toSegments(List<Content> contents) {
        List<RagSegment> segments = new ArrayList<>();
        for (Content content : contents) {
            try {
                TextSegment ts = content.textSegment();
                if (ts == null) {
                    continue;
                }
                Metadata md = ts.metadata();
                String docId = md == null ? null : md.getString(RagService.META_DOC_ID);
                String source = md == null ? null : md.getString(RagService.META_SOURCE);
                String type = md == null ? null : md.getString(RagService.META_TYPE);
                double score = md != null && md.containsKey(RagService.META_SCORE)
                        ? roundScore(md.getDouble(RagService.META_SCORE)) : 0d;
                segments.add(new RagSegment(
                        docId,
                        source == null ? "" : source,
                        type == null ? "" : type,
                        TraceCollector.truncate(ts.text(), TraceCollector.MAX_SEGMENT_TEXT),
                        score));
            } catch (Exception e) {
                log.debug("[Trace] 召回片段转换失败，跳过: {}", e.getMessage());
            }
        }
        return segments;
    }

    /** 相似度保留 4 位小数，避免展示/落库长尾浮点 */
    private static double roundScore(Double score) {
        if (score == null) {
            return 0d;
        }
        return Math.round(score * 10000d) / 10000d;
    }
}
