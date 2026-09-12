package com.example.agent.tools.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 带 SSRF 防护与资源上限的 HTTP 客户端（联网工具共用）。
 *
 * - 所有 URL（含重定向逐跳）经 {@link UrlSafetyGuard} 校验；
 * - 不自动跟随重定向：手动跟随且 ≤3 跳，每跳重新校验，阻断「公网 302 → 内网」绕过；
 * - 响应体按 maxBodyBytes 截断读取，连接/读取超时受配置约束；
 * - 统一浏览器 UA 与中文 Accept-Language。
 */
@Component
public class SafeHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SafeHttpClient.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    /** 允许的方法白名单（CONNECT/TRACE/TRACK 等永远不放行） */
    private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private static final int MAX_REDIRECTS = 3;

    private final UrlSafetyGuard guard;
    private final WebToolsProperties properties;
    private final HttpClient httpClient;

    public SafeHttpClient(UrlSafetyGuard guard, WebToolsProperties properties) {
        this.guard = guard;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** GET 请求（搜索/抓取） */
    public SafeHttpResponse get(String url) {
        return send("GET", url, null);
    }

    /**
     * 发起受保护的 HTTP 请求。
     *
     * @param method GET/POST/PUT/PATCH/DELETE（白名单校验）
     * @param body   请求体（可空；非空时按 application/json; charset=utf-8 发送）
     */
    public SafeHttpResponse send(String method, String url, String body) {
        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(normalizedMethod)) {
            throw new UnsafeUrlException("不支持的 HTTP 方法: " + method);
        }
        URI uri = guard.validate(url);
        try {
            return exchange(normalizedMethod, uri, body, 0);
        } catch (UnsafeUrlException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[SafeHttp] 请求失败: {} {} -> {}", normalizedMethod, url, e.getMessage());
            if (hasTimeoutCause(e)) {
                throw new SafeHttpException("网络请求超时（连接/读取超过 "
                        + (properties.getReadTimeout() == null ? Duration.ofSeconds(10)
                        : properties.getReadTimeout()).toSeconds() + "s）");
            }
            throw new SafeHttpException("网络请求失败：" + rootMessage(e));
        }
    }

    /** JDK 请求级超时（HttpTimeoutException，含 body 读取阶段静默挂起）或我们的读取截止异常 */
    private static boolean hasTimeoutCause(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            String simple = cause.getClass().getSimpleName();
            if (simple.contains("HttpTimeoutException") || cause instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    // 包可见：首跳 URI 已由 send() 入口的 guard 校验；测试可直接构造首跳以验证「重定向逐跳校验」
    SafeHttpResponse exchange(String method, URI uri, String body, int redirects) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(properties.getReadTimeout() == null ? Duration.ofSeconds(10) : properties.getReadTimeout())
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();
        if (body != null && !body.isEmpty()) {
            bodyPublisher = HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            builder.header("Content-Type", "application/json; charset=utf-8");
        }
        builder.method(method, bodyPublisher);

        HttpResponse<InputStream> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());

        int status = statusOf(response);
        // 3xx：手动跟随，逐跳重新过 SSRF 校验
        if (status >= 300 && status < 400 && redirects < MAX_REDIRECTS) {
            String location = response.headers().firstValue("Location").orElse(null);
            // 丢弃响应体后再跳转
            response.body().close();
            if (location == null || location.isBlank()) {
                return new SafeHttpResponse(status, contentTypeOf(response), uri.toString(), "", false);
            }
            URI next = uri.resolve(location.trim());
            log.info("[SafeHttp] 跟随重定向 {}: {} -> {}", status, uri, next);
            // 307/308 保留方法与请求体；301/302/303 浏览器惯例改用 GET（不带 body）
            String nextMethod = (status == 307 || status == 308) ? method : "GET";
            String nextBody = (status == 307 || status == 308) ? body : null;
            URI validated = guard.validate(next.toString());
            return exchange(nextMethod, validated, nextBody, redirects + 1);
        }

        String contentType = contentTypeOf(response);
        byte[] bytes;
        boolean truncated = false;
        // body 读取总时限（兜底）：请求级 HttpRequest.timeout 在多数 JDK 上覆盖整个交换；
        // 此截止保证「read 返回后」也能终止慢滴流，双重保险
        long readDeadlineNanos = System.nanoTime()
                + (properties.getReadTimeout() == null ? Duration.ofSeconds(10) : properties.getReadTimeout())
                .toMillis() * 1_000_000L;
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int limit = properties.getMaxBodyBytes();
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (total + n > limit) {
                    out.write(buffer, 0, Math.max(0, limit - total));
                    total = limit;
                    truncated = true;
                    // 截断即停：不再无限 drain（直接关流，放弃该连接的复用）
                    break;
                }
                out.write(buffer, 0, n);
                total += n;
                if (System.nanoTime() > readDeadlineNanos) {
                    throw new SafeHttpException("读取响应超时（超过 "
                            + (properties.getReadTimeout() == null ? Duration.ofSeconds(10)
                            : properties.getReadTimeout()).toSeconds() + "s）");
                }
            }
            bytes = out.toByteArray();
        }
        Charset charset = charsetOf(contentType);
        return new SafeHttpResponse(status, contentType, uri.toString(), new String(bytes, charset), truncated);
    }

    private static String contentTypeOf(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse(null);
    }

    /**
     * 读取状态码：JDK 25 起 {@code HttpResponse.status()} 更名为 {@code statusCode()}（26 已删除旧名），
     * 部署基线为 Java 21（只有 status()），用反射兼容两个运行时，避免直接引用导致 NoSuchMethodError。
     */
    private static int statusOf(HttpResponse<?> response) {
        try {
            return (int) HttpResponse.class.getMethod("status").invoke(response);
        } catch (NoSuchMethodException e) {
            try {
                return (int) HttpResponse.class.getMethod("statusCode").invoke(response);
            } catch (ReflectiveOperationException ex) {
                throw new SafeHttpException("无法读取 HTTP 响应状态码");
            }
        } catch (ReflectiveOperationException e) {
            throw new SafeHttpException("读取 HTTP 响应状态码失败");
        }
    }

    /** 从 Content-Type 解析字符集，缺省 UTF-8 */
    private static Charset charsetOf(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.toLowerCase().startsWith("charset=")) {
                    try {
                        return Charset.forName(trimmed.substring("charset=".length()).trim());
                    } catch (Exception ignore) {
                        // 未知字符集回退 UTF-8
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg == null ? cause.getClass().getSimpleName() : msg;
    }
}
