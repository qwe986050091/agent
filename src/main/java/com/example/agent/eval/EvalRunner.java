package com.example.agent.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 评测执行器：遍历数据集 → 调用 SUT → 断言打分 → 聚合指标 → 产出 {@link EvalReport}。
 *
 * <p>执行器不关心「怎么调用」（见 {@link AgentInvoker}）也不关心「怎么输出」（见 {@link EvalReportWriter}），
 * 是纯编排，便于用假 invoker 做离线流水线单测。
 */
public final class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AgentInvoker invoker;
    private final JudgeScorer judge;
    /** 评测专用隔离用户：知识库/记忆按该 userId 隔离，不污染真实用户 */
    private final String userId;
    /** 每条用例重复次数（>1 时全部通过才算通过，用于抵消 LLM 抖动；离线档恒为 1） */
    private final int repetitions;

    public EvalRunner(AgentInvoker invoker, JudgeScorer judge, String userId, int repetitions) {
        this.invoker = invoker;
        this.judge = judge == null ? JudgeScorer.DISABLED : judge;
        this.userId = userId == null || userId.isBlank() ? "eval-runner" : userId;
        this.repetitions = Math.max(1, repetitions);
    }

    /**
     * 跑完整个数据集。
     *
     * @param label        本次运行标签（如 "rerank-on" / "baseline"），用于两次运行对比
     * @param judgeEnabled 报告里标记 judge 是否开启（仅展示用）
     */
    public EvalReport run(EvalDataset dataset, String label, boolean judgeEnabled) {
        long start = System.nanoTime();
        List<EvalCaseResult> results = new ArrayList<>();
        for (EvalCase evalCase : dataset.cases()) {
            results.add(runCase(evalCase));
        }
        EvalMetrics metrics = EvalMetrics.of(results);
        EvalReport report = new EvalReport(
                newRunId(), label, dataset.name(), invoker.describe(), invoker.modelName(),
                LocalDateTime.now().toString(), judgeEnabled, metrics, results);
        log.info("[Eval] 运行 {} 完成：{}/{} 通过（passRate={}），耗时 {} ms",
                report.runId(), metrics.passed(), metrics.total(),
                String.format("%.1f%%", metrics.passRate() * 100),
                (System.nanoTime() - start) / 1_000_000L);
        return report;
    }

    /** 单用例：重复 N 次，全部通过才算通过；展示用第一次失败（或第一次）的观测 */
    private EvalCaseResult runCase(EvalCase evalCase) {
        EvalCaseResult first = null;
        boolean allPassed = true;
        long tokens = 0;
        long duration = 0;
        for (int i = 0; i < repetitions; i++) {
            long t0 = System.nanoTime();
            EvalTurn turn;
            try {
                turn = invoker.invoke(evalCase, userId);
            } catch (Exception e) {
                turn = EvalTurn.failure(null, e.getMessage(), elapsedMs(t0));
            }
            EvalCaseResult result = EvalScorer.score(evalCase, turn, judge);
            tokens += result.tokens();
            duration += result.durationMs();
            if (first == null) {
                first = result;
            }
            if (!result.passed() && allPassed) {
                first = result; // 记录首次失败用于报告
            }
            allPassed &= result.passed();
        }
        EvalCaseResult base = first;
        return new EvalCaseResult(base.id(), base.category(), base.input(), allPassed, base.checks(),
                base.answer(), base.actualTools(), base.citedDocs(), base.retrievedDocs(),
                (int) (tokens / repetitions), duration / repetitions);
    }

    private static String newRunId() {
        String suffix = Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
        return LocalDateTime.now().format(RUN_ID_FORMAT) + "-" + suffix;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
