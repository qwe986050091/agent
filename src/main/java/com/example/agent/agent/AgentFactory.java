package com.example.agent.agent;

import com.example.agent.observability.ObservabilityRetriever;
import com.example.agent.observability.TraceAwareStreamingChatModel;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.rag.RagService;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.skill.SkillManager;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Agent 工厂（无状态版：动态 Skill 挂载 + 动态 System Prompt + 请求级记忆）
 *
 * 每次用户请求时，由 backend（数据权威）在 payload 中下发：
 *   1. 该用户已挂载的 Skill 定义（{@link SkillDefinition} 列表）→ 解析成本次生效的 Tools + Prompt
 *   2. 持久化的对话摘要 + 近期历史消息 → 通过 {@link SummarizingChatMemory#restore} 重建请求级记忆
 *
 * 处理流程：
 *   1. SkillManager 解析本次 Skill 列表（内置技能用代码实例，自定义技能按定义动态构建）
 *   2. 拼接默认人设 + 所有 Skill 的 systemPrompt → 动态 System Message
 *   3. 从 Skills 中提取所有 Tools
 *   4. 构建请求级 AiServices（System Message + Tools + 记忆 + RAG 检索器）
 *   5. 调用 chat() 返回结果；回合结束后取出最新摘要回传 backend 落库
 *
 * agent 本身不保存任何跨请求状态：重启不丢上下文，多实例部署行为一致。
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
    private final ChatModel fallbackChatModel;
    private final StreamingChatModel streamingChatModel;
    private final StreamingChatModel fallbackStreamingChatModel;
    private final SkillManager skillManager;
    /** RAG 服务（rag.enabled=false 或降级时可能不存在） */
    private final ObjectProvider<RagService> ragServiceProvider;

    /** 工具调用最大轮次（agent.loop.max-tool-round-trips），超限后强制收敛 */
    private final int maxToolRoundTrips;

    /** 会话总结记忆参数：消息超过该条数即触发一次语义压缩 */
    private static final int MEMORY_MAX_MESSAGES = 40;
    /** 压缩后保留的最近对话条数 */
    private static final int MEMORY_KEEP_RECENT = 20;

    public AgentFactory(ChatModel chatModel,
                        ChatModel fallbackChatModel,
                        StreamingChatModel streamingChatModel,
                        StreamingChatModel fallbackStreamingChatModel,
                        SkillManager skillManager,
                        ObjectProvider<RagService> ragServiceProvider,
                        @Value("${agent.loop.max-tool-round-trips:15}") int maxToolRoundTrips) {
        this.chatModel = chatModel;
        this.fallbackChatModel = fallbackChatModel;
        this.streamingChatModel = streamingChatModel;
        this.fallbackStreamingChatModel = fallbackStreamingChatModel;
        this.skillManager = skillManager;
        this.ragServiceProvider = ragServiceProvider;
        this.maxToolRoundTrips = maxToolRoundTrips;
    }

    /**
     * 一次对话请求的全部输入状态（由 backend 从 MySQL 组装下发）。
     *
     * @param summary 持久化的历史摘要纯文本（无则 null）
     * @param history 当前消息之前的近期对话（升序）
     * @param skills  该用户已挂载的 Skill 定义（内置技能可只填 name）
     */
    public record AgentChatRequest(String userId,
                                   Long conversationId,
                                   String message,
                                   String summary,
                                   List<SummarizingChatMemory.HistoryMessage> history,
                                   List<SkillDefinition> skills) {

        /** 请求级记忆的唯一键：用户 + 会话（多会话互不串上下文；RAG 检索仍按 userId） */
        public String memoryId() {
            return userId + "::" + (conversationId == null ? 0 : conversationId);
        }
    }

    /** 同步对话结果：回复文本 + 回合后的最新摘要（供 backend 持久化） */
    public record AgentChatResult(String answer, String summary) {
    }

    /**
     * 处理用户对话请求（同步，完整返回回复与最新摘要）
     *
     * 主模型失败时自动降级备用模型重试一次（工具轮次超限不属于模型故障，直接抛出由上层收敛）。
     */
    public AgentChatResult chat(AgentChatRequest request) {
        List<Skill> mountedSkills = prepare(request);

        SummarizingChatMemory memory = restoreMemory(request);
        try {
            return doChat(request, mountedSkills, memory, chatModel);
        } catch (Exception e) {
            if (isToolLoopExceeded(e)) {
                throw e;
            }
            log.warn("[AgentFactory] 主模型调用失败，降级备用模型重试: {}", e.getMessage());
            // 标记 fallback 并清空首轮 trace（首轮可能已执行工具/触发 RAG，避免同步 JSON 重复）
            TraceCollector collector = TraceContextHolder.current();
            if (collector != null) {
                collector.prepareForFallback();
            }
            return doChat(request, mountedSkills, memory, fallbackChatModel);
        }
    }

    private AgentChatResult doChat(AgentChatRequest request, List<Skill> mountedSkills,
                                   SummarizingChatMemory memory, ChatModel model) {
        SimpleAgent agent = AiServices.builder(SimpleAgent.class)
                .chatModel(model)
                .maxToolCallingRoundTrips(maxToolRoundTrips)
                .systemMessageProvider(memoryId -> buildSystemMessage(mountedSkills))
                .tools(toolsOf(mountedSkills))
                .chatMemoryProvider(memoryId -> memory)
                .contentRetriever(retrieverFor(request.userId()))
                .build();

        String answer = agent.chat(request.memoryId(), request.message());
        return new AgentChatResult(answer == null ? "" : answer, memory.summaryText());
    }

    /**
     * 处理用户对话请求（流式），返回已装配回调的 TokenStream（使用主流式模型）。
     *
     * 注意：TokenStream.start() 是阻塞执行，调用方需在独立线程中调用：
     * <pre>
     *   executor.execute(() -> agentFactory.streamChat(...).start());
     * </pre>
     *
     * @param onToken    每收到一个 token 片段回调
     * @param onComplete 完整回复回调（参数：最终文本含工具调用后的答案、回合后最新摘要）
     * @param onError    异常回调
     */
    public TokenStream streamChat(AgentChatRequest request,
                                  Consumer<String> onToken,
                                  BiConsumer<String, String> onComplete,
                                  Consumer<Throwable> onError) {
        return streamChat(request, streamingChatModel, onToken, onComplete, onError);
    }

    /**
     * 使用指定流式模型装配 TokenStream（备用模型降级重试用）。
     */
    public TokenStream streamChat(AgentChatRequest request,
                                  StreamingChatModel model,
                                  Consumer<String> onToken,
                                  BiConsumer<String, String> onComplete,
                                  Consumer<Throwable> onError) {
        List<Skill> mountedSkills = prepare(request);

        SummarizingChatMemory memory = restoreMemory(request);
        // 流式回调在 HTTP 客户端线程执行，ThreadLocal 采集器不可见：
        // 用请求级装饰器显式绑定当前 collector（采集工具往返/token），能力与元数据透传
        TraceCollector collector = TraceContextHolder.current();
        StreamingChatModel effectiveModel = collector == null
                ? model : new TraceAwareStreamingChatModel(model, collector);
        StreamingSimpleAgent agent = AiServices.builder(StreamingSimpleAgent.class)
                .streamingChatModel(effectiveModel)
                .maxToolCallingRoundTrips(maxToolRoundTrips)
                .systemMessageProvider(memoryId -> buildSystemMessage(mountedSkills))
                .tools(toolsOf(mountedSkills))
                .chatMemoryProvider(memoryId -> memory)
                .contentRetriever(retrieverFor(request.userId()))
                .build();

        return agent.chat(request.memoryId(), request.message())
                .onPartialResponse(onToken)
                .onCompleteResponse(response -> {
                    String text = response.aiMessage().text();
                    String answer = text == null ? "" : text;
                    onComplete.accept(answer, memory.summaryText());
                })
                .onError(onError);
    }

    /** 判断异常是否为 LangChain4j 工具调用轮次超限（消息形如 "exceeded ... tool calling round trips"） */
    public static boolean isToolLoopExceeded(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        return msg.contains("round trips") || msg.contains("maxToolCallingRoundTrips");
    }

    /**
     * 使用备用流式模型装配 TokenStream（主模型流式失败且未开始输出时降级重试）。
     */
    public TokenStream streamChatFallback(AgentChatRequest request,
                                          Consumer<String> onToken,
                                          BiConsumer<String, String> onComplete,
                                          Consumer<Throwable> onError) {
        return streamChat(request, fallbackStreamingChatModel, onToken, onComplete, onError);
    }

    /**
     * 请求前置准备：解析本次生效的 Skills 并打印日志
     */
    private List<Skill> prepare(AgentChatRequest request) {
        List<Skill> mountedSkills = skillManager.resolveSkills(request.skills());
        if (mountedSkills.isEmpty()) {
            log.info("[AgentFactory] 用户 {} 未挂载任何 Skill，将以纯对话模式响应（无工具）", request.userId());
        } else {
            log.info("[AgentFactory] 用户 {} 本次装配 {} 个 Skill: {}",
                    request.userId(), mountedSkills.size(),
                    mountedSkills.stream().map(Skill::name).toList());
        }
        return mountedSkills;
    }

    /** 按 backend 下发的摘要 + 历史重建请求级记忆（记忆键=用户+会话；用完即弃，不跨请求保留） */
    private SummarizingChatMemory restoreMemory(AgentChatRequest request) {
        return SummarizingChatMemory.restore(
                request.memoryId(), chatModel,
                MEMORY_MAX_MESSAGES, MEMORY_KEEP_RECENT,
                request.summary(), request.history());
    }

    /** 从 Skills 提取所有 Tools */
    private Object[] toolsOf(List<Skill> mountedSkills) {
        List<Object> allTools = new ArrayList<>();
        for (Skill skill : mountedSkills) {
            allTools.addAll(skill.tools());
        }
        return allTools.isEmpty() ? new Object[0] : allTools.toArray();
    }

    /**
     * 获取该用户的 RAG 检索器（可观测装饰器包装：采集召回片段与相似度发往当前 trace）。
     * RAG 未启用 / 模型加载失败降级时，返回不召回任何内容的空检索器（对话退化为纯记忆模式），
     * 空检索器同样被观测为 miss。
     */
    private ContentRetriever retrieverFor(String userId) {
        RagService ragService = ragServiceProvider == null ? null : ragServiceProvider.getIfAvailable();
        if (ragService == null || !ragService.available()) {
            return new ObservabilityRetriever(query -> List.of());
        }
        return new ObservabilityRetriever(ragService.retrieverOf(userId));
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
