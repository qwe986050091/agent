package com.example.agent.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 两次评测运行的对比（「重构前后 / rerank 开与关」的分数变化）。
 *
 * <p>这是「能证明自己的 AI 系统变好了」的载体：给出固定用例（本次由失败转通过）与
 * 回归用例（本次由通过转失败），以及四个核心指标的 delta。
 */
public record RunDiff(String baseRunId,
                      String currentRunId,
                      String baseLabel,
                      String currentLabel,
                      EvalMetrics baseMetrics,
                      EvalMetrics currentMetrics,
                      Map<String, Double> metricDeltas,
                      List<String> fixedCases,
                      List<String> brokenCases) {

    public RunDiff {
        // metricDeltas 允许 null 值（某指标无样本时 delta 为 null），故不能用 Map.copyOf（null 值会 NPE）
        metricDeltas = metricDeltas == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metricDeltas));
        fixedCases = fixedCases == null ? List.of() : List.copyOf(fixedCases);
        brokenCases = brokenCases == null ? List.of() : List.copyOf(brokenCases);
    }

    /** 以 base 为基线、current 为本次，计算差异（按用例 id 关联） */
    public static RunDiff compare(EvalReport base, EvalReport current) {
        Map<String, Boolean> basePass = passMap(base);
        Map<String, Boolean> currentPass = passMap(current);

        Set<String> ids = new LinkedHashSet<>(basePass.keySet());
        ids.addAll(currentPass.keySet());
        List<String> fixed = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        for (String id : ids) {
            boolean before = Boolean.TRUE.equals(basePass.get(id));
            boolean after = Boolean.TRUE.equals(currentPass.get(id));
            if (!before && after) {
                fixed.add(id);
            } else if (before && !after) {
                broken.add(id);
            }
        }

        Map<String, Double> deltas = new LinkedHashMap<>();
        putDelta(deltas, "passRate", base.metrics().passRate(), current.metrics().passRate());
        putDelta(deltas, "toolAccuracy", base.metrics().toolAccuracy(), current.metrics().toolAccuracy());
        putDelta(deltas, "citationAccuracy", base.metrics().citationAccuracy(), current.metrics().citationAccuracy());
        putDelta(deltas, "pointCoverage", base.metrics().pointCoverage(), current.metrics().pointCoverage());
        putDelta(deltas, "refusalRate", base.metrics().refusalRate(), current.metrics().refusalRate());

        return new RunDiff(base.runId(), current.runId(), base.label(), current.label(),
                base.metrics(), current.metrics(), deltas, fixed, broken);
    }

    /** 是否存在回归（有用例由通过转失败）——可作为 CI 的失败信号 */
    public boolean hasRegression() {
        return !brokenCases.isEmpty();
    }

    private static Map<String, Boolean> passMap(EvalReport report) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (EvalCaseResult r : report.results()) {
            map.put(r.id(), r.passed());
        }
        return map;
    }

    private static void putDelta(Map<String, Double> target, String key, Double base, Double current) {
        if (base == null || current == null) {
            target.put(key, null);
            return;
        }
        target.put(key, current - base);
    }
}
