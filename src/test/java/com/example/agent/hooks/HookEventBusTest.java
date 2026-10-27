package com.example.agent.hooks;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HookEventBus} 单测（P1 可编程扩展点）。
 *
 * 覆盖：事件分发到所有 Hook、单个 Hook 异常不影响其他 Hook、
 * 关闭开关不分发、静态访问点在未注册时安全降级。
 */
class HookEventBusTest {

    private static ThreadPoolExecutor pool() {
        return new ThreadPoolExecutor(2, 2, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32));
    }

    @Test
    void dispatchesEventToAllHooks() throws Exception {
        ThreadPoolExecutor pool = pool();
        CountDownLatch latch = new CountDownLatch(2);
        List<String> received = new CopyOnWriteArrayList<>();
        AgentHook first = new AgentHook() {
            @Override
            public void onToolExecuted(AgentHookEvent.ToolExecuted event) {
                received.add("first:" + event.toolName());
                latch.countDown();
            }
        };
        AgentHook second = new AgentHook() {
            @Override
            public void onToolExecuted(AgentHookEvent.ToolExecuted event) {
                received.add("second:" + event.toolName());
                latch.countDown();
            }
        };
        HookEventBus bus = new HookEventBus(List.of(first, second), true, 500, pool);
        try {
            bus.publish(new AgentHookEvent.ToolExecuted(
                    "t-1", "u-1", 1L, "webSearch", "{}", "ok", true, 12, null));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactlyInAnyOrder("first:webSearch", "second:webSearch");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failingHookDoesNotAffectOthers() throws Exception {
        ThreadPoolExecutor pool = pool();
        CountDownLatch latch = new CountDownLatch(1);
        AgentHook broken = new AgentHook() {
            @Override
            public void onSafetyMatch(AgentHookEvent.SafetyMatch event) {
                throw new IllegalStateException("hook 内部故障");
            }
        };
        AgentHook healthy = new AgentHook() {
            @Override
            public void onSafetyMatch(AgentHookEvent.SafetyMatch event) {
                latch.countDown();
            }
        };
        HookEventBus bus = new HookEventBus(List.of(broken, healthy), true, 500, pool);
        try {
            bus.publish(new AgentHookEvent.SafetyMatch("t-1", "input", "block", "reject"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(bus.failureCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void disabledBusDropsEvents() throws Exception {
        ThreadPoolExecutor pool = pool();
        List<String> received = new CopyOnWriteArrayList<>();
        AgentHook hook = new AgentHook() {
            @Override
            public void onTurnCompleted(AgentHookEvent.TurnCompleted event) {
                received.add(event.traceId());
            }
        };
        HookEventBus bus = new HookEventBus(List.of(hook), false, 500, pool);
        try {
            bus.publish(new AgentHookEvent.TurnCompleted(
                    "t-1", "u-1", 1L, "model", 10, 1, false, false, 100));

            pool.shutdown();
            assertThat(pool.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(received).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staticHolderIsSafeWithoutRegisteredBus() {
        HookContextHolder.clear();
        // 未注册总线时发布不应抛异常
        HookContextHolder.publish(new AgentHookEvent.ToolDenied("t-1", "u-1", "tool", "deny"));
        assertThat(HookContextHolder.available()).isFalse();
    }

    @Test
    void staticHolderForwardsToRegisteredBus() throws Exception {
        ThreadPoolExecutor pool = pool();
        CountDownLatch latch = new CountDownLatch(1);
        AgentHook hook = new AgentHook() {
            @Override
            public void onToolDenied(AgentHookEvent.ToolDenied event) {
                latch.countDown();
            }
        };
        HookEventBus bus = new HookEventBus(List.of(hook), true, 500, pool);
        try {
            HookContextHolder.set(bus);
            HookContextHolder.publish(new AgentHookEvent.ToolDenied("t-1", "u-1", "tool", "deny"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(HookContextHolder.available()).isTrue();
        } finally {
            HookContextHolder.clear();
            pool.shutdownNow();
        }
    }
}
