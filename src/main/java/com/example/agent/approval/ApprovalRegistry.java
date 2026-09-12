package com.example.agent.approval;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 待审批条目注册表（单例，仅保存回合内瞬态）。
 *
 * - 工具线程在 ReAct 循环中登记条目并阻塞等待决策；
 * - 决策请求由 {@link ApprovalController}（另一 Tomcat 线程）resolve；
 * - 超时由 {@link ApprovalGate} 的 orTimeout 释放工具线程，本注册表额外做 TTL 清理防内存泄漏；
 * - SSE 取消/断连时按 traceId 全部拒绝（denyAllByTrace）。
 *
 * 无状态化边界：跨请求的「始终允许」授权保存在 backend Redis，agent 不保存任何跨回合状态。
 */
@Component
public class ApprovalRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRegistry.class);

    /** 审批决策结果（供 Controller 映射 HTTP 状态） */
    public enum ResolveOutcome {
        RESOLVED,
        NOT_FOUND,
        ALREADY_DECIDED
    }

    public record ResolveResult(ResolveOutcome outcome, ApprovalRequest request) {
    }

    private final Map<String, ApprovalEntry> entries = new ConcurrentHashMap<>();

    /** 条目在注册表中的最长存活时间 = 审批超时 + 60s 清理缓冲 */
    private final long ttlMillis;
    /** 审批等待超时秒数（超时直接作用于原始 future，迟到决策将得到 409） */
    private final long timeoutSeconds;

    private final ScheduledExecutorService purgeExecutor;

    public ApprovalRegistry(@Value("${agent.approval.timeout-seconds:60}") long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.ttlMillis = TimeUnit.SECONDS.toMillis(timeoutSeconds + 60);
        this.purgeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "approval-purge");
            t.setDaemon(true);
            return t;
        });
        // 每 30s 清理过期条目（并对漏网的未决条目兜底拒绝）
        this.purgeExecutor.scheduleAtFixedRate(this::purgeExpired, 30, 30, TimeUnit.SECONDS);
    }

    /** 登记一条待审批，返回条目（工具线程据此等待决策；超时在原始 future 上生效） */
    public ApprovalEntry register(ApprovalRequest request) {
        ApprovalEntry entry = new ApprovalEntry(request);
        entries.put(request.approvalId(), entry);
        // 直接作用于原始 future：超时后条目保留至 TTL，期间迟到决策 → complete()=false → 409
        entry.future().orTimeout(timeoutSeconds, TimeUnit.SECONDS);
        return entry;
    }

    /**
     * 提交决策。
     * - 条目不存在或 userId 不匹配：NOT_FOUND（不泄露存在性，统一 404）
     * - 已被决策/超时/取消：ALREADY_DECIDED（409）
     * - 成功：complete Future 并移除条目
     */
    public ResolveResult resolve(String approvalId, String userId, ApprovalDecision decision) {
        ApprovalEntry entry = entries.get(approvalId);
        if (entry == null || entry.request().userId() == null
                || !entry.request().userId().equals(userId)) {
            log.warn("[Approval] 决策目标不存在或归属不匹配: id={}, user={}", approvalId, userId);
            return new ResolveResult(ResolveOutcome.NOT_FOUND, null);
        }
        // complete 返回 false 表示已被超时/取消/重复决策先行处理
        boolean accepted = entry.future().complete(decision);
        if (!accepted) {
            // 已决条目保留至 TTL 清理：短时间内的重复决策可区分 409 与「从未存在」的 404
            return new ResolveResult(ResolveOutcome.ALREADY_DECIDED, entry.request());
        }
        // 不立即移除：已完成的条目保留至 TTL（≈超时+60s），供重复决策返回 409；purge 负责清理
        log.info("[Approval] 决策已记录: traceId={}, tool={}, user={}, approved={}, remember={}",
                entry.request().traceId(), entry.request().tool(), userId,
                decision.approved(), decision.remember());
        return new ResolveResult(ResolveOutcome.RESOLVED, entry.request());
    }

    /** 某个回合（traceId）的全部待审批条目按拒绝放行（用户停止/断连/超时兜底） */
    public void denyAllByTrace(String traceId) {
        if (traceId == null) {
            return;
        }
        int count = 0;
        for (Map.Entry<String, ApprovalEntry> mapEntry : entries.entrySet()) {
            ApprovalEntry entry = mapEntry.getValue();
            if (traceId.equals(entry.request().traceId()) && entry.future().complete(ApprovalDecision.DENIED)) {
                entries.remove(mapEntry.getKey(), entry);
                count++;
            }
        }
        if (count > 0) {
            log.info("[Approval] 回合取消，拒绝全部待审批: traceId={}, count={}", traceId, count);
        }
    }

    /** 当前等待中的审批条目数（已决但未到 TTL 清理的条目不计；监控/测试用） */
    public int pendingCount() {
        int count = 0;
        for (ApprovalEntry entry : entries.values()) {
            if (!entry.isDone()) {
                count++;
            }
        }
        return count;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Map.Entry<String, ApprovalEntry> mapEntry : entries.entrySet()) {
            ApprovalEntry entry = mapEntry.getValue();
            if (now - entry.request().createdAt() > ttlMillis) {
                if (entry.future().complete(ApprovalDecision.DENIED)) {
                    count++;
                }
                entries.remove(mapEntry.getKey(), entry);
            }
        }
        if (count > 0) {
            log.warn("[Approval] TTL 清理过期未决审批: count={}", count);
        }
    }

    @PreDestroy
    void shutdown() {
        purgeExecutor.shutdownNow();
        // 关闭时全部拒绝，避免工具线程悬挂
        for (ApprovalEntry entry : entries.values()) {
            entry.future().complete(ApprovalDecision.DENIED);
        }
        entries.clear();
    }
}
