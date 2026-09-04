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
import java.util.stream.Collectors;

/**
 * Agent 工厂（动态 Skill 挂载 + 动态 System Prompt 的核心）
 *
 * 每次用户请求时：
 *   1. 从 SkillManager 取出该用户已挂载的 Skills
 *   2. 拼接默认人设 + 所有 Skill 的 systemPrompt → 作为动态 System Message
 *   3. 从 Skills 中提取所有 Tools
 *   4. 动态构建 AiServices（注入动态 System Message + 用户的 Tools + 共享的 ChatModel）
 *   5. 调用 chat() 返回结果
 *
 * 这就是"运行时按用户重新装配 Prompt + Tools"，而不是启动时一次性绑定。
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    /** 默认人设（所有用户共享） */
    private static final String DEFAULT_PERSONA = """
            你是一个聪明、友好的 AI 助手，叫「小 Agent」。
            你可以回答用户的一般性问题，也可以使用用户已挂载的技能工具。

            规则：
            - 用中文回答，语气自然、简洁、有帮助
            - 如果无法回答或工具调用失败，直接如实告知
            - 严格遵循下方【已挂载技能】中定义的流程规则
            """;

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

        // 2. 拼接动态 System Message = 默认人设 + 所有 Skill 的 systemPrompt
        String systemMessage = buildSystemMessage(mountedSkills);

        // 3. 从 Skills 提取所有 Tools
        List<Object> allTools = new ArrayList<>();
        for (Skill skill : mountedSkills) {
            allTools.addAll(skill.tools());
        }

        // 4. 动态构建 AiServices（注入动态 System Message + 用户专属的 Tools）
        SimpleAgent agent = AiServices.builder(SimpleAgent.class)
                .chatModel(chatModel)
                .systemMessageProvider(memoryId -> systemMessage)  // ★ 动态注入 System Message
                .tools(allTools.isEmpty() ? new Object[0] : allTools.toArray())
                .chatMemoryProvider(memoryIdObj -> {
                    String memoryId = memoryIdObj == null ? "default" : memoryIdObj.toString();
                    return memoryStore.computeIfAbsent(
                            memoryId,
                            id -> MessageWindowChatMemory.withMaxMessages(20)
                    );
                })
                .build();

        // 5. 调用
        return agent.chat(userId, message);
    }

    /**
     * 拼接动态 System Message
     * 默认人设 + 每个已挂载 Skill 的 systemPrompt
     */
    private String buildSystemMessage(List<Skill> mountedSkills) {
        StringBuilder sb = new StringBuilder(DEFAULT_PERSONA);

        if (!mountedSkills.isEmpty()) {
            sb.append("\n\n【已挂载技能】\n");
            for (Skill skill : mountedSkills) {
                sb.append("\n■ ").append(skill.name()).append("（").append(skill.shortDescription()).append("）\n");
                if (skill.systemPrompt() != null && !skill.systemPrompt().isBlank()) {
                    sb.append(skill.systemPrompt()).append("\n");
                }
            }
        }

        String result = sb.toString();
        log.debug("[AgentFactory] 动态 System Message:\n{}", result);
        return result;
    }
}
