package com.example.agent.tools.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 防护单测：全部使用 IP 字面量/本地解析，不依赖外网 DNS。
 */
class UrlSafetyGuardTest {

    private final UrlSafetyGuard guard = new UrlSafetyGuard();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://127.1.2.3/",
            "http://localhost/",
            "http://169.254.169.254/latest/meta-data/",
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://172.31.255.255/",
            "http://192.168.1.1/",
            "http://100.64.0.1/",
            "http://100.127.255.255/",
            "http://0.0.0.0/",
            "http://255.255.255.255/",
            "http://[::1]/",
            "http://[::7f00:1]/",              // IPv4-compatible 内嵌 127.0.0.1
            "http://[::ffff:7f00:1]/",          // IPv4-mapped 十六进制写法
            "http://[fc00::1]/",
            "http://[fd12::1]/",
            "http://[fe80::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://user:pass@127.0.0.1/",
            "http://user@10.0.0.1/",
    })
    void blockedAddressesRejected(String url) {
        UnsafeUrlException ex = assertThrows(UnsafeUrlException.class, () -> guard.validate(url));
        // 错误文案不得回显内网细节（这里仅要求为安全类提示文案）
        assertTrue(ex.getMessage().contains("安全") || ex.getMessage().contains("协议")
                || ex.getMessage().contains("用户信息") || ex.getMessage().contains("内网")
                || ex.getMessage().contains("无法解析") || ex.getMessage().contains("保留地址"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/",
            "http://223.5.5.5/",
            "http://1.1.1.1/path?q=1",
            "https://172.32.0.1/",      // 172.16/12 边界之外，放行
            "http://100.128.0.1/",      // CGNAT 100.64/10 边界之外，放行
            "http://[2606:4700:4700::1111]/",
    })
    void publicAddressesPass(String url) {
        assertDoesNotThrow(() -> guard.validate(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://8.8.8.8/",
            "gopher://8.8.8.8/",
            "example.com",                  // 非绝对 URL
            "http://",
    })
    void illegalSchemeOrFormatRejected(String url) {
        assertThrows(UnsafeUrlException.class, () -> guard.validate(url));
    }

    @Test
    void blankUrlRejected() {
        assertThrows(UnsafeUrlException.class, () -> guard.validate("  "));
    }
}
