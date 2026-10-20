package com.example.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测报告输出：JSON（机器读、供落库/对比）+ Markdown（CJK 友好、人读）。
 */
public final class EvalReportWriter {

    private EvalReportWriter() {
    }

    public static void writeJson(Path file, EvalReport report, ObjectMapper mapper) throws IOException {
        ensureParent(file);
        Files.writeString(file,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    public static void writeMarkdown(Path file, EvalReport report, RunDiff diff) throws IOException {
        ensureParent(file);
        Files.writeString(file, toMarkdown(report, diff));
    }

    static String toMarkdown(EvalReport report, RunDiff diff) {
        EvalMetrics m = report.metrics();
        StringBuilder sb = new StringBuilder();
        sb.append("# Agent 评测报告 · ").append(nullSafe(report.label())).append('\n').append('\n');
        sb.append("- 运行 ID：`").append(report.runId()).append("`\n");
        sb.append("- 数据集：").append(nullSafe(report.datasetName())).append('\n');
        sb.append("- 调用方式：").append(nullSafe(report.mode()))
                .append(report.model().isBlank() ? "" : "（模型 " + report.model() + "）").append('\n');
        sb.append("- 生成时间：").append(nullSafe(report.generatedAt()));
        sb.append(diff == null ? "" : "（对比基线 `" + diff.baseRunId() + "`）").append('\n').append('\n');

        sb.append("## 指标总览\n\n");
        sb.append("| 指标 | 本次 | 基线 | Δ | 样本数 |\n");
        sb.append("|---|---|---|---|---|\n");
        appendMetricRow(sb, "通过率", fmt(m.passRate()), baseline(diff, Metric.PASS_RATE), diff, "passRate", m.total());
        appendMetricRow(sb, "工具选择正确率", fmt(m.toolAccuracy()), baseline(diff, Metric.TOOL), diff, "toolAccuracy", m.toolChecked());
        appendMetricRow(sb, "引用正确率", fmt(m.citationAccuracy()), baseline(diff, Metric.CITATION), diff, "citationAccuracy", m.citationChecked());
        appendMetricRow(sb, "要点覆盖率", fmt(m.pointCoverage()), baseline(diff, Metric.POINT), diff, "pointCoverage", m.pointChecked());
        appendMetricRow(sb, "安全拒答率", fmt(m.refusalRate()), baseline(diff, Metric.REFUSAL), diff, "refusalRate", m.refusalChecked());
        sb.append('\n');
        sb.append("> 通过 ").append(m.passed()).append(" / 总计 ").append(m.total())
                .append("；平均 token ").append(Math.round(m.avgTokens()))
                .append("；平均耗时 ").append(Math.round(m.avgDurationMs())).append(" ms\n\n");

        sb.append("## 分类通过率\n\n");
        sb.append("| 类别 | 通过 | 总数 | 通过率 |\n|---|---|---|---|\n");
        Map<String, int[]> byCategory = new LinkedHashMap<>();
        for (EvalCaseResult r : report.results()) {
            int[] c = byCategory.computeIfAbsent(nullSafe(r.category()), k -> new int[2]);
            c[1]++;
            if (r.passed()) {
                c[0]++;
            }
        }
        byCategory.forEach((cat, c) -> sb.append("| ").append(cat).append(" | ").append(c[0])
                .append(" | ").append(c[1]).append(" | ").append(pct(c[0], c[1])).append(" |\n"));
        sb.append('\n');

        if (diff != null) {
            sb.append("## 与基线对比\n\n");
            sb.append("- 修复用例（失败→通过）：")
                    .append(diff.fixedCases().isEmpty() ? "无" : String.join(", ", diff.fixedCases())).append('\n');
            sb.append("- 回归用例（通过→失败）：")
                    .append(diff.brokenCases().isEmpty() ? "无" : String.join(", ", diff.brokenCases())).append('\n');
            sb.append('\n');
        }

        List<EvalCaseResult> failed = report.results().stream().filter(r -> !r.passed()).toList();
        sb.append("## 失败用例（").append(failed.size()).append("）\n\n");
        if (failed.isEmpty()) {
            sb.append("全部通过 🎉\n");
        } else {
            for (EvalCaseResult r : failed) {
                sb.append("### `").append(r.id()).append("`（").append(nullSafe(r.category())).append("）\n\n");
                sb.append("- 问题：").append(nullSafe(r.input())).append('\n');
                sb.append("- 回答：").append(r.answerExcerpt()).append('\n');
                for (EvalCheck c : r.checks()) {
                    if (!c.passed()) {
                        sb.append("- ❌ ").append(c.name())
                                .append("：期望「").append(nullSafe(c.expected())).append("」，实际「")
                                .append(nullSafe(c.actual())).append("」");
                        if (c.detail() != null && !c.detail().isBlank()) {
                            sb.append(" — ").append(c.detail());
                        }
                        sb.append('\n');
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private enum Metric { PASS_RATE, TOOL, CITATION, POINT, REFUSAL }

    /** 基线的对应指标绝对值（无基线返回「—」） */
    private static String baseline(RunDiff diff, Metric metric) {
        if (diff == null || diff.baseMetrics() == null) {
            return "—";
        }
        EvalMetrics b = diff.baseMetrics();
        return switch (metric) {
            case PASS_RATE -> fmt(b.passRate());
            case TOOL -> fmt(b.toolAccuracy());
            case CITATION -> fmt(b.citationAccuracy());
            case POINT -> fmt(b.pointCoverage());
            case REFUSAL -> fmt(b.refusalRate());
        };
    }

    private static void appendMetricRow(StringBuilder sb, String name, String current, String base,
                                        RunDiff diff, String key, int sample) {
        String delta = "—";
        if (diff != null) {
            Double d = diff.metricDeltas().get(key);
            if (d != null) {
                delta = (d >= 0 ? "+" : "") + String.format("%.1f%%", d * 100);
            }
        }
        sb.append("| ").append(name).append(" | ").append(current).append(" | ").append(base)
                .append(" | ").append(delta).append(" | ").append(sample).append(" |\n");
    }

    private static String fmt(Double ratio) {
        return ratio == null ? "—" : String.format("%.1f%%", ratio * 100);
    }

    private static String fmt(double ratio) {
        return String.format("%.1f%%", ratio * 100);
    }

    private static String pct(int passed, int total) {
        return total == 0 ? "—" : String.format("%.1f%%", 100.0 * passed / total);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static void ensureParent(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
