package com.example.agent.hooks;

/**
 * Agent 回合内的可订阅事件（P1 可编程扩展点）。
 *
 * <p>与 trace 事件的区别：trace 事件是「发给 backend/前端的协议」，新增事件类型要动
 * backend 与前端；hook 事件只在 agent 进程内分发，第三方实现 {@link AgentHook} 即可订阅，
 * 用于审计、脱敏、外部系统联动，不需要改业务代码，也不改变对外协议。
 */
public sealed interface AgentHookEvent
        permits AgentHookEvent.ToolExecuted, AgentHookEvent.ToolDenied,
        AgentHookEvent.SafetyMatch, AgentHookEvent.Compressed, AgentHookEvent.TurnCompleted {

    /** 一次工具调用完成（成功或失败） */
    record ToolExecuted(String traceId,
                        String userId,
                        Long conversationId,
                        String toolName,
                        String arguments,
                        String resultPreview,
                        boolean success,
                        long durationMs,
                        String agentId) implements AgentHookEvent {
    }

    /** 一次工具调用被审批/安全策略拒绝 */
    record ToolDenied(String traceId,
                      String userId,
                      String toolName,
                      String reason) implements AgentHookEvent {
    }

    /** 一次内容安全检测命中（与 trace 的 safety 事件同源） */
    record SafetyMatch(String traceId,
                       String channel,
                       String verdict,
                       String action) implements AgentHookEvent {
    }

    /** 记忆完成一次语义压缩（压缩前后消息数与摘要长度） */
    record Compressed(String memoryId,
                      int beforeMessages,
                      int afterMessages,
                      int summaryChars) implements AgentHookEvent {
    }

    /** 一个回合结束（含取消/失败路径的用量快照） */
    record TurnCompleted(String traceId,
                         String userId,
                         Long conversationId,
                         String model,
                         int totalTokens,
                         int rounds,
                         boolean fallback,
                         boolean cancelled,
                         long durationMs) implements AgentHookEvent {
    }
}
