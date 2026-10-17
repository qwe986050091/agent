package com.example.agent.observability;

import com.example.agent.observability.model.TraceEvent;
import org.slf4j.MDC;

import java.util.function.Consumer;

/**
 * 工作线程级 trace 上下文持有者（ThreadLocal）。
 *
 * 同步对话在 Tomcat 线程、流式对话在 agent-sse-stream-N 线程（含 fallback 重跑）
 * 执行整条 AiServices 链，{@link dev.langchain4j.model.chat.listener.ChatModelListener}
 * 与 RAG 检索器经此获取当前回合的 {@link TraceCollector}。
 *
 * <b>必须在 finally 中 {@link #clear()}：</b>线程池线程会被复用，
 * 不清理会导致下一个请求串 traceId/MDC。
 */
public final class TraceContextHolder {

    /** 与日志 pattern %X{traceId} 对应 */
    public static final String MDC_TRACE_ID = "traceId";

    private static final ThreadLocal<TraceCollector> CURRENT = new ThreadLocal<>();

    private TraceContextHolder() {
    }

    /** 在当前工作线程开启一个回合的采集（同时写 MDC） */
    public static TraceCollector start(String traceId, String userId, Long conversationId,
                                       Consumer<TraceEvent> liveSink, AgentMetricsSink metrics) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        TraceCollector collector = TraceCollector.start(traceId, userId, conversationId, liveSink, metrics);
        CURRENT.set(collector);
        return collector;
    }

    /** 当前回合采集器；非对话线程（启动、RAG 索引接口等）为 null */
    public static TraceCollector current() {
        return CURRENT.get();
    }

    /**
     * 把「已有」采集器绑定到当前线程（不创建新采集器）。
     *
     * 供 fan-out 工作线程使用：并发子代理与回合工作线程共享同一个请求级采集器，
     * 只是需要在本线程可见（ThreadLocal 不会自动跨线程传递），否则并发子代理的
     * trace 事件会静默丢失。
     */
    public static void bind(TraceCollector collector, String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_TRACE_ID, traceId);
        }
        CURRENT.set(collector);
    }

    /** 解除当前线程绑定（fan-out 工作线程 finally 调用；只清本线程，不影响回合级采集器） */
    public static void unbind() {
        CURRENT.remove();
        MDC.remove(MDC_TRACE_ID);
        AgentScope.clear();
    }

    /** 回合结束清理 ThreadLocal + MDC（务必 finally 调用）；一并清理 Agent 作用域，防线程池复用串作用域 */
    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_TRACE_ID);
        // Agent 作用域由 AgentScope.runScoped 在 finally 中恢复，此处兜底再清一次
        AgentScope.clear();
    }
}
