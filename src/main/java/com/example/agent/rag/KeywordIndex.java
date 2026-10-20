package com.example.agent.rag;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 关键词检索抽象（P1-E）：把「关键词粗召」从具体的 PostgreSQL 实现里解耦。
 *
 * 两种实现：
 *   - {@link KeywordSearcher}：pg_trgm + ILIKE（P1-9.3，无需额外中间件）；
 *   - {@link EsKeywordIndex}：Elasticsearch wildcard 查询（P1-E，独立索引，可与 pg 做对比实验）。
 *
 * 由 {@code rag.keyword.store=pg|es} 选择；任一实现故障时上层自动退化为纯向量召回。
 */
public interface KeywordIndex {

    /** 实现名（pg_trgm / es），用于日志与对比实验标注 */
    String name();

    /** 当前是否可用（ES 需要 ping 成功；pg 始终可用） */
    boolean available();

    /** 关键词检索：只返回该用户的片段，按命中排序 */
    List<KeywordHit> search(String userId, String query, int maxResults);

    /**
     * 索引一批片段（与向量库共用同一批 id，便于 RRF 融合去重）。
     * pg_trgm 实现为空操作（数据本就在向量表里）。
     */
    void indexAll(List<String> ids, List<TextSegment> segments);

    /** 按 docId 删除（pg 实现为空操作：向量表随向量删除一起消失） */
    void removeByDocId(String docId);

    /** 一条关键词命中：embeddingId 与片段（与向量路按 id 去重融合） */
    record KeywordHit(String embeddingId, TextSegment segment) {
    }
}
