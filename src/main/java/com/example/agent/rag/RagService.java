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
import dev.langchain4j.rag.content.retriever.ContentRetriever;
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
    /** 片段 metadata 键：片段在源文档内的序号（从 0 起，引用定位用，P1-9.4） */
    public static final String META_CHUNK_INDEX = "chunkIndex";
    /** 片段 metadata 键：检索时的向量相似度分数（检索阶段临时写入副本，供观测采集读取，不落快照） */
    public static final String META_SCORE = "_score";
    /** 片段 metadata 键：命中通道 vector/keyword/both（混合检索，P1-9.3，临时写入副本） */
    public static final String META_CHANNEL = "_channel";
    /** 片段 metadata 键：Rerank 精排分（raw logit，P1-9.3，临时写入副本） */
    public static final String META_RERANK_SCORE = "_rerankScore";

    private final EmbeddingStore<TextSegment> embeddingStore;
    /** 向量快照文件路径（null 表示不落盘） */
    private final Path storeFile;
    private final int chunkSize;
    private final int chunkOverlap;
    /** 检索参数（粗召条数/阈值/融合开关/最终条数，P1-9.3） */
    private final RagSearchProperties searchProperties;
    /** 二进制文档（PDF/Word/Excel/…）正文抽取器（P1-9.2） */
    private final DocumentTextExtractor textExtractor;
    /** 关键词粗召（仅 pgvector 模式由 RagConfig 注入；memory 模式为 null） */
    private final KeywordSearcher keywordSearcher;
    /** Rerank 精排（组件始终在，模型默认休眠，见 {@link RerankScorer}） */
    private final RerankScorer rerankScorer;

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
                      RagSearchProperties searchProperties,
                      DocumentTextExtractor textExtractor,
                      KeywordSearcher keywordSearcher,
                      RerankScorer rerankScorer) {
        this.embeddingStore = embeddingStore;
        this.storeFile = storeFile;
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.searchProperties = searchProperties;
        this.textExtractor = textExtractor;
        this.keywordSearcher = keywordSearcher;
        this.rerankScorer = rerankScorer;
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
        int chunks = splitAndStore(model, userId, docId, sourceName, text == null ? "" : text);
        log.info("[RAG] 用户 {} 文档《{}》索引完成，共 {} 个片段", userId, sourceName, chunks);
        return chunks;
    }

    /**
     * 索引一个二进制文档（PDF/Word/Excel/PPT/HTML…，P1-9.2）：
     * Tika 抽取正文 → 切块 → 向量化 → 入库。
     *
     * @param fileName 原始文件名（带后缀，供 Tika 探测格式）
     * @param content  文件原始字节
     * @return 实际写入的片段（块）数
     * @throws DocumentTextExtractor.DocumentParseException 不受支持/加密/损坏/空文本/超限/超时
     * @throws DocumentTextExtractor.ParseBusyException      解析线程池满载
     */
    public int indexFile(String userId, String docId, String sourceName,
                         String fileName, byte[] content) {
        // 先抽取正文：格式/加密/损坏/超限类问题在向量化之前就拒绝，避免白跑模型
        String text = textExtractor.extract(fileName, content == null ? new byte[0] : content);
        EmbeddingModel model = requireModel();
        int chunks = splitAndStore(model, userId, docId, sourceName, text);
        log.info("[RAG] 用户 {} 文件《{}》解析+索引完成，共 {} 个片段", userId, sourceName, chunks);
        return chunks;
    }

    /** 正文切块、写入片段 metadata（含 chunkIndex）、向量化入库并触发快照 */
    private int splitAndStore(EmbeddingModel model, String userId, String docId,
                              String sourceName, String text) {
        List<TextSegment> segments = chunkDocument(chunkSize, chunkOverlap, userId, docId, sourceName, text);
        embedAndStore(model, segments);
        scheduleSnapshot();
        return segments.size();
    }

    /**
     * 把正文切成带 metadata 的片段（P2-12 抽出为包私有 static 便于单测）。
     *
     * chunkIndex 是片段在源文档内的序号（从 0 起连续），引用定位依赖它；
     * sourceName 为 null 时统一落成空串。调用方保证 text 非 null。
     */
    static List<TextSegment> chunkDocument(int chunkSize, int chunkOverlap, String userId,
                                           String docId, String sourceName, String text) {
        Document document = Document.from(text);
        DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
        List<TextSegment> rawSegments = splitter.split(document);

        List<TextSegment> segments = new ArrayList<>();
        for (int i = 0; i < rawSegments.size(); i++) {
            Metadata metadata = new Metadata()
                    .put(META_USER_ID, userId)
                    .put(META_TYPE, TYPE_DOCUMENT)
                    .put(META_DOC_ID, docId)
                    .put(META_CHUNK_INDEX, i)
                    .put(META_SOURCE, sourceName == null ? "" : sourceName);
            segments.add(TextSegment.from(rawSegments.get(i).text(), metadata));
        }
        return segments;
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
     * 构建某用户专属的混合检索器（向量 + 可选关键词 RRF + 可选 Rerank 精排），
     * 只召回该用户自己的文档片段与历史对话；任一层缺失自动降级为纯向量。
     */
    public ContentRetriever retrieverOf(String userId) {
        EmbeddingModel model = model();
        if (model == null) {
            return query -> List.of();
        }
        return new HybridContentRetriever(model, embeddingStore, keywordSearcher,
                rerankScorer, userId, searchProperties);
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