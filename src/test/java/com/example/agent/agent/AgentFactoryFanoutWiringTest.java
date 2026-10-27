package com.example.agent.agent;

import com.example.agent.context.ContextProperties;
import com.example.agent.rag.RagService;
import com.example.agent.security.InjectionRuleScanner;
import com.example.agent.security.SafetyProperties;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.skill.SkillManager;
import com.example.agent.skill.SubAgentSkill;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fan-out 开关装配单测：验证 {@code agent.fanout.enabled} 真正决定「Supervisor 是否挂上
 * {@code delegate_parallel} 工具」，而不是只改了个配置值。
 *
 * 覆盖：开启+有子代理 → 挂上；关闭 → 不挂；无子代理 → 不挂。
 * 纯单测：直接构造 AgentFactory（不启 Spring、不连 LLM）。
 */
class AgentFactoryFanoutWiringTest {

    @SuppressWarnings("unchecked")
    private static AgentFactory factory(boolean fanoutEnabled, FanoutExecutor executor) {
        SkillManager skillManager = mock(SkillManager.class);
        ObjectProvider<RagService> ragProvider = mock(ObjectProvider.class);
        when(ragProvider.getIfAvailable()).thenReturn(null);
        IntentRouter router = mock(IntentRouter.class);
        when(router.isEnabled()).thenReturn(false);
        return new AgentFactory(
                mock(ChatModel.class), mock(ChatModel.class),
                mock(StreamingChatModel.class), mock(StreamingChatModel.class),
                skillManager, ragProvider,
                mock(InjectionRuleScanner.class), mock(SafetyProperties.class),
                new ContextProperties(),
                new com.example.agent.context.ToolResultProperties(),
                router, executor,
                fanoutEnabled, mock(AgentPlanner.class), 4, 4, 15);
    }

    private static AgentFactory.AgentChatRequest request() {
        return new AgentFactory.AgentChatRequest("u-1", 0L, "帮我并行查一下资料", null,
                List.of(), List.of(), List.of());
    }

    private static Skill specialist(String name) {
        SkillDefinition def = new SkillDefinition();
        def.setName(name);
        def.setShortDescription("研究与汇总");
        def.setSystemPrompt("你是研究员。");
        def.setType(SkillDefinition.TYPE_AGENT);
        SubAgentToolProvider provider = new SubAgentToolProvider(
                name, "委派子代理", (task, memoryId) -> "ok", 4, 4000);
        return new SubAgentSkill(def, provider, List.of());
    }

    private static boolean hasFanoutTool(AgentFactory.TurnPlan plan) {
        for (ToolProvider p : plan.toolProviders()) {
            var result = p.provideTools(new ToolProviderRequest(0L,
                    UserMessage.from(TextContent.from("hi"))));
            if (result.toolSpecificationByName(FanoutToolProvider.TOOL_NAME) != null) {
                return true;
            }
        }
        return false;
    }

    @Test
    void fanoutToolIsAttachedWhenEnabledAndSpecialistMounted() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            AgentFactory factory = factory(true, executor);

            AgentFactory.TurnPlan plan = factory.planTurn(request(), List.of(specialist("researcher")));

            assertThat(hasFanoutTool(plan)).isTrue();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void fanoutToolAbsentWhenDisabledEvenWithSpecialist() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            AgentFactory factory = factory(false, executor);

            AgentFactory.TurnPlan plan = factory.planTurn(request(), List.of(specialist("researcher")));

            assertThat(hasFanoutTool(plan)).isFalse();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void fanoutToolAbsentWhenNoSpecialistMounted() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            AgentFactory factory = factory(true, executor);

            AgentFactory.TurnPlan plan = factory.planTurn(request(), List.of());

            assertThat(hasFanoutTool(plan)).isFalse();
            assertThat(plan.toolProviders()).isEmpty();
        } finally {
            executor.shutdown();
        }
    }
}
