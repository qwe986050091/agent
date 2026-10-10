package com.example.agent.tools.web;

import com.example.agent.security.UntrustedContentWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 通用 HTTP 请求服务（白名单方法 + SSRF 校验 + 资源上限）。
 * 高危工具：调用方（@Tool 方法）必须先过人工审批。
 * 响应正文经 {@link UntrustedContentWrapper} 包裹（P4-17：接口响应是间接注入载体）。
 */
@Component
public class HttpRequestService {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestService.class);

    private final SafeHttpClient httpClient;
    private final WebToolsProperties properties;
    private final UntrustedContentWrapper untrustedContentWrapper;

    public HttpRequestService(SafeHttpClient httpClient, WebToolsProperties properties,
                              UntrustedContentWrapper untrustedContentWrapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.untrustedContentWrapper = untrustedContentWrapper;
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
            StringBuilder meta = new StringBuilder();
            meta.append("HTTP ").append(resp.status()).append('\n');
            meta.append("Content-Type: ").append(contentType).append('\n');
            meta.append("最终 URL: ").append(resp.finalUrl()).append('\n');
            if (resp.truncated()) {
                meta.append("注意：原始响应超过下载上限，内容可能不完整\n");
            }
            // 不可信内容边界化（P4-17 §5）：元信息行保留在边界外，仅包裹正文
            String bodyOut = text.isBlank()
                    ? "（空响应）" : untrustedContentWrapper.wrap("sendHttpRequest", text);
            return meta + "\n响应正文：\n" + bodyOut;
        } catch (UnsafeUrlException | SafeHttpException e) {
            return "HTTP 请求失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("[HttpRequest] 请求失败: {}", e.getMessage());
            return "HTTP 请求失败：" + e.getMessage();
        }
    }
}
