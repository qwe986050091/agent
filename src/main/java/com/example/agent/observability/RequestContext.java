package com.example.agent.observability;

import com.example.agent.approval.ApprovalContextHolder;
import com.example.agent.approval.ApprovalGate;
import org.slf4j.MDC;

import java.util.concurrent.Callable;

/**
 * 请求级上下文的可跨线程快照：采集器 + 审批闸门 + traceId。
 *
 * <b>为什么需要它：</b>{@link TraceContextHolder} / {@link ApprovalContextHolder} 都是 ThreadLocal，
 * 不会自动跨线程传递。fan-out 并行子代理在自己的工作线程上运行时，若不带上下文：
 *   - 采集器不可见 → 子代理的 tool/rag/usage 事件静默丢失；
 *   - 审批闸门不可见 → 高危工具 deny-by-default 被直接拒绝（功能损坏）。
 *
 * 用法：在回合工作线程 {@link #capture()} 抓取快照，在 fan-out 工作线程用 {@link #call} 包裹执行体，
 * 内部会绑定/解绑本线程的 ThreadLocal（含 MDC traceId），执行完自动清理，池化线程不串上下文。
 */
public final class RequestContext {

    private final TraceCollector collector;
    private final ApprovalGate gate;
    private final String traceId;

    private RequestContext(TraceCollector collector, ApprovalGate gate, String traceId) {
        this.collector = collector;
        this.gate = gate;
        this.traceId = traceId;
    }

    /** 抓取当前线程的请求上下文（无对话上下文时各字段可能为 null，行为等价于不绑定） */
    public static RequestContext capture() {
        return new RequestContext(
                TraceContextHolder.current(),
                ApprovalContextHolder.current(),
                MDC.get(TraceContextHolder.MDC_TRACE_ID));
    }

    /** 在当前（工作）线程绑定上下文后执行；无论正常/异常都在 finally 解绑 */
    public <T> T call(Callable<T> action) throws Exception {
        TraceContextHolder.bind(collector, traceId);
        // gate 可能为 null（同步通道无审批出口）：start(null) 等价于本线程无闸门，行为与单独执行一致
        ApprovalContextHolder.start(gate);
        try {
            return action.call();
        } finally {
            ApprovalContextHolder.clear();
            TraceContextHolder.unbind();
        }
    }

    public TraceCollector collector() {
        return collector;
    }
}
