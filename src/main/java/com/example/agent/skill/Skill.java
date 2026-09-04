package com.example.agent.skill;

import java.util.List;

/**
 * Skill（技能包）= 一组相关 Tools + 领域 System Prompt
 *
 * 核心设计：
 *   - systemPrompt() 告诉 LLM "这个技能该怎么用、按什么流程做"
 *   - tools()        提供该技能需要的工具
 *
 * 运行时：AgentFactory 把所有已挂载 Skill 的 systemPrompt 拼到主 Agent 的系统提示里，
 * 把所有 tools 注册给 LLM。LLM 在 ReAct 循环里会按 systemPrompt 的流程规则自动编排工具调用。
 */
public interface Skill {

    /** 技能唯一标识（如 "calculator", "datetime"） */
    String name();

    /** 短描述：给用户/LLM 看，说明这个技能是干什么的 */
    String shortDescription();

    /** 详细描述：可选，展示用 */
    String detailedDescription();

    /**
     * 系统提示词片段：定义该技能的使用规则和流程。
     * 例如："当用户要生成天气报告时，必须先调用 getWeather 再调用 getCurrentTime，最后生成文档"
     */
    String systemPrompt();

    /** 该技能包含的 Tool 对象列表（@Tool 注解的类实例） */
    List<Object> tools();
}
