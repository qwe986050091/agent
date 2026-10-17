package com.example.agent.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立检索接口（MCP 工具 kb_search）的契约测试：
 *  - topK 归一（与 Go 侧 MaxTopK/DEFAULT_TOP_K 对齐）
 *  - 片段 metadata → 对外命中结构的字段映射（观测字段缺失时不得抛异常）
 */
class RagSearchMappingTest {

    private static RagService service() {
        RagSearchProperties props = new RagSearchProperties(4, 0.3, 0.1, 10, 10, false);
        return new RagService(new InMemoryEmbeddingStore<>(), null, 512, 64, props, null, null, null);
    }

    @Test
    void clampsTopKToSupportedRange() {
        assertEquals(RagService.DEFAULT_SEARCH_TOP_K, RagService.clampTopK(0));
        assertEquals(RagService.DEFAULT_SEARCH_TOP_K, RagService.clampTopK(-3));
        assertEquals(3, RagService.clampTopK(3));
        assertEquals(RagService.MAX_SEARCH_TOP_K, RagService.clampTopK(999));
        assertEquals(RagService.MAX_SEARCH_TOP_K, RagService.clampTopK(RagService.MAX_SEARCH_TOP_K));
    }

    @Test
    void mapsFullMetadataIntoHit() {
        Metadata metadata = new Metadata()
                .put(RagService.META_SOURCE, "运维手册.pdf")
                .put(RagService.META_DOC_ID, "doc-7")
                .put(RagService.META_CHUNK_INDEX, 3)
                .put(RagService.META_SCORE, 0.83d)
                .put(RagService.META_CHANNEL, "both")
                .put(RagService.META_RERANK_SCORE, 0.91d)
                .put(RagService.META_TYPE, RagService.TYPE_DOCUMENT);

        RagService.RagHit hit = RagService.toHit(Content.from(TextSegment.from("原文片段", metadata)));

        assertEquals("原文片段", hit.text());
        assertEquals("运维手册.pdf", hit.source());
        assertEquals("doc-7", hit.docId());
        assertEquals(3, hit.chunkIndex());
        assertEquals(0.83d, hit.score(), 1e-9);
        assertEquals("both", hit.channel());
        assertEquals(0.91d, hit.rerankScore(), 1e-9);
        assertEquals(RagService.TYPE_DOCUMENT, hit.type());
    }

    @Test
    void missingObservationFieldsBecomeNullInsteadOfThrowing() {
        // 历史对话片段没有 docId/chunkIndex；未启用 Rerank 时没有 rerankScore。
        Metadata metadata = new Metadata().put(RagService.META_TYPE, RagService.TYPE_CONVERSATION);

        RagService.RagHit hit = RagService.toHit(Content.from(TextSegment.from("对话片段", metadata)));

        assertEquals("对话片段", hit.text());
        assertEquals(RagService.TYPE_CONVERSATION, hit.type());
        assertNull(hit.source());
        assertNull(hit.docId());
        assertNull(hit.chunkIndex());
        assertNull(hit.score());
        assertNull(hit.channel());
        assertNull(hit.rerankScore());
    }

    @Test
    void toleratesUnexpectedMetadataValueTypes() {
        // 观测字段理论上都是数值，但接口不该因为类型异常而 500：一律降级为 null。
        Metadata metadata = new Metadata()
                .put(RagService.META_SCORE, "not-a-number")
                .put(RagService.META_CHUNK_INDEX, "seven");

        RagService.RagHit hit = RagService.toHit(Content.from(TextSegment.from("x", metadata)));

        assertNull(hit.score());
        assertNull(hit.chunkIndex());
        assertEquals("x", hit.text());
    }

    @Test
    void emptyMetadataIsHandled() {
        RagService.RagHit hit = RagService.toHit(Content.from(TextSegment.from("只有正文")));
        assertEquals("只有正文", hit.text());
        assertNull(hit.source());
        assertNull(hit.score());
    }

    @Test
    void blankQueryReturnsEmptyWithoutTouchingEmbeddingModel() {
        // RAG 不可用时 requireModel 会抛 IllegalStateException；
        // 空查询必须在触碰模型之前就返回空结果，避免无意义的模型加载与 503。
        List<RagService.RagHit> hits = service().search("u1", "   ", 5);
        assertNotNull(hits);
        assertTrue(hits.isEmpty());
    }

    @Test
    void maxSearchTopKMatchesGoSideLimit() {
        assertEquals(10, RagService.MAX_SEARCH_TOP_K);
        assertEquals(5, RagService.DEFAULT_SEARCH_TOP_K);
    }
}
