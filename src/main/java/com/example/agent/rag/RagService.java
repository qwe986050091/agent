package com.example.agent.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG 检索增强服务。
 *
 * 职责：
 *  - 文档索引：文本切块 → 本地 embedding 向量化 → 写入内存向量库
 *  - 对话索引：每条对话作为一个片段向量化（跨会话长期记忆）
 *  - 检索：按 userId 过滤的语义检索，返回注入 LLM 上下文的 ContentRetriever
 *
 * 特点：
 *  - Embedding 使用进程内 ONNX 模型（bge-small-zh-v1.5 量化版），零外部 API
 *  - 模型懒加载：首次索引/检索时才初始化，不拖慢启动
 *  - 向量库快照落盘：每次写入后防抖异步序列化到 JSON 文件，启动时自动加载，重启不丢索引
 *  - 优雅降级：模型加载或向量化失败时 RAG 自动关闭，不影响正常聊天
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 片段 metadata 键：用户隔离 */
    public static final String META_USER_ID = "userId";
    /** 片段 metadata 键：来源类型 document / conversation */
    public static final String META_TYPE = "type";
    public static final String TYPE_DOCUMENT = "document";
    public static final String TYPE_CONVERSATION = "conversation";
    /** 片段 metadata 键：来源名 / 文档ID */
    public static final String META_SOURCE = "source";
    public static final String META_DOC_ID = "docId";
    /** 片段 metadata 键：检索时的向量相似度分数（检索阶段临时写入副本，供观测采集读取，不落快照） */
    public static final String META_SCORE = "_score";

    private final EmbeddingStore<TextSegment> embeddingStore;
    /** 向量快照文件路径（null 表示不落盘） */
    private final Path storeFile;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int maxResults;
    private final double minScore;

    /** ONNX 模型懒加载句柄 */
    private volatile EmbeddingModel embeddingModel;
    /** 模型加载失败后置 true，RAG 永久降级为关闭（直到重启） */
    private volatile boolean unavailable = false;

    /** 快照落盘单线程（守护线程，不阻塞 JVM 退出） */
    private final ExecutorService snapshotExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-snapshot-writer");
        t.setDaemon(true);
        return t;
    });
    private final Object snapshotLock = new Object();
    /** 是否已有一次落盘任务在排队（防抖：合并连续写入） */
    private boolean snapshotScheduled = false;

    public RagService(EmbeddingStore<TextSegment> embeddingStore,
                      Path storeFile,
                      int chunkSize, int chunkOverlap,
                      int maxResults, double minScore) {
        this.embeddingStore = embeddingStore;
        this.storeFile = storeFile;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    /** RAG 当前是否可用（触发懒加载） */
    public boolean available() {
        return model() != null;
    }

    /**
     * 索引一篇文档：切块 → 向量化 → 入库。
     *
     * @return 实际写入的片段（块）数
     */
    public int indexDocument(String userId, String docId, String sourceName, String text) {
        EmbeddingModel model = requireModel();
        Document document = Document.from(text == null ? "" : text);
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> rawSegments = splitter.split(document);

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < rawSegments.size(); i++) {
            Metadata metadata = new Metadata()
                    .put(META_USER_ID, userId)
                    .put(META_TYPE, TYPE_DOCUMENT)
                    .put(META_DOC_ID, docId)
                    .put(META_SOURCE, sourceName == null ? "" : sourceName);
            segments.add(TextSegment.from(rawSegments.get(i).text(), metadata));
        }
        embedAndStore(model, segments);
        log.info("[RAG] 用户 {} 文档《{}》索引完成，共 {} 个片段", userId, sourceName, segments.size());
        scheduleSnapshot();
        return segments.size();
    }

    /**
     * 删除某文档的全部向量片段（删除知识库文档时调用）。
     * docId 为上传时生成的全局唯一 ID，按 metadata 精确匹配。
     */
    public void removeDocument(String docId) {
        if (docId == null || docId.isBlank()) {
            return;
        }
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(META_DOC_ID).isEqualTo(docId));
        log.info("[RAG] 文档 {} 的向量片段已删除", docId);
        scheduleSnapshot();
    }

    /**
     * 索引一条对话消息（长期记忆）。短文本不切块，直接作为一个片段。
     */
    public void indexConversation(String userId, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        EmbeddingModel model = requireModel();
        String speaker = "assistant".equalsIgnoreCase(role) ? "助手" : "用户";
        String text = speaker + "：" + content.trim();

        Metadata metadata = new Metadata()
                .put(META_USER_ID, userId)
                .put(META_TYPE, TYPE_CONVERSATION)
                .put(META_SOURCE, "历史对话");
        List<TextSegment> segments = List.of(TextSegment.from(text, metadata));
        embedAndStore(model, segments);
        scheduleSnapshot();
    }

    /**
     * 构建某用户专属的检索器：只召回该用户自己的文档片段与历史对话。
     */
    public ContentRetriever retrieverOf(String userId) {
        return query -> {
            EmbeddingModel model = model();
            if (model == null) {
                return List.of();
            }
            try {
                Embedding queryEmbedding = model.embed(query.text()).content();
                EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(maxResults)
                        .minScore(minScore)
                        .filter(MetadataFilterBuilder.metadataKey(META_USER_ID).isEqualTo(userId))
                        .build();
                EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
                List<Content> contents = new ArrayList<>();
                for (EmbeddingMatch<TextSegment> match : result.matches()) {
                    TextSegment embedded = match.embedded();
                    if (embedded != null) {
                        // 副本 metadata 写入相似度分数（不改库存片段，不影响快照），供观测采集读取
                        Metadata metadata = embedded.metadata() == null
                                ? new Metadata() : embedded.metadata().copy();
                        metadata.put(META_SCORE, match.score());
                        contents.add(Content.from(TextSegment.from(embedded.text(), metadata)));
                    }
                }
                log.debug("[RAG] 用户 {} 检索「{}」召回 {} 个片段", userId, query.text(), contents.size());
                return contents;
            } catch (Exception e) {
                // 检索失败不阻断对话：跳过上下文注入
                log.warn("[RAG] 检索失败，本次不注入知识上下文: {}", e.getMessage());
                return List.of();
            }
        };
    }

    // ==================== 内部方法 ====================

    /** 懒加载本地 embedding 模型；失败返回 null 并标记降级 */
    private EmbeddingModel model() {
        if (unavailable) {
            return null;
        }
        EmbeddingModel m = embeddingModel;
        if (m != null) {
            return m;
        }
        synchronized (this) {
            if (unavailable) {
                return null;
            }
            if (embeddingModel == null) {
                try {
                    embeddingModel = new BgeSmallZhV15QuantizedEmbeddingModel();
                    log.info("[RAG] 本地 Embedding 模型（bge-small-zh-v1.5 量化版）加载完成，RAG 已启用");
                } catch (Throwable t) {
                    unavailable = true;
                    log.warn("[RAG] 本地 Embedding 模型加载失败，RAG 降级为关闭（不影响正常聊天）: {}",
                            t.toString());
                    return null;
                }
            }
            return embeddingModel;
        }
    }

    private EmbeddingModel requireModel() {
        EmbeddingModel model = model();
        if (model == null) {
            throw new IllegalStateException("RAG 当前不可用（embedding 模型未成功加载）");
        }
        return model;
    }

    /** 批量向量化并写入向量库 */
    private void embedAndStore(EmbeddingModel model, List<TextSegment> segments) {
        if (segments.isEmpty()) {
            return;
        }
        try {
            Response<List<Embedding>> response = model.embedAll(segments);
            List<Embedding> embeddings = response.content();
            embeddingStore.addAll(embeddings, segments);
        } catch (Exception e) {
            log.warn("[RAG] 向量化/入库失败: {}", e.getMessage());
            throw e;
        }
    }

    // ==================== 向量快照持久化 ====================

    /**
     * 写入向量库后防抖异步落盘：1.5s 内的多次写入合并为一次快照，
     * 避免连续对话时每条消息都全量序列化。
     */
    private void scheduleSnapshot() {
        if (storeFile == null) {
            return;
        }
        synchronized (snapshotLock) {
            if (snapshotScheduled) {
                return;
            }
            snapshotScheduled = true;
        }
        snapshotExecutor.submit(() -> {
            try {
                Thread.sleep(1500L);
                writeSnapshot();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("[RAG] 向量快照落盘失败: {}", e.getMessage());
            } finally {
                synchronized (snapshotLock) {
                    snapshotScheduled = false;
                }
            }
        });
    }

    /**
     * 全量序列化向量库到 JSON 文件：先写 .tmp 再原子替换，
     * 避免写到一半进程退出导致快照损坏。
     */
    private void writeSnapshot() {
        if (!(embeddingStore instanceof InMemoryEmbeddingStore<?>) || storeFile == null) {
            return;
        }
        try {
            Path parent = storeFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            ((InMemoryEmbeddingStore<TextSegment>) embeddingStore).serializeToFile(tmp);
            try {
                Files.move(tmp, storeFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 个别文件系统不支持原子移动，退化为普通替换
                Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("[RAG] 向量快照已落盘: {}", storeFile);
        } catch (Exception e) {
            log.warn("[RAG] 向量快照落盘失败: {}", e.getMessage());
        }
    }

    /** 停机前同步刷一次盘，尽量不丢最后未落盘的写入 */
    @PreDestroy
    public void shutdown() {
        writeSnapshot();
        snapshotExecutor.shutdown();
    }
}