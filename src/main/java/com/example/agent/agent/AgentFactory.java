package com.example.agent.agent;

import com.example.agent.rag.RagService;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillManager;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
    private final StreamingChatModel streamingChatModel;
    private final SkillManager skillManager;
    /** RAG 服务（rag.enabled=false 或降级时可能不存在） */
    private final ObjectProvider<RagService> ragServiceProvider;

    /** 全局共享的对话记忆存储（按 userId 隔离） */
    private final Map<String, ChatMemory> memoryStore = new ConcurrentHashMap<>();

    /** 会话总结记忆参数：消息超过该条数即触发一次语义压缩 */
    private static final int MEMORY_MAX_MESSAGES = 40;
    /** 压缩后保留的最近对话条数 */
    private static final int MEMORY_KEEP_RECENT = 20;

    public AgentFactory(ChatModel chatModel, StreamingChatModel streamingChatModel,
                        SkillManager skillManager, ObjectProvider<RagService> ragServiceProvider) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.skillManager = skillManager;
        this.ragServiceProvider = ragServiceProvider;
    }

    /**
     * 处理用户对话请求（同步，完整返回回复）
     */
    public String chat(String userId, String message) {
        List<Skill> mountedSkills = prepare(userId);

        SimpleAgent agent = AiServices.builder(SimpleAgent.class)
                .chatModel(chatModel)
                .systemMessageProvider(memoryId -> buildSystemMessage(mountedSkills))
                .tools(toolsOf(mountedSkills))
                .chatMemoryProvider(this::memoryOf)
                .contentRetriever(retrieverFor(userId))
                .build();

        return agent.chat(userId, message);
    }

    /**
     * 处理用户对话请求（流式），返回已装配回调的 TokenStream。
     *
     * 注意：TokenStream.start() 是阻塞执行，调用方需在独立线程中调用：
     * <pre>
     *   executor.execute(() -> agentFactory.streamChat(...).start());
     * </pre>
     *
     * @param onToken    每收到一个 token 片段回调
     * @param onComplete 完整回复回调（最终文本，含工具调用后的最终答案）
     * @param onError    异常回调
     */
    public TokenStream streamChat(String userId, String message,
                                  Consumer<String> onToken,
                                  Consumer<String> onComplete,
                                  Consumer<Throwable> onError) {
        List<Skill> mountedSkills = prepare(userId);

        StreamingSimpleAgent agent = AiServices.builder(StreamingSimpleAgent.class)
                .streamingChatModel(streamingChatModel)
                .systemMessageProvider(memoryId -> buildSystemMessage(mountedSkills))
                .tools(toolsOf(mountedSkills))
                .chatMemoryProvider(this::memoryOf)
                .contentRetriever(retrieverFor(userId))
                .build();

        return agent.chat(userId, message)
                .onPartialResponse(onToken)
                .onCompleteResponse(response -> {
                    String text = response.aiMessage().text();
                    onComplete.accept(text == null ? "" : text);
                })
                .onError(onError);
    }

    /**
     * 请求前置准备：获取用户已挂载 Skills 并打印日志
     */
    private List<Skill> prepare(String userId) {
        List<Skill> mountedSkills = skillManager.getMountedSkills(userId);
        if (mountedSkills.isEmpty()) {
            log.info("[AgentFactory] 用户 {} 未挂载任何 Skill，将以纯对话模式响应（无工具）", userId);
        } else {
            log.info("[AgentFactory] 用户 {} 已挂载 {} 个 Skill: {}",
                    userId, mountedSkills.size(),
                    mountedSkills.stream().map(Skill::name).toList());
        }
        return mountedSkills;
    }

    /** 从 Skills 提取所有 Tools */
    private Object[] toolsOf(List<Skill> mountedSkills) {
        List<Object> allTools = new ArrayList<>();
        for (Skill skill : mountedSkills) {
            allTools.addAll(skill.tools());
        }
        return allTools.isEmpty() ? new Object[0] : allTools.toArray();
    }

    /** 按 memoryId（userId）获取/创建对话记忆（带会话总结，超长对话自动压缩为摘要） */
    private ChatMemory memoryOf(Object memoryIdObj) {
        String memoryId = memoryIdObj == null ? "default" : memoryIdObj.toString();
        return memoryStore.computeIfAbsent(
                memoryId,
                id -> new SummarizingChatMemory(id, chatModel, MEMORY_MAX_MESSAGES, MEMORY_KEEP_RECENT)
        );
    }

    /**
     * 获取该用户的 RAG 检索器。
     * RAG 未启用 / 模型加载失败降级时，返回不召回任何内容的空检索器（对话退化为纯记忆模式）。
     */
    private ContentRetriever retrieverFor(String userId) {
        RagService ragService = ragServiceProvider == null ? null : ragServiceProvider.getIfAvailable();
        if (ragService == null || !ragService.available()) {
            return query -> List.of();
        }
        return ragService.retrieverOf(userId);
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
