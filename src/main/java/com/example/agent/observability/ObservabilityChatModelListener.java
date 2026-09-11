package com.example.agent.observability;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可观测 ChatModelListener：把每次 LLM 调用的 token 用量、工具往返还原到
 * 当前线程的 {@link TraceCollector}，同时打 {@link AgentMetricsSink} 指标。
 *
 * 每个模型 bean 独立一个实例，{@code modelLabel} 标识主/备与模型名
 * （响应 metadata 不带 modelName 时作为兜底标签）。
 *
 * 所有回调对异常自我兜底：采集逻辑任何意外都不得影响 LLM 调用本身（NFR-3）。
 */
public class ObservabilityChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityChatModelListener.class);

    /** attributes 共享 Map 中记录调用起始时间的键（onRequest 写、onResponse/onError 读） */
    private static final String ATTR_START_NANOS = "observability.startNanos";

    private final String modelLabel;
    private final AgentMetricsSink metrics;

    public ObservabilityChatModelListener(String modelLabel, AgentMetricsSink metrics) {
        this.modelLabel = modelLabel;
        this.metrics = metrics;
    }

    public String modelLabel() {
        return modelLabel;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        try {
            context.attributes().put(ATTR_START_NANOS, System.nanoTime());
            TraceCollector collector = TraceContextHolder.current();
            if (collector == null) {
                return;
            }
            // 上一轮工具的执行结果随本轮请求消息回来：按 id/FIFO 与 pending 请求配对
            for (ChatMessage msg : context.chatRequest().messages()) {
                if (msg instanceof ToolExecutionResultMessage ter) {
                    collector.completeTool(ter);
                }
            }
        } catch (Exception e) {
            log.warn("[Trace] onRequest 采集异常（不影响对话）: {}", e.getMessage());
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            long durationMs = elapsed(context.attributes().get(ATTR_START_NANOS));
            String responseModel = modelNameOf(context.chatResponse().metadata());

            TraceCollector collector = TraceContextHolder.current();
            TokenUsage usage = context.chatResponse().tokenUsage();
            if (collector != null) {
                collector.recordLlmResponse(usage, responseModel);
                if (context.chatResponse().aiMessage().hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : context.chatResponse().aiMessage().toolExecutionRequests()) {
                        collector.pendTool(req);
                    }
                }
            }

            if (metrics != null) {
                String tag = responseModel != null ? responseModel : modelLabel;
                metrics.recordLlmCall(tag, true, durationMs,
                        usage == null ? null : usage.inputTokenCount(),
                        usage == null ? null : usage.outputTokenCount());
            }
        } catch (Exception e) {
            log.warn("[Trace] onResponse 采集异常（不影响对话）: {}", e.getMessage());
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        try {
            long durationMs = elapsed(context.attributes().get(ATTR_START_NANOS));
            TraceCollector collector = TraceContextHolder.current();
            if (collector != null) {
                collector.recordLlmError();
            }
            if (metrics != null) {
                metrics.recordLlmCall(modelLabel, false, durationMs, null, null);
            }
        } catch (Exception e) {
            log.warn("[Trace] onError 采集异常（不影响对话）: {}", e.getMessage());
        }
    }

    private static long elapsed(Object startNanos) {
        return startNanos instanceof Long l ? (System.nanoTime() - l) / 1_000_000L : -1L;
    }

    private static String modelNameOf(ChatResponseMetadata metadata) {
        try {
            return metadata == null ? null : metadata.modelName();
        } catch (Exception e) {
            return null;
        }
    }
}
