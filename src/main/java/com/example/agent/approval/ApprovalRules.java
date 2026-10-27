package com.example.agent.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 参数级审批规则引擎（P1 权限粒度）。
 *
 * <p>{@link ApprovalProperties} 的运行时形态：启动时把配置编译成
 * {@link Pattern}，调用时按「工具名 + 参数预览」匹配，返回 allow / deny / confirm。
 * 只做字符串级匹配，不解析工具参数结构——各工具的 argsPreview 已经是
 * 「方法名 + 关键参数」的可读形式（见各 @Tool 实现），把它当作稳定契约使用。
 */
@Component
public class ApprovalRules {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRules.class);

    /** 规则动作：允许 / 拒绝 / 强制确认 */
    public enum Action {
        ALLOW, DENY, CONFIRM
    }

    private record CompiledRule(String tool, Pattern argsPattern, Action action) {
    }

    private final List<CompiledRule> rules;
    private final Set<String> deniedSkills;

    public ApprovalRules(ApprovalProperties properties) {
        this.rules = compile(properties == null ? null : properties.getRules());
        this.deniedSkills = lowerCaseSet(properties == null ? null : properties.getDeniedSkills());
    }

    /** 匹配参数级规则；无命中返回 null（交回原有 AUTO/CONFIRM 策略） */
    public Action evaluate(String toolName, String argsPreview) {
        if (toolName == null || toolsIsEmpty()) {
            return null;
        }
        String args = argsPreview == null ? "" : argsPreview;
        for (CompiledRule rule : rules) {
            if (!rule.tool.equals(toolName)) {
                continue;
            }
            if (rule.argsPattern == null || rule.argsPattern.matcher(args).matches()) {
                return rule.action;
            }
        }
        return null;
    }

    /** 技能是否在装配期被拒绝（精确匹配，忽略大小写） */
    public boolean isSkillDenied(String skillName) {
        return skillName != null && deniedSkills.contains(skillName.trim().toLowerCase(Locale.ROOT));
    }

    public int ruleCount() {
        return rules.size();
    }

    private boolean toolsIsEmpty() {
        return rules.isEmpty();
    }

    private static List<CompiledRule> compile(List<ApprovalProperties.Rule> configured) {
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        List<CompiledRule> compiled = new ArrayList<>();
        for (ApprovalProperties.Rule rule : configured) {
            if (rule == null || rule.getTool() == null || rule.getTool().isBlank()) {
                log.warn("[Approval] 忽略缺少 tool 的规则: {}", rule);
                continue;
            }
            Action action = parseAction(rule.getAction());
            if (action == null) {
                log.warn("[Approval] 忽略非法动作的规则（tool={}, action={}），可选 allow|deny|confirm",
                        rule.getTool(), rule.getAction());
                continue;
            }
            Pattern pattern = null;
            String regex = rule.getArgsRegex();
            if (regex != null && !regex.isBlank()) {
                try {
                    pattern = Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    log.warn("[Approval] 忽略非法正则的规则（tool={}, regex={}）: {}",
                            rule.getTool(), regex, e.getMessage());
                    continue;
                }
            }
            compiled.add(new CompiledRule(rule.getTool().trim(), pattern, action));
        }
        return List.copyOf(compiled);
    }

    private static Action parseAction(String action) {
        if (action == null) {
            return null;
        }
        try {
            return Action.valueOf(action.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Set<String> lowerCaseSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
