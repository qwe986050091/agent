package com.example.agent.observability;

import java.util.function.Supplier;

/**
 * 当前「执行中的 Agent」作用域（ThreadLocal）。
 *
 * 用途：给 trace 工具事件标注归属 —— 一次工具调用到底由主 Agent 发起，还是由某个子代理发起。
 *   - 未设置（null）= 主 Agent（顶层）；
 *   - 值为子代理的技能名 = 该工具调用发生在该子代理的推理循环内。
 *
 * 由 {@link com.example.agent.agent.SubAgentToolProvider} 在真正执行子代理时用
 * {@link #runScoped} 包裹，主 Agent 与子代理在同一条工作线程同步执行，故作用域可见；
 * {@link #runScoped} 在 finally 中恢复上一个作用域，天然支持子代理嵌套。
 *
 * 与 {@link TraceContextHolder} 一样是请求级、工作线程级状态：必须在回合结束时清理
 * （见 {@link TraceContextHolder#clear()}），否则线程池复用会串作用域。
 */
public final class AgentScope {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AgentScope() {
    }

    /** 当前作用域的 Agent 标识；null = 主 Agent */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * 在指定 Agent 作用域内执行并返回结果，结束时恢复原作用域（支持嵌套子代理）。
     * 无论正常返回还是抛异常都恢复，保证作用域不泄漏到外层。
     */
    public static <T> T runScoped(String agentId, Supplier<T> action) {
        String previous = CURRENT.get();
        CURRENT.set(agentId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 清理当前线程作用域（回合结束时由 {@link TraceContextHolder#clear()} 一并调用） */
    public static void clear() {
        CURRENT.remove();
    }
}
