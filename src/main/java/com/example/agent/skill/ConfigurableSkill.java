package com.example.agent.skill;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 从 JSON 配置创建的 Skill（用户上传的 Skill）
 *
 * 与内置 Skill（CalculatorSkill 等）的区别：
 *   - 内置 Skill：Java 类实现，Tools 直接注入
 *   - ConfigurableSkill：从 SkillDefinition 创建，Tools 通过 toolNames 从 ToolRegistry 获取
 */
public class ConfigurableSkill implements Skill {

    private final SkillDefinition definition;
    private final List<Object> tools;

    public ConfigurableSkill(SkillDefinition definition, List<Object> tools) {
        this.definition = definition;
        this.tools = tools;
    }

    @Override
    public String name() {
        return definition.getName();
    }

    @Override
    public String shortDescription() {
        return definition.getShortDescription();
    }

    @Override
    public String detailedDescription() {
        return definition.getDetailedDescription();
    }

    @Override
    public String systemPrompt() {
        return definition.getSystemPrompt();
    }

    @Override
    public List<Object> tools() {
        return tools;
    }

    @Override
    public String toString() {
        return "ConfigurableSkill{name=" + name() +
                ", tools=" + tools.stream().map(t -> t.getClass().getSimpleName()).collect(Collectors.toList()) +
                "}";
    }
}
