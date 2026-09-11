package com.example.agent.observability.model;

/**
 * 整轮对话的 token 用量 trace 事件（event: usage），在 memory/[DONE] 之前发送。
 *
 * @param model       实际服务的模型名（以 LLM 响应 metadata.modelName 为准，缺省用 listener 标签）
 * @param inputTokens 全部 LLM 轮次输入 token 求和
 * @param outputTokens 全部 LLM 轮次输出 token 求和
 * @param totalTokens 全部轮次总 token 求和
 * @param rounds      LLM 调用轮次数
 * @param fallback    是否使用了备用模型
 * @param cancelled   是否被中途取消（取消时上游可能不返回 usage，token 可能为 0）
 * @param durationMs  整轮对话耗时（agent 侧）
 */
public record UsageTraceEvent(String model,
                              int inputTokens,
                              int outputTokens,
                              int totalTokens,
                              int rounds,
                              boolean fallback,
                              boolean cancelled,
                              long durationMs) implements TraceEvent {
}
