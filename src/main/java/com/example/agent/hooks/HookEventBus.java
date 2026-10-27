package com.example.agent.hooks;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hook 事件总线（P1 可编程扩展点）。
 *
 * <p>分发原则：
 * <ul>
 *   <li>异步有界：固定线程池 + 有界队列，队列满直接丢弃并计数，绝不反压主流程；</li>
 *   <li>故障隔离：单个 Hook 抛异常/超时只记日志，不影响对话；</li>
 *   <li>可整体关闭：{@code agent.hooks.enabled=false} 时 publish 直接返回。</li>
 * </ul>
 */
@Component
public class HookEventBus {

    private static final Logger log = LoggerFactory.getLogger(HookEventBus.class);

    private final List<AgentHook> hooks;
    private final boolean enabled;
    private final long timeoutMs;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong slow = new AtomicLong();

    @Autowired
    public HookEventBus(List<AgentHook> hooks,
                        @Value("${agent.hooks.enabled:true}") boolean enabled,
                        @Value("${agent.hooks.max-parallel:2}") int maxParallel,
                        @Value("${agent.hooks.timeout-ms:500}") long timeoutMs) {
        this(hooks, enabled, timeoutMs, newThreadPool(Math.max(1, maxParallel)));
    }

    /** 测试/内部装配用：显式指定执行器（可用直接执行器获得同步语义） */
    HookEventBus(List<AgentHook> hooks, boolean enabled, long timeoutMs, ThreadPoolExecutor executor) {
        this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
        this.enabled = enabled;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.ownedExecutor = executor;
        this.executor = executor;
    }

    private static ThreadPoolExecutor newThreadPool(int maxParallel) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                maxParallel, maxParallel, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "agent-hook");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PostConstruct
    void register() {
        HookContextHolder.set(this);
        if (enabled) {
            log.info("[Hooks] 事件总线就绪：hooks={}, timeoutMs={}", hooks.size(), timeoutMs);
        } else {
            log.info("[Hooks] 事件总线已关闭（agent.hooks.enabled=false）");
        }
    }

    @PreDestroy
    void shutdown() {
        HookContextHolder.clear();
        ownedExecutor.shutdownNow();
    }

    public boolean enabled() {
        return enabled;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failureCount() {
        return failures.get();
    }

    public long slowCount() {
        return slow.get();
    }

    /** 异步分发一条事件给所有已注册 Hook；任何异常不外抛 */
    public void publish(AgentHookEvent event) {
        if (!enabled || event == null || hooks.isEmpty()) {
            return;
        }
        for (AgentHook hook : hooks) {
            try {
                executor.execute(() -> invoke(hook, event));
            } catch (RejectedExecutionException e) {
                dropped.incrementAndGet();
                log.debug("[Hooks] 队列已满，丢弃事件：hook={}, event={}",
                        hook.name(), event.getClass().getSimpleName());
            }
        }
    }

    private void invoke(AgentHook hook, AgentHookEvent event) {
        long startNanos = System.nanoTime();
        try {
            dispatch(hook, event);
        } catch (Exception e) {
            failures.incrementAndGet();
            log.warn("[Hooks] Hook 执行失败（不影响对话）：hook={}, event={}, err={}",
                    hook.name(), event.getClass().getSimpleName(), e.getMessage());
            return;
        }
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (elapsedMs > timeoutMs) {
            slow.incrementAndGet();
            log.warn("[Hooks] Hook 耗时超过阈值（{}ms > {}ms）：hook={}, event={}",
                    elapsedMs, timeoutMs, hook.name(), event.getClass().getSimpleName());
        }
    }

    private static void dispatch(AgentHook hook, AgentHookEvent event) {
        if (event instanceof AgentHookEvent.ToolExecuted e) {
            hook.onToolExecuted(e);
        } else if (event instanceof AgentHookEvent.ToolDenied e) {
            hook.onToolDenied(e);
        } else if (event instanceof AgentHookEvent.SafetyMatch e) {
            hook.onSafetyMatch(e);
        } else if (event instanceof AgentHookEvent.Compressed e) {
            hook.onCompressed(e);
        } else if (event instanceof AgentHookEvent.TurnCompleted e) {
            hook.onTurnCompleted(e);
        }
    }
}
