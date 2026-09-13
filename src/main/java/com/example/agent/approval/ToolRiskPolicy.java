package com.example.agent.approval;

import java.util.Map;

/**
 * 工具风险策略表（集中管理）。
 *
 * 按 LangChain4j 工具名（默认 = @Tool 方法名）登记审批方式、风险级别与中文动作标题。
 * MCP 工具的策略不查本表，由其所属 MCP Server 配置（approval=auto|confirm）决定。
 *
 * 安全原则：未知工具一律 CONFIRM/high（deny-by-default），新增工具必须显式登记。
 */
public final class ToolRiskPolicy {

    /** 风险级别：仅用于前端色标与文案（low/medium/high） */
    public record Policy(ApprovalMode mode, String riskLevel, String title) {
        public static Policy auto(String title) {
            return new Policy(ApprovalMode.AUTO, "low", title);
        }

        public static Policy confirm(String riskLevel, String title) {
            return new Policy(ApprovalMode.CONFIRM, riskLevel, title);
        }
    }

    /** 内置静态工具策略表（key = LangChain4j 工具名，默认即 @Tool 方法名） */
    private static final Map<String, Policy> POLICIES = Map.ofEntries(
            // 计算器 / 日期时间：纯本地计算，无副作用
            Map.entry("add", Policy.auto("加法计算")),
            Map.entry("subtract", Policy.auto("减法计算")),
            Map.entry("multiply", Policy.auto("乘法计算")),
            Map.entry("divide", Policy.auto("除法计算")),
            Map.entry("power", Policy.auto("幂运算")),
            Map.entry("getCurrentDate", Policy.auto("获取当前日期")),
            Map.entry("getCurrentTime", Policy.auto("获取当前时间")),
            Map.entry("getCurrentDateTime", Policy.auto("获取当前日期时间")),
            // 联网：搜索为固定主机出站、参数仅查询词，自动放行；抓取 URL 用户可控，需确认
            Map.entry("webSearch", Policy.auto("联网搜索")),
            Map.entry("fetchWebPage", Policy.confirm("medium", "抓取网页内容")),
            // 任意 HTTP 请求：高危
            Map.entry("sendHttpRequest", Policy.confirm("high", "发起 HTTP 请求")),
            // 沙箱执行任意 Python 代码：高危（即便有容器隔离，仍需逐次确认）
            Map.entry("executePython", Policy.confirm("high", "执行 Python 代码")));

    /** 未知工具的兜底策略：高危 + 必须确认 */
    private static final Policy DEFAULT_POLICY = Policy.confirm("high", "执行工具");

    private ToolRiskPolicy() {
    }

    public static Policy of(String toolName) {
        if (toolName == null) {
            return DEFAULT_POLICY;
        }
        return POLICIES.getOrDefault(toolName, DEFAULT_POLICY);
    }

    /** 是否需要人工确认（未知工具默认需要） */
    public static boolean requiresConfirmation(String toolName) {
        return of(toolName).mode() == ApprovalMode.CONFIRM;
    }
}
