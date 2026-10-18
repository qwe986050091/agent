package com.example.agent.observability;

import com.example.agent.approval.ApprovalContextHolder;
import com.example.agent.approval.ApprovalGate;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * {@link TraceAwareStreamingChatModel} 请求级上下文绑定单测。
 *
 * 复现线上缺陷：流式的工具执行发生在模型 HTTP 回调线程，该线程的 ThreadLocal
 * 采集器/审批闸门均为 null，导致委派/扇出工具与子代理取不到上下文
 * （子代理 LLM 用量与 tool 事件丢失、回合委派预算空转）。
 *
 * 本装饰器在每轮 onCompleteResponse（内联工具执行点）前后绑定/清理 ThreadLocal，
 * 使该线程上执行的工具与同步子代理可见请求级上下文；执行完必须解绑（池化线程不串上下文）。
 *
 * 纯单测：不启 Spring、不连 LLM。
 */
class TraceAwareStreamingChatModelTest {

    @Test
    void bindsCollectorAndGateDuringInlineToolExecutionThenUnbinds() throws Exception {
        // 采集器只在回合工作线程创建，未绑定到回调线程（复现线上场景）
        TraceCollector collector = TraceCollector.start("t-stream", "u-1", 0L, null, null);
        ApprovalGate gate = mock(ApprovalGate.class);

        StreamingChatModel delegate = mock(StreamingChatModel.class);
        // 模拟真实流式模型：在回调线程上把完整响应用包装后的 handler 回抛（内联工具即在此刻执行）
        doAnswer(invocation -> {
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("hi")).build());
            return null;
        }).when(delegate).doChat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        TraceAwareStreamingChatModel model = new TraceAwareStreamingChatModel(delegate, collector, gate);
        ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();

        AtomicReference<TraceCollector> seenCollector = new AtomicReference<>();
        AtomicReference<ApprovalGate> seenGate = new AtomicReference<>();
        AtomicReference<TraceCollector> residualCollector = new AtomicReference<>();
        AtomicReference<ApprovalGate> residualGate = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                model.doChat(request, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        // 内联工具执行点：请求级上下文必须在本（回调）线程可见
                        seenCollector.set(TraceContextHolder.current());
                        seenGate.set(ApprovalContextHolder.current());
                    }

                    @Override
                    public void onError(Throwable error) {
                    }
                });
                // 执行完已解绑：池化线程不残留上下文
                residualCollector.set(TraceContextHolder.current());
                residualGate.set(ApprovalContextHolder.current());
                return null;
            }).get();
        } finally {
            worker.shutdownNow();
        }

        // 回调线程上可见：委派/扇出工具与子代理据此采集 trace
        assertThat(seenCollector.get()).isSameAs(collector);
        assertThat(seenGate.get()).isSameAs(gate);
        // 执行完不残留（池化线程复用不串上下文）
        assertThat(residualCollector.get()).isNull();
        assertThat(residualGate.get()).isNull();
    }
}
