package com.example.agent.eval;

import java.util.List;

/**
 * 一次评测运行的完整报告（落盘 JSON / Markdown 的根结构，也是上报 backend 的载荷）。
 *
 * <p>字段名在后端 {@code eval_run} 落库解析中被复用，改动需两侧同步（见 backend EvalService）。
 */
public record EvalReport(String runId,
                         String label,
                         String datasetName,
                         String mode,
                         String model,
                         String generatedAt,
                         boolean judgeEnabled,
                         EvalMetrics metrics,
                         List<EvalCaseResult> results) {

    public EvalReport {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
