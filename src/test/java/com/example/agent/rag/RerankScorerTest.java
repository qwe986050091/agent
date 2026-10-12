package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link RerankScorer} 降级行为单测（P2-12）。
 *
 * 精排是「可选增强」：任何一步不成立都必须静默降级回 RRF/向量顺序，绝不阻断对话。
 * 本测试只覆盖不加载真实 ONNX 模型的路径（enabled=false、模型文件缺失），
 * 真实模型的加载与打分依赖外部挂载文件，不在单测范围。
 */
class RerankScorerTest {

    /** 一定不存在的模型目录（用随机名避免与真实部署路径撞上） */
    private static final String MISSING_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "no-such-rerank-models-" + UUID.randomUUID()).toString();

    @Test
    void disabledScorerIsUnavailableAndReturnsNull() {
        RerankScorer scorer = new RerankScorer(false, MISSING_DIR, "model_quantized.onnx", "tokenizer.json");

        assertThat(scorer.available()).isFalse();
        assertThat(scorer.scoreAll("查询", List.of("片段一", "片段二"))).isNull();
    }

    @Test
    void missingModelFilesLatchUnavailableWithoutThrowing() {
        RerankScorer scorer = new RerankScorer(true, MISSING_DIR, "model_quantized.onnx", "tokenizer.json");

        // 首次：探测到文件缺失 → 置 unavailable
        assertThat(scorer.available()).isFalse();
        // 二次：闩锁生效，不重复探测、不抛异常
        assertThatCode(scorer::available).doesNotThrowAnyException();
        assertThat(scorer.available()).isFalse();

        assertThat(scorer.scoreAll("查询", List.of("片段一"))).isNull();
    }

    @Test
    void emptyOrNullPassagesYieldNull() {
        RerankScorer scorer = new RerankScorer(true, MISSING_DIR, "model_quantized.onnx", "tokenizer.json");

        assertThat(scorer.scoreAll("查询", null)).isNull();
        assertThat(scorer.scoreAll("查询", List.of())).isNull();
    }

    @Test
    void shutdownIsSafeWhenModelNeverLoaded() {
        RerankScorer scorer = new RerankScorer(false, MISSING_DIR, "model_quantized.onnx", "tokenizer.json");

        assertThatCode(scorer::shutdown).doesNotThrowAnyException();
    }
}