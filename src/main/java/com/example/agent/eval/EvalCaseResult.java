package com.example.agent.eval;

import java.util.List;

/**
 * 单条用例的评测结果：整体成败 + 逐条断言明细 + 观测摘要（工具/引用/用量）。
 */
public record EvalCaseResult(String id,
                             String category,
                             String input,
                             boolean passed,
                             List<EvalCheck> checks,
                             String answer,
                             List<String> actualTools,
                             List<String> citedDocs,
                             List<String> retrievedDocs,
                             int tokens,
                             long durationMs) {

    public EvalCaseResult {
        checks = checks == null ? List.of() : List.copyOf(checks);
        actualTools = actualTools == null ? List.of() : List.copyOf(actualTools);
        citedDocs = citedDocs == null ? List.of() : List.copyOf(citedDocs);
        retrievedDocs = retrievedDocs == null ? List.of() : List.copyOf(retrievedDocs);
    }

    /** 失败的断言名（写报告与落库用） */
    public List<String> failedChecks() {
        return checks.stream().filter(c -> !c.passed()).map(EvalCheck::name).toList();
    }

    /** 答案摘要（报告/落库截断用） */
    public String answerExcerpt() {
        if (answer == null) {
            return "";
        }
        return answer.length() <= 300 ? answer : answer.substring(0, 300) + "…";
    }
}
