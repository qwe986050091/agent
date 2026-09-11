package com.example.agent.observability.model;

/**
 * 工具调用 trace 事件（event: tool）。
 *
 * @param round      该调用发生在第几个 LLM 轮次（从 1 起）
 * @param id         LLM 返回的工具调用 id（可能为 null，此时按 FIFO 配对）
 * @param name       工具方法名（@Tool 方法）
 * @param arguments  参数 JSON（截断 500 字符）
 * @param result     结果文本预览（截断 300 字符；失败时为错误摘要）
 * @param success    工具执行是否成功（取自 ToolExecutionResultMessage.isError()）
 * @param error      失败时的错误文本（成功为 null）
 * @param durationMs 从「LLM 决定调工具」到「工具结果回到下一轮请求」的近似执行耗时
 */
public record ToolTraceEvent(int round,
                             String id,
                             String name,
                             String arguments,
                             String result,
                             boolean success,
                             String error,
                             long durationMs) implements TraceEvent {
}
