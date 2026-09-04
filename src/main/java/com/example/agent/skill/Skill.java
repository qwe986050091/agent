package com.example.agent.skill;

import java.util.List;

/**
 * Skill（技能包）= 一组相关 Tools + 领域 Prompt + 使用说明
 *
 * 与 Tool 的区别：
 *   - Tool  是原子能力（单个 @Tool 方法）
 *   - Skill 是能力打包（多个 Tool + 该领域的 System Prompt 片段）
 *
 * 动态挂载流程：
 *   用户挂载 Skill → SkillManager 记录 user→skill 关系
 *   → AgentFactory 按用户取出已挂载 Skills → 提取所有 Tools → 动态构建 AiServices
 */
public interface Skill {

    /** 技能唯一标识（如 "calculator", "datetime"） */
    String name();

    /** 短描述：初始化时给 LLM 看，用于选择该技能 */
    String shortDescription();

    /** 详细描述：LLM 选中该技能后，注入到上下文中 */
    String detailedDescription();

    /** 该技能包含的 Tool 对象列表（@Tool 注解的类实例） */
    List<Object> tools();
}
