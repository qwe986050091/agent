package com.example.agent.config;

import com.example.agent.rag.RagService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 装配。
 *
 * - 向量库：InMemoryEmbeddingStore（内存版，重启清空；先跑通阶段使用）
 * - Embedding：由 {@link RagService} 内部懒加载本地 ONNX 模型（bge-small-zh-v1.5 量化版），
 *   此处不直接声明 EmbeddingModel Bean，避免应用启动时就加载模型拖慢启动/占内存
 * - rag.enabled=false 时整套 RAG Bean 不装配，对话回退为纯记忆模式
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public RagService ragService(EmbeddingStore<TextSegment> embeddingStore,
                                 @Value("${rag.chunk-size:500}") int chunkSize,
                                 @Value("${rag.chunk-overlap:50}") int chunkOverlap,
                                 @Value("${rag.search.max-results:4}") int maxResults,
                                 @Value("${rag.search.min-score:0.5}") double minScore) {
        return new RagService(embeddingStore, chunkSize, chunkOverlap, maxResults, minScore);
    }
}