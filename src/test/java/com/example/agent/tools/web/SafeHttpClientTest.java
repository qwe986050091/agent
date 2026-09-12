package com.example.agent.tools.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安全 HTTP 客户端测试：用 JDK 内置 HttpServer 验证
 * ①「公网 302 → 内网」在第二跳被 SSRF 校验拦截；②响应体按上限截断。
 * 首跳直接调包可见 exchange()（send() 入口的首跳校验由 UrlSafetyGuardTest 覆盖）。
 */
class SafeHttpClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private SafeHttpClient client(int maxBodyBytes) {
        WebToolsProperties props = new WebToolsProperties();
        props.setMaxBodyBytes(maxBodyBytes);
        return new SafeHttpClient(new UrlSafetyGuard(), props);
    }

    private String localUrl(int port, String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @Test
    void redirectToInternalAddressBlockedOnSecondHop() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/r", (HttpExchange ex) -> {
            // 模拟「公网链接 302 跳内网」
            ex.getResponseHeaders().add("Location", localUrl(port, "/internal"));
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();

        SafeHttpClient c = client(1024);
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
                () -> c.exchange("GET", URI.create(localUrl(port, "/r")), null, 0));
        assertTrue(ex.getMessage().contains("内网") || ex.getMessage().contains("保留地址"));
    }

    @Test
    void bodyTruncatedAtLimit() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/big", (HttpExchange ex) -> {
            byte[] payload = "0123456789".repeat(10).getBytes(StandardCharsets.UTF_8); // 100 字节
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
        });
        server.start();

        SafeHttpResponse resp = client(20).exchange("GET", URI.create(localUrl(port, "/big")), null, 0);
        assertEquals(200, resp.status());
        assertTrue(resp.truncated(), "超过 20 字节应标记截断");
        assertEquals(20, resp.body().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void methodWhitelistEnforced() {
        SafeHttpClient c = client(1024);
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class,
                () -> c.send("CONNECT", "http://example.com:443", null));
        assertTrue(ex.getMessage().contains("方法"));
    }

    @Test
    void internalUrlBlockedOnFirstHop() {
        SafeHttpClient c = client(1024);
        assertThrows(UnsafeUrlException.class, () -> c.get("http://localhost/secret"));
    }

    @Test
    void slowDripBodyAbortedByReadDeadline() throws Exception {
        // F-06：慢滴流响应必须在 readTimeout 总时限内中止，不能长期占用回调线程
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/slow", (HttpExchange ex) -> {
            ex.getResponseHeaders().add("Content-Type", "text/plain");
            ex.sendResponseHeaders(200, 0); // 流式响应，无 Content-Length
            byte[] chunk = "0123456789".getBytes(StandardCharsets.UTF_8);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 10; i++) {
                    os.write(chunk);
                    os.flush();
                    try {
                        Thread.sleep(500); // 全程 5s，超过 2s 读取总时限
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (IOException ignored) {
                // 客户端主动断开
            } finally {
                ex.close();
            }
        });
        server.start();

        WebToolsProperties props = new WebToolsProperties();
        props.setMaxBodyBytes(1024 * 1024);
        props.setReadTimeout(java.time.Duration.ofSeconds(2));
        SafeHttpClient c = new SafeHttpClient(new UrlSafetyGuard(), props);

        long t0 = System.currentTimeMillis();
        // 首跳为本机测试服务，直接调包可见 exchange 绕过入口 SSRF 校验（读超时逻辑与首跳无关）
        assertThrows(Exception.class,
                () -> c.exchange("GET", URI.create(localUrl(port, "/slow")), null, 0));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(elapsed < 5000, "应在约 2s 读取时限内中止，实际 " + elapsed + "ms");
    }
}
