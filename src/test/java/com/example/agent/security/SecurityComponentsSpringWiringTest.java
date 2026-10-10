package com.example.agent.security;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 装配冒烟（P4-17 上线 crash-loop 复盘，2026-10-10）：
 * SensitiveValueRegistry 有双构造器（Environment/Map），未标 @Autowired 时 Spring 回退
 * 要求无参构造器 → 启动即 BeanInstantiationException，单测（直接 new）无法发现。
 * 本测试用最小上下文真实装配 security 包全部 @Component，作为此类问题的回归防线。
 */
class SecurityComponentsSpringWiringTest {

    @Test
    void allSecurityComponentsInstantiateInSpringContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(WiringConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InjectionRuleScanner.class)).isNotNull();
                    assertThat(context.getBean(SensitiveValueRegistry.class)).isNotNull();
                    assertThat(context.getBean(OutboundDlpGuard.class)).isNotNull();
                    assertThat(context.getBean(UntrustedContentWrapper.class)).isNotNull();
                    assertThat(context.getBean(LlmInjectionClassifier.class)).isNotNull();
                    assertThat(context.getBean(SafetyProperties.class)).isNotNull();
                });
    }

    @Configuration
    @Import({InjectionRuleScanner.class, SensitiveValueRegistry.class, SafetyProperties.class,
            OutboundDlpGuard.class, UntrustedContentWrapper.class, LlmInjectionClassifier.class})
    static class WiringConfig {

        /** 装配 LlmInjectionClassifier 用的假模型（本测试只验证 Bean 可创建，不调用） */
        @Bean
        ChatModel dummyChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse chat(ChatRequest chatRequest) {
                    return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
                }
            };
        }
    }
}