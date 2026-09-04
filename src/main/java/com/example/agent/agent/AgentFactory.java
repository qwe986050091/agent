package com.example.agent.agent;

import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillManager;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工厂（动态 Skill 挂载的核心）
 *
 * 每次用户请求时：
 *   1. 从 SkillManager 取出该用户已挂载的 Skills
 *   2. 从 Skills 中提取所有 Tools
 *   3. 动态构建 AiServices（注入用户的 Tools + 共享的 ChatModel）
 *   4. 调用 chat() 返回结果
 *
 * 这就是"运行时按用户重新装配 Tools"，而不是启动时一次性绑定。
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ChatModel chatModel;
    private final SkillManager skillManager;

    /** 全局共享的对话记忆存储（按 userId 隔离） */
    private final Map<String, ChatMemory> memoryStore = new ConcurrentHashMap<>();

    public AgentFactory(ChatModel chatModel, SkillManager skillManager) {
        this.chatModel = chatModel;
        this.skillManager = skillManager;
    }

    /**
     * 处理用户对话请求
     *
     * @param userId  用户 ID（用于记忆隔离 + Skill 挂载查询）
     * @param message 用户输入
     * @return Agent 的回答
     */
    public String chat(String userId, String message) {
        // 1. 获取用户已挂载的 Skills
        List<Skill> mountedSkills = skillManager.getMountedSkills(userId);

        if (mountedSkills.isEmpty()) {
            log.info("[AgentFactory] 用户 {} 未挂载任何 Skill，将以纯对话模式响应（无工具）", userId);
        } else {
            log.info("[AgentFactory] 用户 {} 已挂载 {} 个 Skill: {}",
                    userId, mountedSkills.size(),
                    mountedSkills.stream().map(Skill::name).toList());
        }

        // 2. 从 Skills 提取所有 Tools
        List<Object> allTools = new ArrayList<>();
        for (Skill skill : mountedSkills) {
            allTools.addAll(skill.tools());
        }

        // 3. 动态构建 AiServices（注入用户专属的 Tools）
        SimpleAgent agent = AiServices.builder(SimpleAgent.class)
                .chatModel(chatModel)
                .tools(allTools.isEmpty() ? new Object[0] : allTools.toArray())
                .chatMemoryProvider(memoryIdObj -> {
                    String memoryId = memoryIdObj == null ? "default" : memoryIdObj.toString();
                    return memoryStore.computeIfAbsent(
                            memoryId,
                            id -> MessageWindowChatMemory.withMaxMessages(20)
                    );
                })
                .build();

        // 4. 调用
        return agent.chat(userId, message);
    }
}
