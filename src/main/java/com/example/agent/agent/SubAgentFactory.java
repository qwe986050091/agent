package com.example.agent.agent;

import com.example.agent.observability.ObservabilityRetriever;
import com.example.agent.rag.NumberedCitationContentInjector;
import com.example.agent.rag.RagService;
import com.example.agent.rag.TextFirstRetrievalAugmentor;
import com.example.agent.security.InjectionRuleScanner;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.skill.SubAgentSkill;
import com.example.agent.tools.ToolRegistry;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 子代理工厂（Agent-as-Tool）：把一个声明为 type=agent 的技能，构建成主代理可委派的「子代理工具」。
 *
 * 与 {@link AgentFactory} 的关系：
 *   - AgentFactory 构建「主 Agent」（负责与用户对话、流式输出、记忆、RAG）；
 *   - SubAgentFactory 构建「子代理」（负责被委派的子任务，独立循环 + 私有工具，非流式、无记忆）。
 *
 * 子代理工具经 {@code Skill#toolProvider()} 交给主 Agent（与 MCP 动态工具同一扩展点），
 * 因此主 Agent 的装配流程（AgentFactory）无需任何改动即可支持多 Agent。
 *
 * 无状态：子代理实例在「一次请求解析技能」时构建，不跨请求保留；与主 Agent 一致可多实例部署。
 */
@Component
public class SubAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(SubAgentFactory.class);

    private final ChatModel chatModel;
    private final ChatModel fallbackChatModel;
    private final ToolRegistry toolRegistry;
    /** RAG 服务（rag.enabled=false 或降级时可能不存在）：为子代理提供按用户的检索增强 */
    private final ObjectProvider<RagService> ragServiceProvider;
    /** L1 注入规则引擎：RAG 片段的编号引用注入（与主 Agent 同一条链） */
    private final InjectionRuleScanner injectionRuleScanner;
    private final boolean enabled;
    private final int maxToolRoundTrips;
    private final int maxDelegationsPerTurn;
    private final int maxTaskChars;
    private final boolean ragEnabled;

    public SubAgentFactory(ChatModel chatModel,
                           ChatModel fallbackChatModel,
                           ToolRegistry toolRegistry,
                           ObjectProvider<RagService> ragServiceProvider,
                           InjectionRuleScanner injectionRuleScanner,
                           @Value("${agent.subagent.enabled:true}") boolean enabled,
                           @Value("${agent.subagent.max-tool-round-trips:8}") int maxToolRoundTrips,
                           @Value("${agent.subagent.max-delegations-per-turn:4}") int maxDelegationsPerTurn,
                           @Value("${agent.subagent.max-task-chars:4000}") int maxTaskChars,
                           @Value("${agent.subagent.rag-enabled:true}") boolean ragEnabled) {
        this.chatModel = chatModel;
        this.fallbackChatModel = fallbackChatModel;
        this.toolRegistry = toolRegistry;
        this.ragServiceProvider = ragServiceProvider;
        this.injectionRuleScanner = injectionRuleScanner;
        this.enabled = enabled;
        this.maxToolRoundTrips = maxToolRoundTrips;
        this.maxDelegationsPerTurn = maxDelegationsPerTurn;
        this.maxTaskChars = maxTaskChars;
        this.ragEnabled = ragEnabled;
    }

    /** 子代理能力是否启用；关闭时 type=agent 的技能退化为普通技能（工具直挂主 Agent） */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 把 type=agent 的技能定义构建成子代理技能。
     *
     * @param userId 会话用户（RAG 检索按用户隔离；为空时子代理不做检索）
     * @throws IllegalArgumentException 子代理引用了不存在的 Tool 名称（数据不一致需暴露）
     */
    public Skill buildAgentSkill(SkillDefinition def, String userId) {
        List<Object> subTools = resolveTools(def);
        RetrievalAugmentor augmentor = buildAugmentor(userId);
        SubAgentToolProvider.SubAgentRunner runner = buildRunner(def, subTools, augmentor);
        String description = "委派子代理「" + def.getName() + "」独立完成子任务："
                + safe(def.getShortDescription());
        SubAgentToolProvider provider = new SubAgentToolProvider(
                def.getName(), description, runner, maxDelegationsPerTurn, maxTaskChars);
        log.info("[SubAgent] 技能「{}」装配为子代理，私有工具 {} 个，RAG={}，委派工具名 {}",
                def.getName(), subTools.size(), augmentor != null, provider.toolName());
        return new SubAgentSkill(def, provider, subTools);
    }

    /**
     * 子代理的检索增强器：按用户取 RAG 检索器（可观测装饰，召回片段同样进 trace）。
     * RAG 未启用 / 降级 / 显式关闭时返回 null，子代理退化为纯工具模式。
     */
    private RetrievalAugmentor buildAugmentor(String userId) {
        if (!ragEnabled || userId == null || userId.isBlank()) {
            return null;
        }
        RagService ragService = ragServiceProvider == null ? null : ragServiceProvider.getIfAvailable();
        if (ragService == null || !ragService.available()) {
            return null;
        }
        return new TextFirstRetrievalAugmentor(
                new ObservabilityRetriever(ragService.retrieverOf(userId)),
                new NumberedCitationContentInjector(injectionRuleScanner));
    }

    /** 按 toolNames 从 ToolRegistry 解析子代理的私有工具（与 ConfigurableSkill 同规则） */
    private List<Object> resolveTools(SkillDefinition def) {
        List<Object> tools = new ArrayList<>();
        if (def.getToolNames() == null) {
            return tools;
        }
        for (String toolName : def.getToolNames()) {
            Object tool = toolRegistry.getTool(toolName);
            if (tool == null) {
                throw new IllegalArgumentException(
                        "子代理「" + def.getName() + "」引用的 Tool 不存在: " + toolName
                                + "，可用 Tool: " + toolRegistry.getAllToolNames());
            }
            tools.add(tool);
        }
        return tools;
    }

    /** 构建子代理执行体：主模型失败时降级备用模型重试一次（与主 Agent 相同策略） */
    private SubAgentToolProvider.SubAgentRunner buildRunner(SkillDefinition def, List<Object> subTools,
                                                            RetrievalAugmentor augmentor) {
        String systemPrompt = def.getSystemPrompt() == null ? "" : def.getSystemPrompt();
        // 每个技能解析时构建一次（同一请求内多次委派复用同一实例），不跨请求保留
        SubAgent primary = buildAgent(chatModel, systemPrompt, subTools, augmentor);
        SubAgent fallback = buildAgent(fallbackChatModel, systemPrompt, subTools, augmentor);
        return (task, memoryId) -> {
            try {
                return run(primary, task);
            } catch (Exception e) {
                log.warn("[SubAgent] 「{}」主模型调用失败，降级备用模型重试: {}",
                        def.getName(), e.getMessage());
                return run(fallback, task);
            }
        };
    }

    private SubAgent buildAgent(ChatModel model, String systemPrompt, List<Object> subTools,
                                RetrievalAugmentor augmentor) {
        AiServices<SubAgent> builder = AiServices.builder(SubAgent.class)
                .chatModel(model)
                .maxToolCallingRoundTrips(maxToolRoundTrips)
                .systemMessageProvider(memoryId -> systemPrompt);
        if (!subTools.isEmpty()) {
            builder.tools(subTools.toArray());
        }
        if (augmentor != null) {
            builder.retrievalAugmentor(augmentor);
        }
        return builder.build();
    }

    private String run(SubAgent agent, String task) {
        List<Content> contents = List.of(TextContent.from(task));
        String answer = agent.chat(contents);
        return answer == null ? "" : answer;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
