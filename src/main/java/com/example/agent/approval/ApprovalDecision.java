package com.example.agent.approval;

/**
 * 用户对一次审批的决策结果。
 *
 * @param approved 是否批准
 * @param remember 是否「本次会话始终允许此工具」（backend 据此写 Redis 授权集合）
 */
public record ApprovalDecision(boolean approved, boolean remember) {

    public static final ApprovalDecision DENIED = new ApprovalDecision(false, false);
}
