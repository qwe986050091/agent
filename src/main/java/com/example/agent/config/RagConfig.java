package com.example.agent.config;

import com.example.agent.rag.DocumentTextExtractor;
import com.example.agent.rag.EsKeywordIndex;
import com.example.agent.rag.KeywordIndex;
import com.example.agent.rag.KeywordSearcher;
import com.example.agent.rag.RagSearchProperties;
import com.example.agent.rag.RagService;
import com.example.agent.rag.RerankScorer;
import com.example.agent.rag.SnapshotMigrator;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
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
 * 向量库由 {@code rag.store} 选择（P1-9.1 起支持外置 PostgreSQL+pgvector）：
 * <ul>
 *   <li><b>memory</b>（默认，本地/单机）：InMemoryEmbeddingStore + JSON 文件快照持久化。
 *       启动从 rag.store-file 加载，写入后由 RagService 防抖异步落盘。</li>
 *   <li><b>pgvector</b>（生产推荐）：向量落 PostgreSQL，磁盘存储内存有界、多实例共享；
 *       首次启动可把旧 JSON 快照一次性迁移进 PG（{@code rag.pgvector.migrate-snapshot}）。</li>
 * </ul>
 *
 * Embedding 由 {@link RagService} 内部懒加载本地 ONNX 模型（bge-small-zh-v1.5 量化版）。
 * rag.enabled=false 时整套 RAG Bean 不装配，对话回退为纯记忆模式。
 */
@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    /** 本地内存库 + JSON 快照（默认存储模式） */
    @Bean
    @ConditionalOnProperty(prefix = "rag", name = "store", havingValue = "memory", matchIfMissing = true)
    public EmbeddingStore<TextSegment> memoryEmbeddingStore(
            @Value("${rag.store-file:data/rag-embeddings.json}") String storeFile) {
        Path path = Paths.get(storeFile).toAbsolutePath();
        if (Files.exists(path)) {
            try {
                InMemoryEmbeddingStore<TextSegment> store = InMemoryEmbeddingStore.fromFile(path);
                log.info("[RAG] 向量快照加载成功: {}（重启后索引自动恢复），存储模式=memory", path);
                return store;
            } catch (Exception e) {
                // 快照损坏不阻断启动：使用空库，下次写入会覆盖坏文件
                log.warn("[RAG] 向量快照加载失败，改用空内存库（旧文件保留）: {} - {}", path, e.getMessage());
            }
        } else {
            log.info("[RAG] 未找到向量快照文件 {}，首次启动使用空内存库，存储模式=memory", path);
        }
        return new InMemoryEmbeddingStore<>();
    }

    /** PostgreSQL + pgvector 外置存储（生产）。连接失败按 fail-fast 让服务启动即暴露问题 */
    @Bean
    @ConditionalOnProperty(prefix = "rag", name = "store", havingValue = "pgvector")
    public EmbeddingStore<TextSegment> pgVectorEmbeddingStore(
            @Value("${rag.pgvector.host:127.0.0.1}") String host,
            @Value("${rag.pgvector.port:5432}") int port,
            @Value("${rag.pgvector.database:agent_rag}") String database,
            @Value("${rag.pgvector.table:rag_embeddings}") String table,
            @Value("${rag.pgvector.user:agent_rag}") String user,
            @Value("${rag.pgvector.password:}") String password,
            @Value("${rag.pgvector.dimension:512}") int dimension,
            @Value("${rag.pgvector.migrate-snapshot:true}") boolean migrateSnapshot,
            @Value("${rag.store-file:data/rag-embeddings.json}") String storeFile) {
        if (password == null || password.isBlank()) {
            // 与 datasource/API key 同一套约定：不写明文默认值，缺配置启动即失败并给出注入指引
            throw new IllegalStateException(
                    "rag.store=pgvector 但未配置 rag.pgvector.password（环境变量 RAG_PG_PASSWORD）。"
                    + "请在 agent 的 systemd unit 注入 Environment=RAG_PG_PASSWORD=... 后重启。");
        }
        PgVectorEmbeddingStore store = PgVectorEmbeddingStore.builder()
                .host(host).port(port).database(database).user(user).password(password)
                .table(table)
                .dimension(dimension)              // bge-small-zh-v1.5 = 512 维
                .useIndex(false)                  // 数据量 <10 万片段精确扫描足够；后续再手动建 HNSW
                .createTable(true)                // 表不存在自动建
                .skipCreateVectorExtension(true)  // vector 扩展由部署脚本用超级用户预建
                // JSONB 单列存元数据：动态 metadata 键（userId/docId/chunkIndex…）
                // 列定义是 builder 的必填项（build 校验非空）；jsonb 上的 GIN 索引待数据量增长后再手工补
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COMBINED_JSONB)
                        .columnDefinitions(java.util.List.of("metadata JSONB NULL"))
                        .build())
                .build();
        log.info("[RAG] 存储模式=pgvector（{}:{}/{}，表 {}）", host, port, database, table);

        if (migrateSnapshot) {
            // 旧 InMemory JSON 快照 → PG 一次性迁移（PG 为空且快照文件存在时执行，幂等）
            new SnapshotMigrator(Paths.get(storeFile).toAbsolutePath(),
                    host, port, database, table, user, password)
                    .migrate(store);
        }
        return store;
    }

    /**
     * 关键词粗召（P1-9.3，默认实现）：仅 pgvector 模式装配。建表/迁移完成后再初始化，
     * 顺手确保 pg_trgm 扩展与 GIN 索引就绪（失败不阻断启动，自动退化为纯向量）。
     *
     * P1-E：加 {@code rag.keyword.store=pg}（缺省）才会装配；切到 es 时由下一个 Bean 顶替。
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag", name = "store", havingValue = "pgvector")
    @ConditionalOnProperty(prefix = "rag.keyword", name = "store", havingValue = "pg", matchIfMissing = true)
    public KeywordIndex keywordSearcher(
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${rag.pgvector.host:127.0.0.1}") String host,
            @Value("${rag.pgvector.port:5432}") int port,
            @Value("${rag.pgvector.database:agent_rag}") String database,
            @Value("${rag.pgvector.table:rag_embeddings}") String table,
            @Value("${rag.pgvector.user:agent_rag}") String user,
            @Value("${rag.pgvector.password:}") String password) {
        // 显式依赖 embeddingStore：确保 PgVectorEmbeddingStore 已建好表再创建索引
        KeywordSearcher searcher = new KeywordSearcher(host, port, database, table, user, password);
        searcher.ensureTrgmIndex();
        return searcher;
    }

    /**
     * 关键词粗召（P1-E 对照组）：Elasticsearch 实现，{@code rag.keyword.store=es} 时装配。
     *
     * 与 pg_trgm 实现共用切词与 id 口径，可直接切换做召回/延迟对比。
     * ES 不可达时 available=false，混合检索自动退化为纯向量（不影响对话）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "rag.keyword", name = "store", havingValue = "es")
    public KeywordIndex esKeywordIndex(
            @Value("${rag.keyword.es.url:http://127.0.0.1:9200}") String url,
            @Value("${rag.keyword.es.index:agent-rag}") String index,
            @Value("${rag.keyword.es.timeout-ms:1500}") int timeoutMs) {
        return new EsKeywordIndex(url, index, timeoutMs);
    }

    @Bean
    public RagService ragService(EmbeddingStore<TextSegment> embeddingStore,
                                 DocumentTextExtractor textExtractor,
                                 RerankScorer rerankScorer,
                                 org.springframework.beans.factory.ObjectProvider<KeywordIndex> keywordProvider,
                                 @Value("${rag.store:memory}") String storeType,
                                 @Value("${rag.store-file:data/rag-embeddings.json}") String storeFile,
                                 @Value("${rag.chunk-size:500}") int chunkSize,
                                 @Value("${rag.chunk-overlap:50}") int chunkOverlap,
                                 @Value("${rag.search.max-results:4}") int maxResults,
                                 @Value("${rag.search.min-score:0.5}") double minScore,
                                 @Value("${rag.search.candidate-min-score:0.3}") double candidateMinScore,
                                 @Value("${rag.search.vector-top-k:10}") int vectorTopK,
                                 @Value("${rag.search.keyword-top-k:10}") int keywordTopK,
                                 @Value("${rag.search.hybrid:true}") boolean hybrid) {
        // pgvector 模式下向量在 PG，无需本地快照文件（传 null，RagService 的快照逻辑自动停用）
        Path path = "pgvector".equalsIgnoreCase(storeType)
                ? null : Paths.get(storeFile).toAbsolutePath();
        RagSearchProperties searchProperties = new RagSearchProperties(
                maxResults, minScore, candidateMinScore, vectorTopK, keywordTopK, hybrid);
        return new RagService(embeddingStore, path,
                chunkSize, chunkOverlap, searchProperties, textExtractor,
                keywordProvider.getIfAvailable(), rerankScorer);
    }
}
