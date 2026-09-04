package com.example.agent.skill;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill 管理器（内存版，最小可用实现）
 *
 * 职责：
 *   1. 维护平台所有已注册的 Skill（skillName -> Skill）
 *   2. 维护用户-Skill 挂载关系（userId -> Set<skillName>）
 *   3. 提供注册/注销/挂载/卸载/查询接口
 *
 * 注意：这是内存版，重启后数据丢失。
 * 生产环境应替换为数据库存储（Skill 元数据 + 用户挂载关系表）。
 */
@Component
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    /** 平台所有已注册的 Skill */
    private final Map<String, Skill> skillRegistry = new ConcurrentHashMap<>();

    /** 用户-Skill 挂载关系：userId -> 已挂载的 skillName 集合 */
    private final Map<String, Set<String>> userSkillMounts = new ConcurrentHashMap<>();

    /**
     * Spring 启动时自动注入所有 Skill 实现类并注册
     * （这就是"自动注入" → 未来改成 HTTP/RPC 获取用户挂载 Skill 的替换点）
     */
    private final List<Skill> initialSkills;

    public SkillManager(List<Skill> initialSkills) {
        this.initialSkills = initialSkills;
    }

    @PostConstruct
    public void init() {
        for (Skill skill : initialSkills) {
            register(skill);
        }
        log.info("[SkillManager] 启动完成，已注册 {} 个 Skill: {}",
                skillRegistry.size(), skillRegistry.keySet());
    }

    // ==================== Skill 注册 ====================

    /** 注册一个 Skill（用户上传/平台新增时调用） */
    public void register(Skill skill) {
        skillRegistry.put(skill.name(), skill);
        log.info("[SkillManager] 注册 Skill: {}", skill.name());
    }

    /** 注销一个 Skill */
    public void unregister(String skillName) {
        skillRegistry.remove(skillName);
        log.info("[SkillManager] 注销 Skill: {}", skillName);
    }

    /** 获取平台所有 Skill */
    public Collection<Skill> listAll() {
        return skillRegistry.values();
    }

    /** 按名称获取 Skill */
    public Skill getSkill(String skillName) {
        return skillRegistry.get(skillName);
    }

    // ==================== 用户-Skill 挂载 ====================

    /** 用户挂载一个 Skill */
    public boolean mountSkill(String userId, String skillName) {
        if (!skillRegistry.containsKey(skillName)) {
            log.warn("[SkillManager] 挂载失败，Skill 不存在: {}", skillName);
            return false;
        }
        userSkillMounts
                .computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(skillName);
        log.info("[SkillManager] 用户 {} 挂载 Skill: {}", userId, skillName);
        return true;
    }

    /** 用户卸载一个 Skill */
    public boolean unmountSkill(String userId, String skillName) {
        Set<String> mounted = userSkillMounts.get(userId);
        if (mounted == null || !mounted.remove(skillName)) {
            log.warn("[SkillManager] 卸载失败，用户 {} 未挂载 Skill: {}", userId, skillName);
            return false;
        }
        log.info("[SkillManager] 用户 {} 卸载 Skill: {}", userId, skillName);
        return true;
    }

    /** 获取用户已挂载的所有 Skill */
    public List<Skill> getMountedSkills(String userId) {
        Set<String> mounted = userSkillMounts.get(userId);
        if (mounted == null || mounted.isEmpty()) {
            return Collections.emptyList();
        }
        return mounted.stream()
                .map(skillRegistry::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** 获取用户已挂载的所有 Skill 名称 */
    public Set<String> getMountedSkillNames(String userId) {
        return userSkillMounts.getOrDefault(userId, Collections.emptySet());
    }

    /** 获取平台所有 Skill 的短描述（用于展示可选技能列表） */
    public String allShortDescriptions() {
        return skillRegistry.values().stream()
                .map(s -> "- " + s.name() + ": " + s.shortDescription())
                .collect(Collectors.joining("\n"));
    }
}
