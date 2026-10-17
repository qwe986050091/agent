package com.example.agent.agent;

import com.example.agent.observability.RequestContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * fan-out 并行执行器：把一批子任务分发到有界线程池并发执行。
 *
 * 关键点：
 *   - 每个任务在独立工作线程通过 {@link RequestContext#call} 绑定请求上下文后执行，
 *     保证 trace 事件不丢、高危工具审批闸门可用（详见 RequestContext）；
 *   - 单任务超时/异常只影响自身（记为 error），不阻断其他子任务；
 *   - 有界线程池（core=max=agent.fanout.max-parallel），队列无界但任务数由回合预算约束；
 *   - 关闭时中断在途任务，避免应用停机悬挂。
 */
@Component
public class FanoutExecutor {

    private static final Logger log = LoggerFactory.getLogger(FanoutExecutor.class);

    /** 一个扇出子任务：目标子代理名 + 子任务指令 */
    public record FanoutTask(String agent, String task) {
    }

    /** 一个扇出子任务的结果：成功时 result 非空；失败/超时/中断时 error 非空 */
    public record FanoutOutcome(String agent, String result, String error) {
    }

    private final ThreadPoolExecutor pool;
    private final int maxParallel;
    private final long perTaskTimeoutSeconds;

    public FanoutExecutor(@Value("${agent.fanout.max-parallel:3}") int maxParallel,
                          @Value("${agent.fanout.per-task-timeout-seconds:120}") long perTaskTimeoutSeconds) {
        this.maxParallel = Math.max(1, maxParallel);
        // 默认 > 审批等待超时（agent.approval.timeout-seconds=60）：子代理等人工确认不会先被扇出超时掐断
        this.perTaskTimeoutSeconds = Math.max(1, perTaskTimeoutSeconds);
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(
                this.maxParallel, this.maxParallel, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "agent-fanout-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        log.info("[Fanout] 并行执行器就绪：max-parallel={}, per-task-timeout={}s",
                this.maxParallel, this.perTaskTimeoutSeconds);
    }

    /** 扇出是否可用（线程池未关闭） */
    public boolean available() {
        return !pool.isShutdown();
    }

    /**
     * 并行执行一批子任务。
     *
     * @param ctx   回合请求上下文快照（由回合工作线程 capture）
     * @param tasks 子任务列表
     * @param work  单个子任务的执行体（通常调子代理的 runner）
     * @return 与 tasks 等长的结果列表（保持入参顺序）
     */
    public List<FanoutOutcome> run(RequestContext ctx, List<FanoutTask> tasks,
                                  Function<FanoutTask, String> work) {
        List<Future<FanoutOutcome>> futures = new ArrayList<>(tasks.size());
        for (FanoutTask task : tasks) {
            Callable<FanoutOutcome> callable = () -> {
                try {
                    String value = ctx.call(() -> work.apply(task));
                    return new FanoutOutcome(task.agent(), value, null);
                } catch (Exception e) {
                    return new FanoutOutcome(task.agent(), null, shortMessage(e));
                }
            };
            futures.add(pool.submit(callable));
        }

        List<FanoutOutcome> outcomes = new ArrayList<>(tasks.size());
        for (int i = 0; i < futures.size(); i++) {
            FanoutTask task = tasks.get(i);
            try {
                outcomes.add(futures.get(i).get(perTaskTimeoutSeconds, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                futures.get(i).cancel(true);
                log.warn("[Fanout] 子代理「{}」执行超时（{}s），已取消", task.agent(), perTaskTimeoutSeconds);
                outcomes.add(new FanoutOutcome(task.agent(), null,
                        "执行超时（" + perTaskTimeoutSeconds + "s），已取消"));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.get(i).cancel(true);
                outcomes.add(new FanoutOutcome(task.agent(), null, "扇出被中断"));
            } catch (ExecutionException e) {
                outcomes.add(new FanoutOutcome(task.agent(), null, shortMessage(e.getCause())));
            }
        }
        return outcomes;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }

    int maxParallel() {
        return maxParallel;
    }

    private static String shortMessage(Throwable e) {
        if (e == null) {
            return "未知错误";
        }
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
