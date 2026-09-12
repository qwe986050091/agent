package com.example.agent.approval;

/**
 * 工具审批策略：
 * - AUTO：低风险工具自动放行（计算器、时间、固定主机的搜索）
 * - CONFIRM：高危工具，执行前必须经用户在对话界面确认（或命中会话级「始终允许」）
 */
public enum ApprovalMode {
    AUTO,
    CONFIRM
}
