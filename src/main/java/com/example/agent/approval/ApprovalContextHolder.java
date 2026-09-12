package com.example.agent.approval;

/**
 * 请求级 {@link ApprovalGate} 的 ThreadLocal 持有器。
 *
 * 与 TraceContextHolder 同生命周期：同步对话在 Tomcat 线程 start/clear；
 * 流式对话在 agent-sse-stream 工作线程 start/clear（@Tool 方法在该线程内同步执行）。
 */
public final class ApprovalContextHolder {

    private static final ThreadLocal<ApprovalGate> CURRENT = new ThreadLocal<>();

    private ApprovalContextHolder() {
    }

    public static void start(ApprovalGate gate) {
        CURRENT.set(gate);
    }

    public static ApprovalGate current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
