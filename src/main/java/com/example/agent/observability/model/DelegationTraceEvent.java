package com.example.agent.observability.model;

/**
 * 子代理委派 trace 事件（event: delegation）。
 *
 * 与工具事件的区别：工具事件记录「某个 Agent 调用了什么工具」，委派事件记录
 * 「某个 Agent 把一整段子任务交给了子代理」——它是父子 Agent 调用树的节点。
 *
 * @param id         本次委派唯一标识（前端/后端用 parentId 建树）
 * @param agent      子代理标识：技能名；计划编排固定为 planner
 * @param parentId   父委派 id：null=由主 Agent 直接发起；非空=由某次委派内的计划编排发起
 * @param task       交给子代理的子任务指令（截断 300 字符）
 * @param success    子代理是否正常返回
 * @param error      失败原因（成功为 null）
 * @param durationMs 委派执行耗时（含子代理内部工具调用）
 * @param tokens     本次委派消耗的 token（子代理 LLM 用量求和；无法测量时为 0）
 */
public record DelegationTraceEvent(String id,
                                   String agent,
                                   String parentId,
                                   String task,
                                   boolean success,
                                   String error,
                                   long durationMs,
                                   int tokens) implements TraceEvent {
}
