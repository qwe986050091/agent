package com.example.agent.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器（P1-9.3）：双路粗召（向量 + 关键词）→ RRF 融合 → Rerank 精排。
 *
 * 降级策略（任何一层缺失/故障都退化为更简单的路径，绝不阻断对话）：
 *  <ul>
 *    <li>关键词组件为 null（memory 模式）或查询失败：纯向量粗召</li>
 *    <li>Rerank 未启用/模型缺失/打分失败：RRF 融合顺序（纯向量时即相似度顺序）</li>
 *  </ul>
 *
 * 输出片段的 metadata 副本上写入观测字段：{@code _score}(向量余弦)、
 * {@code channel}(vector/keyword/both)、{@code rerankScore}(精排 logit，可缺省)。
 */
public class HybridContentRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridContentRetriever.class);

    /** RRF 常数：经典值 60，越大排名分曲线越平滑 */
    private static final int RRF_K = 60;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KeywordIndex keywordSearcher;
    private final RerankScorer rerankScorer;
    private final String userId;
    private final RagSearchProperties props;

    public HybridContentRetriever(EmbeddingModel embeddingModel,
                                  EmbeddingStore<TextSegment> embeddingStore,
                                  KeywordIndex keywordSearcher,
                                  RerankScorer rerankScorer,
                                  String userId,
                                  RagSearchProperties props) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.keywordSearcher = keywordSearcher;
        this.rerankScorer = rerankScorer;
        this.userId = userId;
        this.props = props;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String queryText = query == null ? "" : query.text();
        try {
            boolean hybridActive = props.hybrid() && keywordSearcher != null
                    && keywordSearcher.available();
            boolean rerankActive = rerankScorer != null && rerankScorer.available();
            // 有融合/精排兜底时放宽向量阈值扩大候选；纯向量模式保持严格阈值
            double vectorFloor = (hybridActive || rerankActive)
                    ? props.candidateMinScore() : props.minScore();

            // ---------- 1. 向量粗召 ----------
            Embedding queryEmbedding = embeddingModel.embed(queryText).content();
            EmbeddingSearchRequest vectorRequest = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(props.vectorTopK())
                    .minScore(vectorFloor)
                    .filter(MetadataFilterBuilder.metadataKey(RagService.META_USER_ID).isEqualTo(userId))
                    .build();
            List<EmbeddingMatch<TextSegment>> vectorMatches =
                    embeddingStore.search(vectorRequest).matches();

            Map<String, Candidate> candidates = new LinkedHashMap<>();
            List<String> vectorRank = new ArrayList<>();
            for (EmbeddingMatch<TextSegment> match : vectorMatches) {
                if (match.embedded() == null) {
                    continue;
                }
                Candidate c = new Candidate(match.embeddingId(), match.embedded(), match.score());
                c.inVector = true;
                candidates.put(c.id, c);
                vectorRank.add(c.id);
            }

            // ---------- 2. 关键词粗召（pgvector 模式） ----------
            List<String> keywordRank = List.of();
            if (hybridActive) {
                List<KeywordIndex.KeywordHit> hits =
                        keywordSearcher.search(userId, queryText, props.keywordTopK());
                keywordRank = new ArrayList<>(hits.size());
                for (KeywordIndex.KeywordHit hit : hits) {
                    keywordRank.add(hit.embeddingId());
                    Candidate existing = candidates.get(hit.embeddingId());
                    if (existing == null) {
                        Candidate c = new Candidate(hit.embeddingId(), hit.segment(), 0d);
                        c.inKeyword = true;
                        candidates.put(c.id, c);
                    } else {
                        existing.inKeyword = true;
                    }
                }
            }
            if (candidates.isEmpty()) {
                return List.of();
            }

            // ---------- 3. Rerank 精排（成功则完全决定顺序），否则 RRF 融合 ----------
            List<String> orderedIds;
            if (rerankActive) {
                List<String> passages = candidates.values().stream().map(c -> c.segment.text()).toList();
                List<Double> scores = rerankScorer.scoreAll(queryText, passages);
                if (scores != null && scores.size() == candidates.size()) {
                    int i = 0;
                    for (Candidate c : candidates.values()) {
                        c.rerankScore = scores.get(i++);
                    }
                    orderedIds = candidates.values().stream()
                            .sorted(Comparator.comparing((Candidate c) -> c.rerankScore).reversed()
                                    .thenComparing(c -> c.vectorScore, Comparator.reverseOrder()))
                            .map(c -> c.id)
                            .toList();
                } else {
                    orderedIds = rrfOrder(candidates, vectorRank, keywordRank);
                }
            } else {
                orderedIds = rrfOrder(candidates, vectorRank, keywordRank);
            }

            // ---------- 4. 组装输出（带观测 metadata 副本），截断到最终条数 ----------
            // 文档（知识库资料）优先占名额、历史对话只补空位：
            // 否则大量短对话片段会在融合后排在前面，把真正回答问题的文档挤出 maxResults。
            List<String> finalIds = composeFinalOrder(orderedIds,
                    id -> {
                        Candidate c = candidates.get(id);
                        String type = c == null || c.segment.metadata() == null
                                ? null : c.segment.metadata().getString(RagService.META_TYPE);
                        return RagService.TYPE_DOCUMENT.equals(type);
                    },
                    props.maxResults());
            List<Content> contents = new ArrayList<>();
            for (String id : finalIds) {
                Candidate c = candidates.get(id);
                contents.add(Content.from(TextSegment.from(c.segment.text(), copyMetadata(c))));
            }
            log.debug("[RAG] 用户 {} 检索「{}」：向量 {} + 关键词 {} → 输出 {}（rerank={}）",
                    userId, queryText, vectorRank.size(), keywordRank.size(),
                    contents.size(), rerankActive);
            return contents;
        } catch (Exception e) {
            log.warn("[RAG] 混合检索失败，本次不注入知识上下文: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 最终名额组装：保持融合/精排顺序，先放文档片段（最多 limit 条），
     * 剩余名额再按序补历史对话片段。无文档时全部名额给对话。
     * 包级静态便于单测固定"文档不被对话挤出"的契约。
     */
    static List<String> composeFinalOrder(List<String> orderedIds,
                                         java.util.function.Predicate<String> isDocument,
                                         int limit) {
        if (limit <= 0 || orderedIds.isEmpty()) {
            return List.of();
        }
        List<String> docs = new ArrayList<>();
        List<String> conversations = new ArrayList<>();
        for (String id : orderedIds) {
            if (isDocument.test(id)) {
                docs.add(id);
            } else {
                conversations.add(id);
            }
        }
        List<String> result = new ArrayList<>(Math.min(limit, orderedIds.size()));
        for (String id : docs) {
            if (result.size() >= limit) {
                return result;
            }
            result.add(id);
        }
        for (String id : conversations) {
            if (result.size() >= limit) {
                break;
            }
            result.add(id);
        }
        return result;
    }

    /** RRF：score = Σ 1/(k+rank)，rank 从 1 起；同分按向量分降序保证确定性 */
    private List<String> rrfOrder(Map<String, Candidate> candidates,
                                  List<String> vectorRank, List<String> keywordRank) {
        Map<String, Double> rrf = new LinkedHashMap<>();
        addRrf(rrf, vectorRank);
        addRrf(rrf, keywordRank);
        return candidates.values().stream()
                .sorted(Comparator.comparing((Candidate c) -> rrf.getOrDefault(c.id, 0d)).reversed()
                        .thenComparing(c -> c.vectorScore, Comparator.reverseOrder()))
                .map(c -> c.id)
                .toList();
    }

    private void addRrf(Map<String, Double> scores, List<String> rankList) {
        for (int i = 0; i < rankList.size(); i++) {
            scores.merge(rankList.get(i), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    /** 复制库存片段 metadata，附加本轮检索的观测字段（不改库存对象） */
    private Metadata copyMetadata(Candidate c) {
        Metadata source = c.segment.metadata();
        Metadata metadata = source == null ? new Metadata() : source.copy();
        metadata.put(RagService.META_SCORE, c.vectorScore);
        metadata.put(RagService.META_CHANNEL, c.channel());
        if (c.rerankScore != null) {
            metadata.put(RagService.META_RERANK_SCORE, c.rerankScore);
        }
        return metadata;
    }

    /** 检索候选（可变通道标记 + 精排分） */
    private static final class Candidate {
        private final String id;
        private final TextSegment segment;
        private final double vectorScore;
        private boolean inVector;
        private boolean inKeyword;
        private Double rerankScore;

        private Candidate(String id, TextSegment segment, double vectorScore) {
            this.id = id;
            this.segment = segment;
            this.vectorScore = vectorScore;
        }

        private String channel() {
            if (inVector && inKeyword) {
                return "both";
            }
            return inVector ? "vector" : "keyword";
        }
    }
}
