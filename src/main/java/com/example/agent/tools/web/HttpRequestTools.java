package com.example.agent.tools.web;

import com.example.agent.approval.ApprovalGate;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用 HTTP 请求工具：可对任意外部 API 发起 GET/POST/PUT/PATCH/DELETE。
 * 顺序：方法白名单 + {@link UrlSafetyGuard} 前置拦截（不弹窗）→ 人工确认 → 实际请求。
 * 不支持自定义请求头（v1 限制）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpRequestTools {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestTools.class);

    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final HttpRequestService httpRequestService;
    private final UrlSafetyGuard urlSafetyGuard;

    public HttpRequestTools(HttpRequestService httpRequestService, UrlSafetyGuard urlSafetyGuard) {
        this.httpRequestService = httpRequestService;
        this.urlSafetyGuard = urlSafetyGuard;
    }

    @Tool("""
            向第三方 HTTP/HTTPS 接口发起请求并返回响应（状态码、Content-Type、正文预览）。
            method 仅支持 GET/POST/PUT/PATCH/DELETE；url 为完整网址；
            body 可选（有请求体时按 JSON 发送），无请求体传 null。不支持自定义请求头。
            该操作会对外部系统产生真实请求，执行前必须经用户确认。
            """)
    public String sendHttpRequest(String method, String url, String body) {
        log.info("[Tool] sendHttpRequest(method={}, url={})", method, url);
        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase();
        // 1) 方法白名单 + SSRF 前置校验：在弹窗前直接拒绝
        if (!ALLOWED_METHODS.contains(normalizedMethod)) {
            return "HTTP 请求失败：不支持的方法 " + method + "（仅允许 GET/POST/PUT/PATCH/DELETE）";
        }
        try {
            urlSafetyGuard.validate(url);
        } catch (UnsafeUrlException e) {
            return "HTTP 请求失败：" + e.getMessage();
        }
        // 2) 高危：人工确认（审批后实际请求阶段会再次过 SSRF 校验）
        String preview = "方法: " + normalizedMethod + "\nURL: " + url
                + (body == null || body.isBlank() ? "" : "\n请求体:\n" + body);
        ApprovalGate.requireConfirmation("sendHttpRequest", preview);
        return httpRequestService.send(normalizedMethod, url, body);
    }
}
