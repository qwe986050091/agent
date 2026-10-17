package com.example.agent.observability;

import com.example.agent.approval.ApprovalContextHolder;
import com.example.agent.approval.ApprovalGate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * fan-out 并发安全单测：
 *   1. RequestContext 把采集器 + 审批闸门绑定到工作线程，执行完解绑（池化线程不串上下文）
 *   2. 多线程并发写同一个 TraceCollector 不丢失事件（synchronized 串行化生效）
 *
 * 纯单测：不启 Spring、不连 LLM。
 */
class RequestContextTest {

    @Test
    void bindsCollectorAndGateOnWorkerThreadThenUnbinds() throws Exception {
        TraceCollector collector = TraceContextHolder.start("t-bind", "u", 0L, null, null);
        ApprovalGate gate = mock(ApprovalGate.class);
        ApprovalContextHolder.start(gate);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            RequestContext ctx = RequestContext.capture();
            AtomicReference<TraceCollector> seenCollector = new AtomicReference<>();
            AtomicReference<ApprovalGate> seenGate = new AtomicReference<>();

            // 同一工作线程内：先验证绑定可见，再验证执行完已解绑
            TraceCollector residual = worker.submit(() -> {
                ctx.call(() -> {
                    seenCollector.set(TraceContextHolder.current());
                    seenGate.set(ApprovalContextHolder.current());
                    return null;
                });
                return TraceContextHolder.current();
            }).get();

            assertThat(seenCollector.get()).isSameAs(collector);
            assertThat(seenGate.get()).isSameAs(gate);
            assertThat(residual).isNull();
        } finally {
            worker.shutdownNow();
            ApprovalContextHolder.clear();
            TraceContextHolder.clear();
        }
    }

    @Test
    void concurrentToolRecordingIsLossless() throws Exception {
        TraceCollector collector = TraceContextHolder.start("t-conc", "u", 0L, null, null);
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int tid = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        String id = "t" + tid + "-" + i;
                        collector.pendTool(ToolExecutionRequest.builder()
                                .id(id).name("tool").arguments("{}").build());
                        collector.completeTool(ToolExecutionResultMessage.from(id, "tool", "ok"));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }

            // 并发写不丢事件：每个 id 恰好配成一条（配对逻辑在临界区内）
            assertThat(collector.tools()).hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
            TraceContextHolder.clear();
        }
    }
}
