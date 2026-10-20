package com.example.agent.eval;

/**
 * 一条断言结果（用例评分的最小可读单元）。
 *
 * @param name     断言名：tools / citations / points / refusal / judge
 * @param passed   是否通过
 * @param expected 期望（人类可读，如 "calc" 或 "退款政策.doc"）
 * @param actual   实际（人类可读）
 * @param detail   补充说明（如缺失/多余明细）
 */
public record EvalCheck(String name, boolean passed, String expected, String actual, String detail) {

    public static EvalCheck pass(String name, String expected, String actual, String detail) {
        return new EvalCheck(name, true, expected, actual, detail);
    }

    public static EvalCheck fail(String name, String expected, String actual, String detail) {
        return new EvalCheck(name, false, expected, actual, detail);
    }
}
