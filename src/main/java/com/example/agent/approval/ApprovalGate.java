package com.example.agent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 请求级高危操作审批闸门（每个对话回合一个，ThreadLocal 取用）。
 *
 * 判定顺序：
 *   1. 策略 AUTO（低风险工具）→ 直接放行；
 *   2. 工具名在 payload 下发的 allowedTools（会话级「始终允许」）→ 直接放行；
 *   3. 无 SSE 出口（同步通道）→ 拒绝（该通道无法弹窗确认）；
 *   4. 登记审批条目 → 经 SSE 发 approval-request → 阻塞等待用户决策（超时按拒绝处理）。
 *
 * 拒绝/超时/取消均抛 {@link ApprovalDeniedException}，工具动作不得继续。
 */
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    public static final int MAX_ARGS_PREVIEW = 500;

    private final ApprovalRegistry registry;
    private final long timeoutSeconds;
    private final String userId;
    private final String traceId;
    private final Long conversationId;
    private final Set<String> allowedTools;
    /** 审批事件下发出口；null = 同步通道（不支持确认） */
    private final Consumer<ApprovalRequest> sink;

    public ApprovalGate(ApprovalRegistry registry,
                        long timeoutSeconds,
                        String userId,
                        Long conversationId,
                        String traceId,
                        Set<String> allowedTools,
                        Consumer<ApprovalRequest> sink) {
        this.registry = registry;
        this.timeoutSeconds = timeoutSeconds;
        this.userId = userId;
        this.traceId = traceId;
        this.conversationId = conversationId;
        this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
        this.sink = sink;
    }

    /**
     * 静态工具方法（@Tool 方法首行调用）：按静态风险策略表校验。
     * 无请求上下文（理论上不应发生）时 deny-by-default。
     */
    public static void requireConfirmation(String toolName, String argsPreview) {
        requireConfirmation(toolName, argsPreview, ToolRiskPolicy.of(toolName));
    }

    /** MCP 工具入口：使用所属 Server 配置的显式策略 */
    public static void requireConfirmation(String toolName, String argsPreview,
                                           ToolRiskPolicy.Policy policy) {
        ApprovalGate gate = ApprovalContextHolder.current();
        if (gate == null) {
            throw new ApprovalDeniedException("当前会话不存在审批上下文，高危操作默认拒绝");
        }
        gate.check(toolName, argsPreview, policy);
    }

    /**
     * 执行审批检查；通过则正常返回，不通过抛 {@link ApprovalDeniedException}。
     */
    public void check(String toolName, String argsPreview, ToolRiskPolicy.Policy policy) {
        if (policy.mode() == ApprovalMode.AUTO) {
            return;
        }
        if (allowedTools.contains(toolName)) {
            log.info("[Approval] 命中会话级授权，直接放行: traceId={}, tool={}", traceId, toolName);
            return;
        }
        if (sink == null) {
            log.warn("[Approval] 同步通道不支持确认，拒绝高危操作: traceId={}, tool={}", traceId, toolName);
            throw new ApprovalDeniedException(
                    "「" + policy.title() + "」属于高危操作，需要在对话界面经用户确认后才能执行；"
                            + "当前调用通道不支持确认，操作已阻止。");
        }

        ApprovalRequest request = new ApprovalRequest(
                UUID.randomUUID().toString(),
                traceId,
                userId,
                conversationId,
                toolName,
                policy.riskLevel(),
                policy.title(),
                truncate(argsPreview),
                System.currentTimeMillis());

        ApprovalEntry entry = registry.register(request);
        log.info("[Approval] 请求用户审批: traceId={}, id={}, tool={}, risk={}",
                traceId, request.approvalId(), toolName, policy.riskLevel());
        try {
            sink.accept(request);
        } catch (Exception e) {
            log.warn("[Approval] 审批请求下发失败，拒绝执行: traceId={}, tool={}, err={}",
                    traceId, toolName, e.getMessage());
            throw new ApprovalDeniedException("审批请求下发失败（连接可能已断开），操作已阻止");
        }

        ApprovalDecision decision = awaitDecision(entry);
        if (decision.approved()) {
            log.info("[Approval] 用户批准，继续执行: traceId={}, tool={}, remember={}",
                    traceId, toolName, decision.remember());
            return;
        }
        log.warn("[Approval] 用户拒绝/超时/取消，阻止执行: traceId={}, tool={}", traceId, toolName);
        throw new ApprovalDeniedException("用户未批准「" + policy.title() + "」，操作未执行。");
    }

    /** 阻塞等待决策（超时已由 ApprovalRegistry 施加在原始 future 上），超时按拒绝处理 */
    private ApprovalDecision awaitDecision(ApprovalEntry entry) {
        try {
            return entry.future().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof TimeoutException) {
                log.warn("[Approval] 等待用户确认超时（{}s），自动拒绝: id={}",
                        timeoutSeconds, entry.request().approvalId());
                throw new ApprovalDeniedException(
                        "等待操作确认超时（" + timeoutSeconds + " 秒），操作已自动取消。");
            }
            throw new ApprovalDeniedException("等待审批过程中发生错误，操作已取消。");
        }
    }

    /** 回合取消/断连时拒绝全部待审批（由 AgentController 回调） */
    public void denyAllPending() {
        registry.denyAllByTrace(traceId);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_ARGS_PREVIEW ? s : s.substring(0, MAX_ARGS_PREVIEW) + "...";
    }
}
