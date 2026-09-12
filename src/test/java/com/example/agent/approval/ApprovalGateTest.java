package com.example.agent.approval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批闸门单元测试：不依赖 Spring/LangChain4j，纯 JDK 验证
 * 自动放行/会话授权/同步拒绝/批准/拒绝/超时/归属校验/回合取消。
 */
class ApprovalGateTest {

    private ApprovalRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.shutdown();
        }
        ApprovalContextHolder.clear();
    }

    private ApprovalRegistry registry(long timeoutSeconds) {
        registry = new ApprovalRegistry(timeoutSeconds);
        return registry;
    }

    private ApprovalGate gate(ApprovalRegistry reg, long timeoutSeconds, Set<String> allowed,
                              java.util.function.Consumer<ApprovalRequest> sink) {
        return new ApprovalGate(reg, timeoutSeconds, "alice", 7L, "trace-1", allowed, sink);
    }

    @Test
    void autoPolicyPassesWithoutSink() {
        ApprovalRegistry reg = registry(60);
        ApprovalContextHolder.start(gate(reg, 60, Set.of(), null));
        // add = 计算器工具，AUTO
        ApprovalGate.requireConfirmation("add", "1+2");
    }

    @Test
    void contextMissingDeniesByDefault() {
        ApprovalDeniedException ex = assertThrows(ApprovalDeniedException.class,
                () -> ApprovalGate.requireConfirmation("fetchWebPage", "http://example.com"));
        assertTrue(ex.getMessage().contains("默认拒绝"));
    }

    @Test
    void syncChannelWithoutSinkDenies() {
        ApprovalRegistry reg = registry(60);
        ApprovalContextHolder.start(gate(reg, 60, Set.of(), null));
        ApprovalDeniedException ex = assertThrows(ApprovalDeniedException.class,
                () -> ApprovalGate.requireConfirmation("fetchWebPage", "http://example.com"));
        assertTrue(ex.getMessage().contains("对话界面"));
    }

    @Test
    void allowedToolBypassesApproval() {
        ApprovalRegistry reg = registry(60);
        AtomicReference<ApprovalRequest> sent = new AtomicReference<>();
        ApprovalContextHolder.start(gate(reg, 60, Set.of("fetchWebPage"), sent::set));
        ApprovalGate.requireConfirmation("fetchWebPage", "http://example.com");
        // 命中会话授权：不应下发任何审批事件
        assertEquals(null, sent.get());
    }

    @Test
    void approvedDecisionContinues() throws Exception {
        ApprovalRegistry reg = registry(60);
        AtomicReference<ApprovalRequest> sent = new AtomicReference<>();
        ApprovalContextHolder.start(gate(reg, 60, Set.of(), sent::set));

        Thread decider = new Thread(() -> {
            try {
                Thread.sleep(100);
                ApprovalRequest req = sent.get();
                ApprovalRegistry.ResolveResult rr = reg.resolve(
                        req.approvalId(), "alice", new ApprovalDecision(true, false));
                assertEquals(ApprovalRegistry.ResolveOutcome.RESOLVED, rr.outcome());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        decider.setDaemon(true);
        decider.start();

        ApprovalGate.requireConfirmation("sendHttpRequest", "GET http://example.com");
        decider.join(2000);
    }

    @Test
    void deniedDecisionThrows() throws Exception {
        ApprovalRegistry reg = registry(60);
        List<ApprovalRequest> sent = new ArrayList<>();
        ApprovalContextHolder.start(gate(reg, 60, Set.of(), sent::add));

        Thread decider = new Thread(() -> {
            try {
                Thread.sleep(100);
                reg.resolve(sent.get(0).approvalId(), "alice", ApprovalDecision.DENIED);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        decider.setDaemon(true);
        decider.start();

        ApprovalDeniedException ex = assertThrows(ApprovalDeniedException.class,
                () -> ApprovalGate.requireConfirmation("sendHttpRequest", "GET http://x"));
        assertTrue(ex.getMessage().contains("未批准"));
        decider.join(2000);
    }

    @Test
    void timeoutAutoDenies() {
        ApprovalRegistry reg = registry(1);
        ApprovalContextHolder.start(gate(reg, 1, Set.of(), req -> {
        }));
        long t0 = System.currentTimeMillis();
        ApprovalDeniedException ex = assertThrows(ApprovalDeniedException.class,
                () -> ApprovalGate.requireConfirmation("fetchWebPage", "http://slow.example.com"));
        assertTrue(ex.getMessage().contains("超时"));
        assertTrue(System.currentTimeMillis() - t0 < 3000, "超时应在约 1s 后返回，不能悬挂");
        ApprovalContextHolder.clear();
    }

    @Test
    void lateDecisionAfterTimeoutConflicts() throws Exception {
        // F-05：超时施加在原始 future 上，迟到决策必须 409（ALREADY_DECIDED），不得 200/写授权
        ApprovalRegistry reg = registry(1);
        AtomicReference<ApprovalRequest> sent = new AtomicReference<>();
        // 用独立线程触发一次审批并等待其超时
        Thread tool = new Thread(() -> {
            ApprovalGate gate1 = gate(reg, 1, Set.of(), sent::set);
            ApprovalContextHolder.start(gate1);
            try {
                gate1.check("sendHttpRequest", "url",
                        ToolRiskPolicy.of("sendHttpRequest"));
            } catch (ApprovalDeniedException ignored) {
                // 预期超时拒绝
            } finally {
                ApprovalContextHolder.clear();
            }
        });
        tool.setDaemon(true);
        tool.start();
        tool.join(3000);
        Thread.sleep(200);
        // 迟到的「批准+始终允许」
        ApprovalRegistry.ResolveResult rr = reg.resolve(sent.get().approvalId(), "alice",
                new ApprovalDecision(true, true));
        assertEquals(ApprovalRegistry.ResolveOutcome.ALREADY_DECIDED, rr.outcome());
        ApprovalContextHolder.clear();
    }

    @Test
    void wrongUserReturnsNotFoundAndCannotDecide() {
        ApprovalRegistry reg = registry(60);
        ApprovalRequest req = new ApprovalRequest("id-1", "trace-1", "alice", 7L,
                "sendHttpRequest", "high", "发起 HTTP 请求", "{}", System.currentTimeMillis());
        reg.register(req);
        ApprovalRegistry.ResolveResult otherUser =
                reg.resolve("id-1", "bob", new ApprovalDecision(true, false));
        assertEquals(ApprovalRegistry.ResolveOutcome.NOT_FOUND, otherUser.outcome());
        // 条目仍然存在且未被决策
        ApprovalRegistry.ResolveResult owner =
                reg.resolve("id-1", "alice", ApprovalDecision.DENIED);
        assertEquals(ApprovalRegistry.ResolveOutcome.RESOLVED, owner.outcome());
    }

    @Test
    void duplicateDecisionConflicts() {
        ApprovalRegistry reg = registry(60);
        ApprovalRequest req = new ApprovalRequest("id-2", "trace-1", "alice", 7L,
                "fetchWebPage", "medium", "抓取网页", "", System.currentTimeMillis());
        reg.register(req);
        assertEquals(ApprovalRegistry.ResolveOutcome.RESOLVED,
                reg.resolve("id-2", "alice", new ApprovalDecision(true, true)).outcome());
        assertEquals(ApprovalRegistry.ResolveOutcome.ALREADY_DECIDED,
                reg.resolve("id-2", "alice", new ApprovalDecision(false, false)).outcome());
        assertEquals(0, reg.pendingCount());
    }

    @Test
    void denyAllByTraceUnblocksPending() throws Exception {
        ApprovalRegistry reg = registry(60);
        ApprovalGate g = gate(reg, 60, Set.of(), req -> {
        });
        ApprovalContextHolder.start(g);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread tool = new Thread(() -> {
            ApprovalContextHolder.start(g);
            try {
                ApprovalGate.requireConfirmation("sendHttpRequest", "GET http://x");
            } catch (Throwable t) {
                error.set(t);
            } finally {
                ApprovalContextHolder.clear();
            }
        });
        tool.setDaemon(true);
        tool.start();
        Thread.sleep(150);
        assertEquals(1, reg.pendingCount());
        g.denyAllPending();
        tool.join(2000);
        assertFalse(tool.isAlive());
        assertTrue(error.get() instanceof ApprovalDeniedException);
        assertEquals(0, reg.pendingCount());
    }
}
