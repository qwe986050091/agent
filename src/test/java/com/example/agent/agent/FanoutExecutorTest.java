package com.example.agent.agent;

import com.example.agent.observability.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FanoutExecutor} 单测：
 *   - 子任务在专用工作线程（agent-fanout-*）并发执行，结果保持入参顺序
 *   - 单任务失败 / 超时只影响自身，不阻断其他子任务
 */
class FanoutExecutorTest {

    @Test
    void runsTasksOnWorkerThreadsPreservingOrder() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            RequestContext ctx = RequestContext.capture();
            List<FanoutExecutor.FanoutTask> tasks = List.of(
                    new FanoutExecutor.FanoutTask("a", "1"),
                    new FanoutExecutor.FanoutTask("b", "2"),
                    new FanoutExecutor.FanoutTask("c", "3"));

            List<FanoutExecutor.FanoutOutcome> outcomes = executor.run(ctx, tasks,
                    task -> Thread.currentThread().getName() + ":" + task.agent());

            assertThat(outcomes).extracting(FanoutExecutor.FanoutOutcome::agent)
                    .containsExactly("a", "b", "c");
            assertThat(outcomes).allSatisfy(o -> {
                assertThat(o.error()).isNull();
                assertThat(o.result()).startsWith("agent-fanout-");
            });
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void singleTaskFailureDoesNotBlockOthers() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            RequestContext ctx = RequestContext.capture();
            List<FanoutExecutor.FanoutTask> tasks = List.of(
                    new FanoutExecutor.FanoutTask("ok", ""),
                    new FanoutExecutor.FanoutTask("boom", ""));

            List<FanoutExecutor.FanoutOutcome> outcomes = executor.run(ctx, tasks, task -> {
                if ("boom".equals(task.agent())) {
                    throw new IllegalStateException("故意失败");
                }
                return "done";
            });

            assertThat(outcomes.get(0).result()).isEqualTo("done");
            assertThat(outcomes.get(0).error()).isNull();
            assertThat(outcomes.get(1).result()).isNull();
            assertThat(outcomes.get(1).error()).contains("故意失败");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void slowTaskTimesOutWithoutBlockingOthers() {
        FanoutExecutor executor = new FanoutExecutor(3, 1); // 单任务超时 1s
        try {
            RequestContext ctx = RequestContext.capture();
            List<FanoutExecutor.FanoutTask> tasks = List.of(
                    new FanoutExecutor.FanoutTask("fast", ""),
                    new FanoutExecutor.FanoutTask("slow", ""));

            List<FanoutExecutor.FanoutOutcome> outcomes = executor.run(ctx, tasks, task -> {
                if ("slow".equals(task.agent())) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted");
                    }
                }
                return "done";
            });

            assertThat(outcomes.get(0).result()).isEqualTo("done");
            assertThat(outcomes.get(1).error()).contains("超时");
        } finally {
            executor.shutdown();
        }
    }
}
