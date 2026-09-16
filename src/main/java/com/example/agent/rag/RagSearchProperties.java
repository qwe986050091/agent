package com.example.agent.rag;

/**
 * RAG 检索参数（对应 application.yml 的 rag.search.*，P1-9.3 扩展为混合检索参数集）。
 *
 * @param maxResults         最终注入 LLM 上下文的片段数（精排/融合之后）
 * @param minScore           纯向量模式（无混合/精排）的余弦相似度下限
 * @param candidateMinScore  混合/精排模式下向量粗召的下限（放宽以提高候选覆盖，再交 RRF/Rerank 精选）
 * @param vectorTopK         向量粗召条数
 * @param keywordTopK        关键词粗召条数（仅 pgvector 模式且 hybrid=true 生效）
 * @param hybrid             是否启用"向量 + 关键词"双路召回（关键词组件缺失时自动退化单路）
 */
public record RagSearchProperties(int maxResults,
                                  double minScore,
                                  double candidateMinScore,
                                  int vectorTopK,
                                  int keywordTopK,
                                  boolean hybrid) {
}
