package com.example.agent.eval;

import java.util.List;

/**
 * 一次评测运行的聚合指标（对标 JD 的「效果评测」：把「变好了没有」变成可比较的数字）。
 *
 * <p>四个核心指标各自带「样本数」，样本为 0 时指标记 null（报告里显示为「—」），
 * 避免「没有该类用例」被误读成 0% 或 100%。
 *
 * @param toolAccuracy      工具选择正确率：期望工具被正确调用的用例占比
 * @param citationAccuracy  引用正确率：回答引用的文档命中期望文档的比例
 * @param pointCoverage     要点覆盖率：答案命中必需要点的比例
 * @param refusalRate       安全拒答率：安全用例被正确拒答的比例
 */
public record EvalMetrics(int total,
                          int passed,
                          int failed,
                          double passRate,
                          Double toolAccuracy,
                          int toolChecked,
                          Double citationAccuracy,
                          int citationChecked,
                          Double pointCoverage,
                          int pointChecked,
                          Double refusalRate,
                          int refusalChecked,
                          double avgTokens,
                          double avgDurationMs) {

    public static EvalMetrics of(List<EvalCaseResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(EvalCaseResult::passed).count();
        double passRate = total == 0 ? 0d : (double) passed / total;

        int toolChecked = 0, toolPassed = 0;
        int citationChecked = 0, citationPassed = 0;
        int pointChecked = 0, pointPassed = 0;
        int refusalChecked = 0, refusalPassed = 0;
        long tokens = 0;
        long duration = 0;

        for (EvalCaseResult r : results) {
            for (EvalCheck c : r.checks()) {
                switch (c.name()) {
                    case "tools" -> { toolChecked++; if (c.passed()) toolPassed++; }
                    case "citations" -> { citationChecked++; if (c.passed()) citationPassed++; }
                    case "points" -> { pointChecked++; if (c.passed()) pointPassed++; }
                    case "refusal" -> { refusalChecked++; if (c.passed()) refusalPassed++; }
                    default -> { /* judge 等其它断言不计入四个核心指标 */ }
                }
            }
            tokens += r.tokens();
            duration += r.durationMs();
        }

        return new EvalMetrics(
                total, passed, total - passed, passRate,
                ratio(toolPassed, toolChecked), toolChecked,
                ratio(citationPassed, citationChecked), citationChecked,
                ratio(pointPassed, pointChecked), pointChecked,
                ratio(refusalPassed, refusalChecked), refusalChecked,
                total == 0 ? 0d : (double) tokens / total,
                total == 0 ? 0d : (double) duration / total);
    }

    /** 无样本时返回 null（N/A），否则返回通过率 */
    private static Double ratio(int passed, int checked) {
        return checked == 0 ? null : (double) passed / checked;
    }
}
