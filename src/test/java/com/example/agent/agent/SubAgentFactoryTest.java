package com.example.agent.agent;

import com.example.agent.rag.RagService;
import com.example.agent.security.InjectionRuleScanner;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.skill.SubAgentSkill;
import com.example.agent.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SubAgentFactory} 单测（Agent-as-Tool + 子代理 RAG 接入）：
 *   - type=agent 定义被构建为 {@link SubAgentSkill}，委派工具名 delegate_{skill}
 *   - RAG 可用时接入检索增强，委派仍正常返回子代理结论文本
 *   - RAG 关闭 / 不可用时退化为纯工具模式，行为不变
 *   - 子代理引用不存在的 Tool 时抛可读异常
 *
 * 用假 ChatModel（只回固定文本）避免真实 LLM 调用。
 */
class SubAgentFactoryTest {

    private static final String USER = "u-1";

    /** 回固定文本的假模型（不触发工具循环、不实际调用 LLM） */
    static class StubChatModel implements ChatModel {
        private final String reply;
        StubChatModel(String reply) { this.reply = reply; }
        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    private static SkillDefinition agentDef(String name, String... toolNames) {
        SkillDefinition def = new SkillDefinition();
        def.setName(name);
        def.setShortDescription("研究并汇总子任务");
        def.setSystemPrompt("你是研究员，独立完成给定子任务。");
        def.setToolNames(List.of(toolNames));
        def.setType(SkillDefinition.TYPE_AGENT);
        return def;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RagService> ragProvider(boolean available) {
        ObjectProvider<RagService> provider = mock(ObjectProvider.class);
        if (available) {
            RagService rag = mock(RagService.class);
            when(rag.available()).thenReturn(true);
            when(rag.retrieverOf(USER)).thenReturn(query -> List.of());
            when(provider.getIfAvailable()).thenReturn(rag);
        } else {
            when(provider.getIfAvailable()).thenReturn(null);
        }
        return provider;
    }

    private static SubAgentFactory factory(ToolRegistry registry,
                                           ObjectProvider<RagService> ragProvider,
                                           boolean ragEnabled) {
        return new SubAgentFactory(
                new StubChatModel("子代理结论"), new StubChatModel("备用结论"),
                registry, ragProvider, mock(InjectionRuleScanner.class),
                true, 8, 4, 4000, ragEnabled);
    }

    private static String delegate(Skill skill, String arguments) {
        ToolProviderResult result = skill.toolProvider().provideTools(
                new ToolProviderRequest(0L,
                        dev.langchain4j.data.message.UserMessage.from(
                                dev.langchain4j.data.message.TextContent.from("hi"))));
        ToolExecutor executor = result.toolExecutorByName(((SubAgentSkill) skill).delegateToolName());
        return executor.execute(ToolExecutionRequest.builder()
                .id("c1").name(((SubAgentSkill) skill).delegateToolName()).arguments(arguments).build(), 0L);
    }

    @Test
    void buildsSubAgentSkillWithDelegateToolName() {
        SubAgentFactory factory = factory(mock(ToolRegistry.class), ragProvider(false), false);

        Skill skill = factory.buildAgentSkill(agentDef("researcher"), USER);

        assertThat(skill).isInstanceOf(SubAgentSkill.class);
        assertThat(((SubAgentSkill) skill).delegateToolName()).isEqualTo("delegate_researcher");
        // 私有工具归子代理，主 Agent 不直接持有
        assertThat(skill.tools()).isEmpty();
        assertThat(skill.toolProvider()).isNotNull();
    }

    @Test
    void delegatesWithRagEnabled() {
        SubAgentFactory factory = factory(mock(ToolRegistry.class), ragProvider(true), true);
        Skill skill = factory.buildAgentSkill(agentDef("researcher"), USER);

        String answer = delegate(skill, "{\"task\":\"分析知识库里的报销政策\"}");

        assertThat(answer).isEqualTo("子代理结论");
    }

    @Test
    void delegatesWithRagDisabled() {
        SubAgentFactory factory = factory(mock(ToolRegistry.class), ragProvider(true), false);
        Skill skill = factory.buildAgentSkill(agentDef("researcher"), USER);

        // 关闭 rag-enabled：即使 RAG 可用也不接入，子代理退化为纯工具模式，委派仍正常
        assertThat(delegate(skill, "{\"task\":\"做点事\"}")).isEqualTo("子代理结论");
    }

    @Test
    void missingToolThrowsActionableError() {
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getTool("ghost")).thenReturn(null);
        when(registry.getAllToolNames()).thenReturn(Set.of("toolA"));
        SubAgentFactory factory = factory(registry, ragProvider(false), false);

        assertThatThrownBy(() -> factory.buildAgentSkill(agentDef("broken", "ghost"), USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("ghost");
    }
}
