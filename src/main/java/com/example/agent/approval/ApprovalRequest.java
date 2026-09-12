package com.example.agent.approval;

/**
 * 一次高危工具审批请求（经 SSE {@code event: approval-request} 下发给前端）。
 *
 * @param approvalId     审批条目 id（UUID），决策回传时据此配对
 * @param traceId        全链路 traceId
 * @param userId         发起对话的用户（归属校验，防止他用户决策）
 * @param conversationId 会话 id（backend 持久化「始终允许」授权时定位会话）
 * @param tool           工具名（LangChain4j 工具名；MCP 工具为其原始工具名）
 * @param riskLevel      风险级别 low/medium/high（前端色标）
 * @param title          中文动作标题（如「发起 HTTP 请求」）
 * @param argsPreview    参数预览（≤500 字符）
 * @param createdAt      创建时间戳（epoch millis，TTL 清理用）
 */
public record ApprovalRequest(String approvalId,
                              String traceId,
                              String userId,
                              Long conversationId,
                              String tool,
                              String riskLevel,
                              String title,
                              String argsPreview,
                              long createdAt) {
}
