package com.example.agent.eval;

/**
 * 可选 LLM-as-judge 断言（对标「要点覆盖率」的主观通道）。
 *
 * <p>规则断言（关键词包含）对「换个说法的正确答案」会误判，judge 用 LLM 兜底；
 * 但它引入非确定性与额外成本，因此<b>默认关闭</b>——只有在真实 LLM 评测（手动/定时）里才开启。
 * CI 的离线确定性档不启用 judge。
 */
public interface JudgeScorer {

    /** 关闭态：永远不产生 judge 断言 */
    JudgeScorer DISABLED = (evalCase, turn) -> null;

    /**
     * 对一条用例做主观判定。
     *
     * @return 一条 judge 断言；返回 null 表示本次跳过（不参与成败）
     */
    EvalCheck judge(EvalCase evalCase, EvalTurn turn);
}
