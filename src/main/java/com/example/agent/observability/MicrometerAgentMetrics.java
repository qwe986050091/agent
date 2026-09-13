package com.example.agent.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Micrometer 的 agent 指标实现（/actuator/prometheus 暴露）。
 *
 * 指标清单：
 * <ul>
 *   <li>agent_chat_duration_seconds{mode,outcome}：对话回合耗时（count=QPS）</li>
 *   <li>agent_llm_duration_seconds{model,outcome}：单次 LLM 调用耗时（count=调用量/错误率）</li>
 *   <li>agent_llm_tokens_total{model,kind=input|output}：token 计数</li>
 *   <li>agent_tool_duration_seconds{name,outcome}：工具耗时（count=调用量/成功率）</li>
 *   <li>agent_tool_calls_total{name,outcome}：工具调用次数</li>
 *   <li>agent_rag_recall_total{outcome=hit|miss}：RAG 检索次数（命中率）</li>
 *   <li>agent_rag_segments_total：召回片段总数</li>
 *   <li>agent_sse_active：在途 SSE 连接数</li>
 * </ul>
 *
 * 指标记录异常不得影响对话（接口契约 NFR-3）。
 */
@Component
public class MicrometerAgentMetrics implements AgentMetricsSink {

    private static final Logger log = LoggerFactory.getLogger(MicrometerAgentMetrics.class);

    private final MeterRegistry registry;
    private final AtomicInteger sseActive = new AtomicInteger(0);

    public MicrometerAgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("agent.sse.active", sseActive);
    }

    @Override
    public void recordLlmCall(String model, boolean success, long durationMs,
                              Integer inputTokens, Integer outputTokens) {
        safe(() -> {
            String outcome = success ? "success" : "error";
            if (durationMs >= 0) {
                Timer.builder("agent.llm.duration")
                        .tags("model", safeTag(model), "outcome", outcome)
                        .publishPercentileHistogram(true)
                        .register(registry)
                        .record(durationMs, TimeUnit.MILLISECONDS);
            }
            if (inputTokens != null && inputTokens > 0) {
                registry.counter("agent.llm.tokens",
                        "model", safeTag(model), "kind", "input").increment(inputTokens);
            }
            if (outputTokens != null && outputTokens > 0) {
                registry.counter("agent.llm.tokens",
                        "model", safeTag(model), "kind", "output").increment(outputTokens);
            }
        });
    }

    @Override
    public void recordToolCall(String toolName, boolean success, long durationMs) {
        safe(() -> {
            String outcome = success ? "success" : "error";
            if (durationMs >= 0) {
                Timer.builder("agent.tool.duration")
                        .tags("name", safeTag(toolName), "outcome", outcome)
                        .publishPercentileHistogram(true)
                        .register(registry)
                        .record(durationMs, TimeUnit.MILLISECONDS);
            }
            registry.counter("agent.tool.calls",
                    "name", safeTag(toolName), "outcome", outcome).increment();
        });
    }

    @Override
    public void recordRagRetrieval(boolean hit, int segmentCount) {
        safe(() -> {
            registry.counter("agent.rag.recall", "outcome", hit ? "hit" : "miss").increment();
            if (segmentCount > 0) {
                registry.counter("agent.rag.segments").increment(segmentCount);
            }
        });
    }

    @Override
    public void recordChat(String mode, String outcome, long durationMs) {
        safe(() -> {
            if (durationMs < 0) {
                return;
            }
            Timer.builder("agent.chat.duration")
                    .tags("mode", safeTag(mode), "outcome", safeTag(outcome))
                    .publishPercentileHistogram(true)
                    .register(registry)
                    .record(durationMs, TimeUnit.MILLISECONDS);
        });
    }

    @Override
    public void changeSseActive(int delta) {
        safe(() -> sseActive.addAndGet(delta));
    }

    /** 指标埋点兜底：任何异常只记 debug，绝不抛到对话链路 */
    private static void safe(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("[Metrics] 指标记录异常（不影响对话）: {}", e.getMessage());
        }
    }

    /** 标签值兜底：null/空 → unknown，去掉换行避免污染指标文本 */
    private static String safeTag(String v) {
        if (v == null || v.isBlank()) {
            return "unknown";
        }
        return v.replaceAll("[\\r\\n]", "_");
    }
}
