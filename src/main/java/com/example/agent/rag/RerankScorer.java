package com.example.agent.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rerank 精排打分器（P1-9.3）。
 *
 * 进程内 ONNX Cross-Encoder（bge-reranker-base 量化版，模型文件外部挂载）。
 * 懒加载（与 embedding 同一套策略）：enabled=true 且模型/分词器文件都存在时，
 * 首次检索才加载（native 内存约 200-300MB）；加载失败永久降级关闭直到重启，不阻断对话。
 *
 * 默认休眠：需先跑 reranker-setup.sh 下载模型并向 unit 注入 RAG_RERANK_ENABLED=true。
 */
@Component
public class RerankScorer {

    private static final Logger log = LoggerFactory.getLogger(RerankScorer.class);

    private final boolean enabled;
    private final Path modelFile;
    private final Path tokenizerFile;

    private volatile OnnxScoringModel model;
    private volatile boolean unavailable = false;

    public RerankScorer(
            @Value("${rag.rerank.enabled:false}") boolean enabled,
            @Value("${rag.rerank.model-dir:/opt/agent-data/models/bge-reranker-base}") String modelDir,
            @Value("${rag.rerank.model-file:model_quantized.onnx}") String modelFileName,
            @Value("${rag.rerank.tokenizer-file:tokenizer.json}") String tokenizerFileName) {
        this.enabled = enabled;
        this.modelFile = Path.of(modelDir, modelFileName);
        this.tokenizerFile = Path.of(modelDir, tokenizerFileName);
    }

    /** Rerank 当前是否可用（触发懒加载；enabled=false 或文件缺失/加载失败均返回 false） */
    public boolean available() {
        if (!enabled || unavailable) {
            return false;
        }
        OnnxScoringModel m = model;
        if (m != null) {
            return true;
        }
        synchronized (this) {
            if (unavailable) {
                return false;
            }
            if (model == null) {
                if (!Files.isRegularFile(modelFile) || !Files.isRegularFile(tokenizerFile)) {
                    // 不打 WARN：默认部署就不带模型，避免每次检索刷日志（只在首次 debug 一次）
                    log.debug("[RAG-Rerank] enabled=true 但模型文件缺失（{} / {}），Rerank 休眠",
                            modelFile, tokenizerFile);
                    unavailable = true;
                    return false;
                }
                try {
                    model = new OnnxScoringModel(modelFile.toString(), tokenizerFile.toString());
                    log.info("[RAG-Rerank] 精排模型加载完成: {}，混合检索精排已启用", modelFile);
                } catch (Throwable t) {
                    unavailable = true;
                    log.warn("[RAG-Rerank] 精排模型加载失败，Rerank 降级关闭（不影响对话）: {}", t.toString());
                    return false;
                }
            }
            return model != null;
        }
    }

    /**
     * 对 (query, 每个片段) 成对打分。
     *
     * @return 与 passages 同序的分数（bge reranker 的 raw logit，越大越相关）；
     *         服务不可用或打分异常时返回 null，调用方回退到 RRF/向量顺序
     */
    public List<Double> scoreAll(String query, List<String> passages) {
        OnnxScoringModel m;
        if (!available() || passages == null || passages.isEmpty()) {
            return null;
        }
        m = model;
        try {
            List<TextSegment> segments = new ArrayList<>(passages.size());
            for (String p : passages) {
                segments.add(TextSegment.from(p));
            }
            Response<List<Double>> response = m.scoreAll(segments, query);
            return response.content();
        } catch (Exception e) {
            log.warn("[RAG-Rerank] 精排打分失败，本轮回退融合顺序: {}", e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (model != null) {
            try {
                model.close();
            } catch (Exception ignored) {
                // 关闭异常无需处理
            }
        }
    }
}
