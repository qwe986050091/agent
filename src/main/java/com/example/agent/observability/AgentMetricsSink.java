package com.example.agent.observability;

/**
 * Agent 指标出口（T1 为 no-op 实现；T3 引入 micrometer 后替换为 Micrometer 实现）。
 * 全部方法必须异常安全，实现方不得因指标问题影响对话主链路。
 */
public interface AgentMetricsSink {

    /** 一次 LLM 调用结束（同步/流式各轮） */
    void recordLlmCall(String model, boolean success, long durationMs,
                       Integer inputTokens, Integer outputTokens);

    /** 一次工具调用完成 */
    void recordToolCall(String toolName, boolean success, long durationMs);

    /** 一次 RAG 检索完成：hit=是否有命中，segmentCount=召回片段数 */
    void recordRagRetrieval(boolean hit, int segmentCount);

    /** 一次对话回合结束：mode=sync|stream，outcome=success|error|converged|cancelled|busy */
    void recordChat(String mode, String outcome, long durationMs);

    /** SSE 在途连接数变化（+1/-1） */
    void changeSseActive(int delta);
}
