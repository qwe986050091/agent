package com.example.agent.tools.web;

import com.example.agent.approval.ApprovalDeniedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-01 回归：SSRF/方法白名单必须在人工审批之前拦截（不触发审批事件）。
 * 不设置 ApprovalContextHolder：若代码走到审批闸门会抛「默认拒绝」，
 * 而预期是直接返回中文错误文本（说明在审批前已被拦截）。
 */
class ToolSafetyPrecheckTest {

    private final UrlSafetyGuard guard = new UrlSafetyGuard();

    @Test
    void fetchInternalUrlRejectedBeforeApproval() {
        WebFetchTools tools = new WebFetchTools(new WebFetchService(null, null) {
            @Override
            public String fetch(String url) {
                throw new AssertionError("内部地址不应触达抓取服务");
            }
        }, guard);

        String result = tools.fetchWebPage("http://169.254.169.254/latest/meta-data/");
        assertTrue(result.startsWith("网页抓取失败"), result);
        assertFalse(result.contains("审批"), result);
    }

    @Test
    void fetchIllegalSchemeRejectedBeforeApproval() {
        WebFetchTools tools = new WebFetchTools(new WebFetchService(null, null) {
            @Override
            public String fetch(String url) {
                throw new AssertionError("非法协议不应触达抓取服务");
            }
        }, guard);

        assertTrue(tools.fetchWebPage("file:///etc/passwd").startsWith("网页抓取失败"));
    }

    @Test
    void fetchSafeUrlReachesApprovalGate() {
        // 公网地址通过 SSRF 前置校验后必须进入审批（无上下文 → 默认拒绝），证明顺序正确
        WebFetchTools tools = new WebFetchTools(new WebFetchService(null, null) {
            @Override
            public String fetch(String url) {
                return "SHOULD_NOT_REACH";
            }
        }, guard);
        // 用公网 IP 字面量避免测试依赖 DNS
        ApprovalDeniedException ex = assertThrows(ApprovalDeniedException.class,
                () -> tools.fetchWebPage("http://8.8.8.8/page"));
        assertTrue(ex.getMessage().contains("默认拒绝") || ex.getMessage().contains("审批")
                || ex.getMessage().contains("对话界面"));
    }

    @Test
    void httpMethodWhitelistRejectedBeforeApproval() {
        HttpRequestTools tools = new HttpRequestTools(new HttpRequestService(null, null) {
            @Override
            public String send(String method, String url, String body) {
                throw new AssertionError("白名单外方法不应触达请求服务");
            }
        }, guard);

        assertTrue(tools.sendHttpRequest("CONNECT", "https://example.com:443", null)
                .startsWith("HTTP 请求失败"));
    }

    @Test
    void httpInternalUrlRejectedBeforeApproval() {
        HttpRequestTools tools = new HttpRequestTools(new HttpRequestService(null, null) {
            @Override
            public String send(String method, String url, String body) {
                throw new AssertionError("内部地址不应触达请求服务");
            }
        }, guard);

        String result = tools.sendHttpRequest("GET", "http://127.0.0.1:8081/api/health", null);
        assertTrue(result.startsWith("HTTP 请求失败"), result);
    }
}
