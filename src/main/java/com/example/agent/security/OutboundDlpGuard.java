package com.example.agent.security;

import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.observability.model.SafetyTraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 出站 DLP 闸门（P4-17 §6.3）：通用 HTTP 工具发包前的最后一道独立检查，
 * 不依赖模型判断——即使注入成功诱导模型把凭据塞进请求，也会在这里被拦下。
 *
 * <p>两级处置：</p>
 * <ol>
 *   <li><b>登记凭据精确命中</b>（原文/base64/urlencode）：直接拒绝，不进入人工审批
 *       （拦截文案/审计只写键名，绝不回显值）；</li>
 *   <li><b>高危模式</b>（sk- / AKIA / JWT / PEM 头 / 内网 IP）：不自动拒绝，
 *       在审批预览中追加警告行，由人确认（避免误伤正常业务报文）。</li>
 * </ol>
 *
 * <p>沙箱已 PrivateNetwork 断网、fetchWebPage 仅 GET URL，本条闸门覆盖的是
 * 唯一能携带任意请求体外发的通道（sendHttpRequest）。</p>
 */
@Component
public class OutboundDlpGuard {

    private static final Logger log = LoggerFactory.getLogger(OutboundDlpGuard.class);

    private record RiskPattern(String description, Pattern pattern) {
    }

    /** 高危模式（仅告警不自动拦截）：常见凭据形态与内网地址 */
    private static final List<RiskPattern> RISK_PATTERNS = List.of(
            new RiskPattern("疑似 API 密钥（sk- 前缀）", Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{16,}")),
            new RiskPattern("疑似 AWS Access Key（AKIA 前缀）", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new RiskPattern("疑似 JWT（三段式 eyJ…）",
                    Pattern.compile("\\beyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}")),
            new RiskPattern("疑似 PEM 私钥/证书", Pattern.compile("-----BEGIN [A-Z ]*(PRIVATE KEY|CERTIFICATE)-----")),
            new RiskPattern("内网地址（10./192.168./172.16-31.）",
                    Pattern.compile("\\b(10\\.[0-9]{1,3}|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.)")),
            new RiskPattern("疑似应用内部 token 形态", Pattern.compile("\\binternal[_\\-]?token\\b")));

    private final SensitiveValueRegistry registry;
    private final SafetyProperties properties;

    public OutboundDlpGuard(SensitiveValueRegistry registry, SafetyProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /** 出站检查结果：blocked=直接拒绝；warnings=审批预览警告行（可为空） */
    public record DlpResult(boolean blocked, String blockReason, List<String> warnings) {

        static DlpResult pass() {
            return new DlpResult(false, null, List.of());
        }

        /** 审批预览可用的警告文本（无警告返回空列表） */
        public List<String> warningLines() {
            return warnings;
        }
    }

    /**
     * 检查一段即将外发的文本（调用方拼接 method + url + body）。
     * 任何内部异常兜底为放行（DLP 故障不得阻断正常工具；出站仍有审批闸门兜底）。
     */
    public DlpResult inspect(String outboundText) {
        try {
            if (!properties.isEnabled() || !properties.getDlp().isEnabled()
                    || outboundText == null || outboundText.isBlank()) {
                return DlpResult.pass();
            }

            String secretLabel = registry.detect(outboundText);
            boolean blockSecrets = properties.getDlp().isBlockOutboundSecrets();

            if (secretLabel != null && blockSecrets) {
                // 精确命中登记凭据：直接拒绝并记 outbound_http 审计（只记键名，不记值）
                log.warn("[safety] 出站请求命中登记凭据（{}），已阻止外发", secretLabel);
                recordOutboundSafety("malicious", "block", "命中登记凭据：" + secretLabel);
                return new DlpResult(true, "请求疑似包含内部凭据，已阻止外发（如需调试请先脱敏）", List.of());
            }

            List<String> warnings = new ArrayList<>();
            for (RiskPattern rp : RISK_PATTERNS) {
                if (rp.pattern().matcher(outboundText).find()) {
                    warnings.add("⚠ " + rp.description());
                }
            }
            if (secretLabel != null) {
                // block-outbound-secrets 关闭时：退化为警告
                warnings.add("⚠ 疑似包含内部凭据（" + secretLabel + "）");
                recordOutboundSafety("suspicious", "warn", "命中登记凭据：" + secretLabel);
            }
            return new DlpResult(false, null, warnings);
        } catch (Exception e) {
            log.warn("[safety] 出站 DLP 检查异常，放行（审批闸门仍生效）: {}", e.getMessage());
            return DlpResult.pass();
        }
    }

    /** 记 outbound_http 通道安全事件（无对话上下文时只记日志）；excerpt 只含键名不含值 */
    private void recordOutboundSafety(String verdict, String action, String excerpt) {
        TraceCollector collector = TraceContextHolder.current();
        if (collector == null) {
            return;
        }
        collector.recordSafety(new SafetyTraceEvent("outbound_http", 0, verdict, "dlp", action,
                null, List.of(), excerpt, 0L));
    }
}