package com.example.agent.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 审批规则配置（agent.approval.*，P1 权限粒度）。
 *
 * <p>两级规则：
 * <ul>
 *   <li>{@code denied-skills}：装配期拒绝。命中的技能整包不进入本轮工具列表与系统提示，
 *       模型根本看不到（等价于 Claude Code 的「deny 前置过滤」）；</li>
 *   <li>{@code rules}：调用期参数级规则。按 tool + args-regex 匹配工具参数预览，
 *       支持 allow（免确认）/ deny（直接拒绝）/ confirm（强制确认，覆盖 AUTO 与会话级授权）。</li>
 * </ul>
 *
 * <p>规则自上而下匹配，首个命中生效。args-regex 为正则全匹配（如需前缀匹配请写 {@code .*}），
 * 留空表示该工具任意参数都命中。正则非法时跳过该条并打 WARN（不因配置笔误拒绝全部调用）。
 */
@Component
@ConfigurationProperties(prefix = "agent.approval")
public class ApprovalProperties {

    /** 装配期拒绝的技能名（精确匹配，忽略大小写；空 = 不拒绝） */
    private List<String> deniedSkills = new ArrayList<>();

    /** 参数级规则（自上而下，首个命中生效） */
    private List<Rule> rules = new ArrayList<>();

    public List<String> getDeniedSkills() { return deniedSkills; }
    public void setDeniedSkills(List<String> deniedSkills) { this.deniedSkills = deniedSkills; }
    public List<Rule> getRules() { return rules; }
    public void setRules(List<Rule> rules) { this.rules = rules; }

    /** 单条参数级规则 */
    public static class Rule {

        /** 工具名（LangChain4j 方法名 / MCP 工具名） */
        private String tool;

        /** 参数预览正则（全匹配；留空 = 任意参数） */
        private String argsRegex;

        /** allow | deny | confirm */
        private String action;

        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public String getArgsRegex() { return argsRegex; }
        public void setArgsRegex(String argsRegex) { this.argsRegex = argsRegex; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
    }
}
