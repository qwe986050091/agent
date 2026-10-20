package com.example.agent.eval;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * LLM-as-judge 断言：用大模型复核「答案是否覆盖了必需要点」，弥补规则断言对
 * 「换个说法的正确答案」的误判。
 *
 * <p>默认关闭：它多一次模型调用、且引入非确定性，只在真实 LLM 评测里开启。
 * 任何异常都退化为「跳过」（返回 null），绝不影响其他断言与整轮评测。
 */
public class LlmJudgeScorer implements JudgeScorer {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeScorer.class);

    private final ChatModel chatModel;

    public LlmJudgeScorer(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public EvalCheck judge(EvalCase evalCase, EvalTurn turn) {
        // 只对「有必需要点」的用例做主观复核；安全/工具用例已有确定性断言
        if (evalCase.requiredPoints().isEmpty() || turn.answer().isBlank()) {
            return null;
        }
        try {
            String prompt = buildPrompt(evalCase, turn);
            String reply = chatModel.chat(prompt);
            boolean pass = reply != null
                    && reply.trim().toUpperCase(Locale.ROOT).startsWith("PASS");
            return new EvalCheck("judge", pass, "PASS", reply == null ? "" : reply.trim(),
                    pass ? "" : "LLM 判定未覆盖必需要点");
        } catch (Exception e) {
            log.warn("[Eval] LLM judge 调用失败（跳过该断言）: {}", e.getMessage());
            return null;
        }
    }

    private static String buildPrompt(EvalCase evalCase, EvalTurn turn) {
        return """
                你是严格的评测裁判。判断「候选答案」是否覆盖了全部必需要点，且没有明显错误。
                必需要点：%s
                用户问题：%s
                候选答案：%s

                只回答一行：第一行必须是 PASS 或 FAIL，第二行给一句话理由。
                """.formatted(evalCase.requiredPoints(), evalCase.input(), turn.answer());
    }
}
