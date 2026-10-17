package com.example.agent.skill;

import com.example.agent.agent.SubAgentToolProvider;
import dev.langchain4j.service.tool.ToolProvider;

import java.util.List;

/**
 * 子代理技能（Agent-as-Tool 中「子代理」这一角色）。
 *
 * 与普通 {@link ConfigurableSkill} 的区别：
 *   - {@code tools()} 返回空：子代理的私有工具不直接暴露给主 Agent；
 *   - {@code toolProvider()} 返回一个委派工具（delegate_{name}），主 Agent 调它即触发子代理；
 *   - {@code systemPrompt()} 是「往主 Agent 系统提示里拼的委派指引」，说明何时、如何委派该子代理。
 *
 * 主 Agent 侧仍是单条推理循环：它看到的只是一个普通工具，是否委派由它自己决定。
 */
public class SubAgentSkill implements Skill {

    private final SkillDefinition definition;
    private final SubAgentToolProvider provider;

    public SubAgentSkill(SkillDefinition definition, SubAgentToolProvider provider) {
        this.definition = definition;
        this.provider = provider;
    }

    /** 主 Agent 可见的委派工具名（delegate_{name}） */
    public String delegateToolName() {
        return provider.toolName();
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

    /**
     * 拼进主 Agent 系统提示的委派指引。
     * 关键是让主 Agent 明白：这是一个「独立完成子任务」的子代理，只能通过委派工具调用，
     * 简单问题不必委派，且不得臆造子代理结果。
     */
    @Override
    public String systemPrompt() {
        String toolName = provider.toolName();
        String desc = definition.getShortDescription() == null ? "独立子任务" : definition.getShortDescription();
        return """
                【子代理：%s】
                这是一个拥有独立推理循环与私有工具的子代理，你只能通过工具 %s 委派它，无法直接调用其内部工具。
                - 当任务属于「%s」范畴、需要多步操作或独立完成时，调用 %s：把完整的子任务指令
                  （目标 + 约束 + 预期产出）作为 %s 参数传入；子代理会独立完成并返回结论文本。
                - 一切以子代理返回的内容为准，不得臆造或补全它的结果；它未完成时如实告知用户。
                - 简单、一步可答的问题不必委派，直接作答更快。
                """.formatted(name(), toolName, desc, toolName, SubAgentToolProvider.TASK_PARAM);
    }

    @Override
    public List<Object> tools() {
        // 私有工具归子代理，主 Agent 不直接持有
        return List.of();
    }

    @Override
    public ToolProvider toolProvider() {
        return provider;
    }

    @Override
    public String toString() {
        return "SubAgentSkill{name=" + name() + ", tool=" + provider.toolName() + "}";
    }
}
