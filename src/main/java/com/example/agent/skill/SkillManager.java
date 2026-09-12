package com.example.agent.skill;

import com.example.agent.tools.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Skill 管理器（无状态版）
 *
 * 职责：
 *   1. 持有平台<b>内置 Skill</b> 目录（Spring 注入的 Skill Bean，代码为权威，如 calculator/datetime）
 *   2. 每次对话请求时，按 backend 传入的已挂载 Skill 定义（SkillDefinition 列表）解析出本次生效的 Skill：
 *      - 名称命中内置 Skill → 直接使用内置实例（其 systemPrompt/tools 以代码为准）
 *      - 其余为用户自定义 Skill → 按 toolNames 从 {@link ToolRegistry} 解析工具，构建 {@link ConfigurableSkill}
 *
 * 无状态化说明：自定义 Skill 元数据与用户挂载关系统一由 backend 的 MySQL 为权威来源，
 * 每次请求随对话 payload 下发；agent 不再保存任何跨请求的 Skill/挂载状态，
 * 重启不丢失、多实例一致。
 */
@Component
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    /** 内置 Skill 目录：skillName -> Skill（启动时由 Spring 注入的 Skill Bean 填充） */
    private final Map<String, Skill> builtinSkills = new LinkedHashMap<>();

    private final ToolRegistry toolRegistry;

    public SkillManager(List<Skill> builtinSkillBeans, ToolRegistry toolRegistry,
                        com.example.agent.mcp.McpClientManager mcpClientManager) {
        this.toolRegistry = toolRegistry;
        for (Skill skill : builtinSkillBeans) {
            builtinSkills.put(skill.name(), skill);
        }
        // MCP 动态内置技能（mcp:{server}）；无配置时为空，行为零变化
        for (Skill mcpSkill : mcpClientManager.skills()) {
            builtinSkills.put(mcpSkill.name(), mcpSkill);
        }
    }

    @PostConstruct
    public void init() {
        log.info("[SkillManager] 启动完成，内置 Skill 目录: {}（自定义 Skill/挂载关系由 backend 每次请求下发）",
                builtinSkills.keySet());
    }

    // ==================== 内置 Skill 目录 ====================

    /** 内置 Skill 名称集合 */
    public Set<String> builtinSkillNames() {
        return builtinSkills.keySet();
    }

    /** 内置 Skill 列表（平台能力展示用） */
    public Collection<Skill> listBuiltin() {
        return Collections.unmodifiableCollection(builtinSkills.values());
    }

    /** 按名称获取内置 Skill */
    public Skill getBuiltin(String skillName) {
        return builtinSkills.get(skillName);
    }

    // ==================== 请求级 Skill 解析 ====================

    /**
     * 按 backend 下发的已挂载 Skill 定义，解析出本次请求生效的 Skill 列表。
     *
     * @param definitions backend 持久化的 Skill 定义（内置技能可只填 name；自定义技能需完整字段）
     * @return 本次装配给 Agent 的 Skill（顺序与入参一致，去重）
     * @throws IllegalArgumentException 自定义 Skill 引用了不存在的 Tool 名称时抛出（数据不一致需暴露）
     */
    public List<Skill> resolveSkills(List<SkillDefinition> definitions) {
        List<Skill> result = new ArrayList<>();
        if (definitions == null) {
            return result;
        }
        for (SkillDefinition def : definitions) {
            if (def == null || def.getName() == null || def.getName().isBlank()) {
                continue;
            }
            Skill builtin = builtinSkills.get(def.getName());
            if (builtin != null) {
                // 内置技能：以代码中的定义为准（backend 元数据行可能只有名称）
                result.add(builtin);
                continue;
            }
            result.add(buildConfigurable(def));
        }
        return result;
    }

    /** 从 JSON 定义构建自定义 Skill：按 toolNames 从 ToolRegistry 获取 Tool 实例 */
    private ConfigurableSkill buildConfigurable(SkillDefinition def) {
        List<Object> skillTools = new ArrayList<>();
        if (def.getToolNames() != null) {
            for (String toolName : def.getToolNames()) {
                Object tool = toolRegistry.getTool(toolName);
                if (tool == null) {
                    throw new IllegalArgumentException(
                            "Skill「" + def.getName() + "」引用的 Tool 不存在: " + toolName
                                    + "，可用 Tool: " + toolRegistry.getAllToolNames());
                }
                skillTools.add(tool);
            }
        }
        return new ConfigurableSkill(def, skillTools);
    }
}
