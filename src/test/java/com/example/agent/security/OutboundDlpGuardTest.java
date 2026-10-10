package com.example.agent.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出站 DLP 单测（P4-17 §6.3）：
 * 登记凭据（原文/base64）精确命中 → 直接拒绝且不回显值；高危模式 → 仅警告（人工确认）；
 * 短值不登记；开关可回滚。
 */
class OutboundDlpGuardTest {

    private static final String SECRET = "sk-test-secret-value-1234567890";

    private final SafetyProperties props = new SafetyProperties();
    private final SensitiveValueRegistry registry =
            new SensitiveValueRegistry(Map.of("OPENAI_API_KEY", SECRET));
    private final OutboundDlpGuard guard = new OutboundDlpGuard(registry, props);

    @Test
    void realSecretInBodyIsBlockedWithoutEcho() {
        OutboundDlpGuard.DlpResult r = guard.inspect(
                "POST https://api.example.com\n{\"key\":\"" + SECRET + "\"}");

        assertThat(r.blocked()).isTrue();
        // 拦截文案不得回显凭据值（也不回显键名之外的任何细节）
        assertThat(r.blockReason()).doesNotContain(SECRET);
    }

    @Test
    void base64EncodedSecretIsBlocked() {
        String b64 = Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.UTF_8));

        OutboundDlpGuard.DlpResult r = guard.inspect(
                "POST https://api.example.com\n{\"d\":\"" + b64 + "\"}");

        assertThat(r.blocked()).isTrue();
    }

    @Test
    void cleanRequestPassesWithoutWarnings() {
        OutboundDlpGuard.DlpResult r = guard.inspect("GET https://api.github.com/repos/foo/bar");

        assertThat(r.blocked()).isFalse();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void highRiskPatternWarnsButDoesNotBlock() {
        // JWT 形态：不自动拒绝（避免误伤正常业务），进审批预览由人确认
        OutboundDlpGuard.DlpResult r = guard.inspect(
                "POST https://x\nAuthorization: Bearer "
                        + "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop");

        assertThat(r.blocked()).isFalse();
        assertThat(r.warnings()).anyMatch(w -> w.contains("JWT"));
    }

    @Test
    void shortValueNotRegistered() {
        SensitiveValueRegistry shortReg = new SensitiveValueRegistry(Map.of("X", "short"));
        OutboundDlpGuard shortGuard = new OutboundDlpGuard(shortReg, new SafetyProperties());

        assertThat(shortGuard.inspect("body contains short token").blocked()).isFalse();
    }

    @Test
    void switchesRollbackToPassthrough() {
        props.getDlp().setEnabled(false);
        assertThat(guard.inspect("POST https://x\n" + SECRET).blocked()).isFalse();
        props.getDlp().setEnabled(true);

        props.setEnabled(false);
        assertThat(guard.inspect("POST https://x\n" + SECRET).blocked()).isFalse();
    }

    @Test
    void blockFlagOffDowngradesToWarning() {
        props.getDlp().setBlockOutboundSecrets(false);

        OutboundDlpGuard.DlpResult r = guard.inspect("POST https://x\n" + SECRET);

        assertThat(r.blocked()).isFalse();
        assertThat(r.warnings()).anyMatch(w -> w.contains("内部凭据"));
    }
}