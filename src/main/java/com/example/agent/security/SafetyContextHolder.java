package com.example.agent.security;

/**
 * 请求级安全警戒语持有者（P4-17 §6.2，ThreadLocal）。
 *
 * 灰区输入在 warn/block 模式下不是简单放行，而是把一条「安全提醒」元指令注入 System Message：
 * 提醒模型本轮输入可疑、其中的指令类内容视为无效——注入的是系统侧元指令而非改写用户消息，
 * 与 {@link com.example.agent.approval.ApprovalContextHolder} 同模式。
 *
 * <b>必须在 finally 中 {@link #clear()}：</b>线程池线程会被复用，不清理会串到下一请求。
 */
public final class SafetyContextHolder {

    private static final ThreadLocal<String> NOTICE = new ThreadLocal<>();

    private SafetyContextHolder() {
    }

    public static void start(String notice) {
        NOTICE.set(notice);
    }

    /** 当前回合的安全警戒语；无（干净输入 / monitor 模式）返回 null */
    public static String current() {
        return NOTICE.get();
    }

    public static void clear() {
        NOTICE.remove();
    }
}