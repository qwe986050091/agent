package com.example.agent.config;

import com.example.agent.rag.RagService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RAG 装配。
 *
 * - 向量库：InMemoryEmbeddingStore（内存）+ JSON 文件快照持久化
 *   启动时从 rag.store-file 加载历史快照，每次写入后由 RagService 防抖异步落盘，
 *   服务重启后文档/对话向量自动恢复，不再需要重新上传文档
 * - Embedding：由 {@link RagService} 内部懒加载本地 ONNX 模型（bge-small-zh-v1.5 量化版），
 *   此处不直接声明 EmbeddingModel Bean，避免应用启动时就加载模型拖慢启动/占内存
 * - rag.enabled=false 时整套 RAG Bean 不装配，对话回退为纯记忆模式
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(
            @Value("${rag.store-file:data/rag-embeddings.json}") String storeFile) {
        Path path = Paths.get(storeFile).toAbsolutePath();
        if (Files.exists(path)) {
            try {
                InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(path);
                log.info("[RAG] 向量快照加载成功: {}（重启后索引自动恢复）", path);
                return store;
            } catch (Exception e) {
                // 快照损坏不阻断启动：使用空库，下次写入会覆盖坏文件
                log.warn("[RAG] 向量快照加载失败，改用空内存库（旧文件保留）: {} - {}", path, e.getMessage());
            }
        } else {
            log.info("[RAG] 未找到向量快照文件 {}，首次启动使用空内存库", path);
        }
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public RagService ragService(EmbeddingStore<TextSegment> embeddingStore,
                                 @Value("${rag.store-file:data/rag-embeddings.json}") String storeFile,
                                 @Value("${rag.chunk-size:500}") int chunkSize,
                                 @Value("${rag.chunk-overlap:50}") int chunkOverlap,
                                 @Value("${rag.search.max-results:4}") int maxResults,
                                 @Value("${rag.search.min-score:0.5}") double minScore) {
        return new RagService(embeddingStore, Paths.get(storeFile).toAbsolutePath(),
                chunkSize, chunkOverlap, maxResults, minScore);
    }
}
