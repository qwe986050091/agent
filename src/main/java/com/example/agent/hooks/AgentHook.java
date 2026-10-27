package com.example.agent.hooks;

/**
 * Agent 生命周期钩子（P1 可编程扩展点）。
 *
 * <p>实现方式：在 Spring 容器里注册一个 {@code @Component} 实现本接口即可，默认方法
 * 全部为空实现，按需覆写；{@link HookEventBus} 负责异步分发，单个 Hook 抛异常或超时
 * 不影响对话。
 *
 * <p>v1 定位是「旁路审计/联动」，不参与决策（不能阻断工具调用）；需要阻断请走
 * {@code AgentHookEvent.ToolDenied} 的订阅做事后告警，或在 {@code ApprovalGate} 里加参数级规则。
 */
public interface AgentHook {

    /** Hook 名称（默认类名，匿名类回退到全限定名），用于日志与排障 */
    default String name() {
        String simple = getClass().getSimpleName();
        return simple.isEmpty() ? getClass().getName() : simple;
    }

    default void onToolExecuted(AgentHookEvent.ToolExecuted event) {
    }

    default void onToolDenied(AgentHookEvent.ToolDenied event) {
    }

    default void onSafetyMatch(AgentHookEvent.SafetyMatch event) {
    }

    default void onCompressed(AgentHookEvent.Compressed event) {
    }

    default void onTurnCompleted(AgentHookEvent.TurnCompleted event) {
    }
}
