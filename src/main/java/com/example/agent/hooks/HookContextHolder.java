package com.example.agent.hooks;

/**
 * Hook 事件总线的静态访问点。
 *
 * <p>业务对象（记忆、trace、审批闸门）多为 new 出来的请求级实例，不走 Spring 装配；
 * 用静态访问点避免把 {@link HookEventBus} 一层层透传。未启动 Spring（单测）时总线为 null，
 * {@link #publish} 自动降级为空操作。
 */
public final class HookContextHolder {

    private static volatile HookEventBus bus;

    private HookContextHolder() {
    }

    public static void set(HookEventBus eventBus) {
        bus = eventBus;
    }

    public static void clear() {
        bus = null;
    }

    /** 发布事件；总线不存在/已关闭时静默丢弃 */
    public static void publish(AgentHookEvent event) {
        HookEventBus current = bus;
        if (current != null) {
            current.publish(event);
        }
    }

    public static boolean available() {
        return bus != null;
    }
}
