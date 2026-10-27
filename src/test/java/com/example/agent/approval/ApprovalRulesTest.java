package com.example.agent.approval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ApprovalRules} 单测（P1 参数级权限规则）。
 *
 * 覆盖：allow / deny / confirm 三种动作、首个命中生效、正则全匹配语义、
 * 非法动作与非法正则的安全降级、技能拒绝名单大小写不敏感。
 */
class ApprovalRulesTest {

    private static ApprovalProperties.Rule rule(String tool, String regex, String action) {
        ApprovalProperties.Rule rule = new ApprovalProperties.Rule();
        rule.setTool(tool);
        rule.setArgsRegex(regex);
        rule.setAction(action);
        return rule;
    }

    private static ApprovalRules rules(ApprovalProperties.Rule... rules) {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setRules(new ArrayList<>(List.of(rules)));
        return new ApprovalRules(properties);
    }

    @Test
    void allowRuleMatchesWholeArguments() {
        ApprovalRules rules = rules(rule("fetchWebPage", "https://api\\.trusted\\.com/.*", "allow"));

        assertThat(rules.evaluate("fetchWebPage", "https://api.trusted.com/doc"))
                .isEqualTo(ApprovalRules.Action.ALLOW);
        // 全匹配：协议/主机不一致时不命中
        assertThat(rules.evaluate("fetchWebPage", "http://api.trusted.com/doc")).isNull();
        // 其他工具不命中
        assertThat(rules.evaluate("sendHttpRequest", "https://api.trusted.com/doc")).isNull();
    }

    @Test
    void denyRuleBlocksInternalAddresses() {
        ApprovalRules rules = rules(rule("sendHttpRequest", ".*://(127\\.0\\.0\\.1|localhost).*", "deny"));

        assertThat(rules.evaluate("sendHttpRequest", "GET http://127.0.0.1:8080/admin"))
                .isEqualTo(ApprovalRules.Action.DENY);
        assertThat(rules.evaluate("sendHttpRequest", "GET https://example.com"))
                .isNull();
    }

    @Test
    void confirmRuleWithoutRegexMatchesAnyArguments() {
        ApprovalRules rules = rules(rule("executePython", null, "confirm"));

        assertThat(rules.evaluate("executePython", "print(1)"))
                .isEqualTo(ApprovalRules.Action.CONFIRM);
        assertThat(rules.evaluate("executePython", ""))
                .isEqualTo(ApprovalRules.Action.CONFIRM);
    }

    @Test
    void firstMatchingRuleWins() {
        ApprovalRules rules = rules(
                rule("fetchWebPage", "https://api\\.trusted\\.com/.*", "allow"),
                rule("fetchWebPage", ".*", "deny"));

        assertThat(rules.evaluate("fetchWebPage", "https://api.trusted.com/doc"))
                .isEqualTo(ApprovalRules.Action.ALLOW);
        assertThat(rules.evaluate("fetchWebPage", "https://evil.example.com"))
                .isEqualTo(ApprovalRules.Action.DENY);
    }

    @Test
    void invalidActionOrRegexIsSkipped() {
        ApprovalRules rules = rules(
                rule("toolA", ".*", "block"),        // 非法动作
                rule("toolB", "[", "deny"),          // 非法正则
                rule("toolC", ".*", "deny"));        // 合法

        assertThat(rules.ruleCount()).isEqualTo(1);
        assertThat(rules.evaluate("toolA", "x")).isNull();
        assertThat(rules.evaluate("toolB", "x")).isNull();
        assertThat(rules.evaluate("toolC", "x")).isEqualTo(ApprovalRules.Action.DENY);
    }

    @Test
    void nullToolIsIgnored() {
        ApprovalRules rules = rules(rule(null, ".*", "deny"));

        assertThat(rules.ruleCount()).isZero();
        assertThat(rules.evaluate("toolA", "x")).isNull();
    }

    @Test
    void deniedSkillsMatchIgnoringCase() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setDeniedSkills(List.of("code-runner", "  HTTP-API "));
        ApprovalRules rules = new ApprovalRules(properties);

        assertThat(rules.isSkillDenied("code-runner")).isTrue();
        assertThat(rules.isSkillDenied("CODE-RUNNER")).isTrue();
        assertThat(rules.isSkillDenied("http-api")).isTrue();
        assertThat(rules.isSkillDenied("calculator")).isFalse();
        assertThat(rules.isSkillDenied(null)).isFalse();
    }
}
