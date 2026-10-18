package com.example.agent.observability;

import com.example.agent.approval.ApprovalContextHolder;
import com.example.agent.approval.ApprovalGate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Set;

/**
 * 请求级流式模型装饰器（P2-10）：解决「流式回调运行在 HTTP 客户端线程、
 * ThreadLocal 采集器不可见」的问题——通过显式持有 {@link TraceCollector}，
 * 在每一轮模型调用上采集工具往返与 token 用量，不依赖线程上下文。
 *
 * - doChat/chat 入参消息尾部若为 ToolExecutionResultMessage → 配对完成上一轮工具；
 * - 每轮完整响应：toolExecutionRequests 入 pending、TokenUsage 累加、轮次+1；
 * - 每轮 onCompleteResponse（内联工具执行点）前后绑定/清理 ThreadLocal（采集器 + 审批闸门），
 *   使该线程上执行的委派/扇出工具、以及同步运行的子代理能取到 trace 与审批上下文；
 * - 回调原样转发给业务 handler（token/最终答案/错误不受影响）。
 *
 * 指标（LLM 耗时/成败）仍由模型 bean 上的 {@link ObservabilityChatModelListener} 负责；
 * 本装饰器只做 trace 采集，且全程异常兜底。
 */
public class TraceAwareStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final TraceCollector collector;
    /**
     * 请求级审批闸门。流式工具实际在 HTTP 客户端回调线程执行（见类注释），
     * 故在每轮 onCompleteResponse（内联工具执行点）前后绑定/清理 ThreadLocal，
     * 使 @Tool 方法与 MCP 工具包装器能取到审批上下文（SSE 出口/会话授权）。
     */
    private final ApprovalGate approvalGate;

    public TraceAwareStreamingChatModel(StreamingChatModel delegate, TraceCollector collector,
                                        ApprovalGate approvalGate) {
        this.delegate = delegate;
        this.collector = collector;
        this.approvalGate = approvalGate;
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        observeRequest(chatRequest);
        delegate.doChat(chatRequest, wrap(handler));
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        observeRequest(chatRequest);
        delegate.chat(chatRequest, wrap(handler));
    }

    @Override
    public void chat(ChatRequest chatRequest, dev.langchain4j.model.chat.ChatRequestOptions options,
                    StreamingChatResponseHandler handler) {
        observeRequest(chatRequest);
        delegate.chat(chatRequest, options, wrap(handler));
    }

    // ---- 能力/元数据一律透传，保证工具支持探测等行为与原模型一致 ----

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public dev.langchain4j.model.ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    // ==================== 采集逻辑 ====================

    private void observeRequest(ChatRequest chatRequest) {
        if (collector == null || chatRequest == null) {
            return;
        }
        try {
            for (ChatMessage msg : chatRequest.messages()) {
                if (msg instanceof ToolExecutionResultMessage ter) {
                    collector.completeTool(ter);
                }
            }
        } catch (Exception e) {
            // 采集异常绝不影响模型调用
        }
    }

    private StreamingChatResponseHandler wrap(StreamingChatResponseHandler original) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                original.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                try {
                    if (collector != null && response != null && response.aiMessage() != null) {
                        AiMessage ai = response.aiMessage();
                        if (ai.hasToolExecutionRequests()) {
                            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                                collector.pendTool(req);
                            }
                        }
                        collector.recordLlmResponse(response.tokenUsage(),
                                response.metadata() == null ? null : response.metadata().modelName());
                    }
                } catch (Exception ignored) {
                    // 采集异常不影响答案回传
                }
                // 工具在本回调线程内联执行：绑定请求级 trace 上下文 + 审批上下文，执行完立即恢复/清理。
                // 二者都是 ThreadLocal（不会跨线程传递），若不在此处绑定：
                //   - 委派/扇出工具取不到采集器 → 子代理 LLM 用量与 tool 事件丢失、回合委派预算空转；
                //   - 子代理在本线程同步推理，其内部工具/模型回调同样看不到采集器；
                //   - 高危工具取不到审批闸门 → deny-by-default 直接拒绝。
                // （工具线程可能阻塞等待用户审批，期间占用模型 HTTP 回调线程，属预期内）
                TraceCollector previousCollector = collector == null ? null : TraceContextHolder.current();
                ApprovalGate previousGate = ApprovalContextHolder.current();
                if (collector != null) {
                    TraceContextHolder.bind(collector, collector.traceId());
                }
                ApprovalContextHolder.start(approvalGate);
                try {
                    original.onCompleteResponse(response);
                } finally {
                    if (collector != null) {
                        if (previousCollector != null) {
                            TraceContextHolder.bind(previousCollector, previousCollector.traceId());
                        } else {
                            TraceContextHolder.unbind();
                        }
                    }
                    if (previousGate != null) {
                        ApprovalContextHolder.start(previousGate);
                    } else {
                        ApprovalContextHolder.clear();
                    }
                }
            }

            @Override
            public void onError(Throwable error) {
                original.onError(error);
            }
        };
    }
}
