package com.example.agent.observability;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 当前「执行中的 Agent」作用域（ThreadLocal）。
 *
 * 用途：
 *   - 给工具 / RAG 事件标注归属：一次调用到底由主 Agent 发起，还是由某个子代理发起；
 *   - 给委派事件提供父节点 id（父子 Agent 调用树）与本次委派的 token 计数。
 *
 *   - 未设置（null）= 主 Agent（顶层）；
 *   - 值为子代理作用域 = 该调用发生在该子代理的推理循环内。
 *
 * 由 {@link com.example.agent.agent.SubAgentToolProvider} / 计划编排器在真正执行子代理时
 * 用 {@link #runScopedDetailed} 包裹，主 Agent 与子代理在同一条工作线程同步执行，故作用域可见；
 * 结束时在 finally 中恢复上一个作用域，天然支持嵌套。
 *
 * 与 {@link TraceContextHolder} 一样是请求级、工作线程级状态：必须在回合结束时清理
 * （见 {@link TraceContextHolder#clear()}），否则线程池复用会串作用域。
 */
public final class AgentScope {

    /**
     * 一次 Agent 执行作用域。
     *
     * @param agentId      子代理技能名（工具/RAG 归属标识）
     * @param delegationId 本次委派的唯一 id（委派事件节点 id）
     * @param startNanos   开始时间
     * @param tokens       本次委派期间子代理 LLM 的 token 累计
     */
    public record Scope(String agentId, String delegationId, long startNanos, AtomicLong tokens) {
    }

    /** 作用域执行结果：返回值 + 耗时 + token 用量（供调用方写委派事件） */
    public record Scoped<T>(T value, long durationMs, int tokens) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private AgentScope() {
    }

    /** 当前作用域的 Agent 标识；null = 主 Agent */
    public static String current() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.agentId();
    }

    /** 当前作用域的委派 id；null = 主 Agent（顶层） */
    public static String currentDelegationId() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.delegationId();
    }

    /**
     * 在指定 Agent 作用域内执行并返回结果（简化版，不关心 token/耗时）。
     */
    public static <T> T runScoped(String agentId, Supplier<T> action) {
        return runScopedDetailed(agentId, null, action).value();
    }

    /**
     * 在指定 Agent 作用域内执行，并收集耗时与 token 用量。
     * 无论正常返回还是抛异常都恢复原作用域，保证作用域不泄漏到外层。
     */
    public static <T> Scoped<T> runScopedDetailed(String agentId, String delegationId, Supplier<T> action) {
        Scope previous = CURRENT.get();
        AtomicLong tokens = new AtomicLong();
        long start = System.nanoTime();
        CURRENT.set(new Scope(agentId, delegationId, start, tokens));
        try {
            T value = action.get();
            return new Scoped<>(value, elapsedMs(start), (int) Math.min(Integer.MAX_VALUE, tokens.get()));
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 给当前作用域累加 token（子代理每次 LLM 调用后由 SubAgentFactory 调用）。
     * 主 Agent 作用域不存在，调用无副作用。
     */
    public static void addTokens(int tokens) {
        if (tokens <= 0) {
            return;
        }
        Scope scope = CURRENT.get();
        if (scope != null) {
            scope.tokens().addAndGet(tokens);
        }
    }

    /** 清理当前线程作用域（回合结束时由 {@link TraceContextHolder#clear()} 一并调用） */
    public static void clear() {
        CURRENT.remove();
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
