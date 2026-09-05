package com.example.agent.config;

import com.example.agent.observability.ReActLoggerListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j Agent 配置类
 *
 * 注意：SimpleAgent 的 Bean 已移除，改为由 AgentFactory 动态构建。
 * 原因：支持用户动态挂载 Skill —— 每次请求需要按用户的 Skill 列表动态注入 Tools。
 */
@Configuration
public class AgentConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Value("${langchain4j.open-ai.chat-model.temperature:0.7}")
    private Double temperature;

    @Value("${langchain4j.open-ai.chat-model.top-p:0.9}")
    private Double topP;

    @Value("${langchain4j.open-ai.chat-model.timeout:60s}")
    private Duration timeout;

    @Value("${langchain4j.open-ai.chat-model.log-requests:false}")
    private boolean logRequests;

    @Value("${langchain4j.open-ai.chat-model.log-responses:false}")
    private boolean logResponses;

    /**
     * 用 OpenAiChatModel.builder() 手动构造 ChatModel（LangChain4j 1.x 新接口名）
     * 所有用户共享同一个 ChatModel 实例（无状态）
     */
    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .topP(topP)
                .timeout(timeout)
                .logRequests(logRequests)
                .logResponses(logResponses)
                // ReAct 日志监听器：控制台打印每一轮 LLM 请求/响应、工具调用
                .listeners(new ReActLoggerListener())
                .build();
    }

    /**
     * 流式 ChatModel：SSE 逐 token 输出使用，所有用户共享（无状态）
     */
    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .topP(topP)
                .timeout(timeout)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
