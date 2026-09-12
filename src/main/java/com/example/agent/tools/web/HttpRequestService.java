package com.example.agent.tools.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通用 HTTP 请求服务（白名单方法 + SSRF 校验 + 资源上限）。
 * 高危工具：调用方（@Tool 方法）必须先过人工审批。
 */
@Component
public class HttpRequestService {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestService.class);

    private final SafeHttpClient httpClient;
    private final WebToolsProperties properties;

    public HttpRequestService(SafeHttpClient httpClient, WebToolsProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public String send(String method, String url, String body) {
        try {
            SafeHttpResponse resp = httpClient.send(method, url, body);
            String contentType = resp.contentType() == null ? "未知" : resp.contentType();
            String text = resp.body();
            int limit = properties.getMaxTextChars();
            if (text.length() > limit) {
                text = text.substring(0, limit) + "\n...（响应内容过长已截断）";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP ").append(resp.status()).append('\n');
            sb.append("Content-Type: ").append(contentType).append('\n');
            sb.append("最终 URL: ").append(resp.finalUrl()).append('\n');
            if (resp.truncated()) {
                sb.append("注意：原始响应超过下载上限，内容可能不完整\n");
            }
            sb.append("\n响应正文：\n").append(text.isBlank() ? "（空响应）" : text);
            return sb.toString();
        } catch (UnsafeUrlException | SafeHttpException e) {
            return "HTTP 请求失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("[HttpRequest] 请求失败: {}", e.getMessage());
            return "HTTP 请求失败：" + e.getMessage();
        }
    }
}
