package com.example.agent.config;

import com.example.agent.agent.SimpleAgent;
import com.example.agent.tools.CalculatorTools;
import com.example.agent.tools.DateTimeTools;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j Agent 配置类
 * 手动构建 ChatLanguageModel Bean（避免 starter 版本差异导致接口找不到）
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

    @Value("${langchain4j.open-ai.chat-model.log-requests:true}")
    private boolean logRequests;

    @Value("${langchain4j.open-ai.chat-model.log-responses:true}")
    private boolean logResponses;

    /**
     * 用 OpenAiChatModel.builder() 手动构造 ChatModel（LangChain4j 1.x 新接口名）
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
                .build();
    }

    /**
     * 注册 SimpleAgent 的 Bean
     */
    @Bean
    public SimpleAgent simpleAgent(ChatModel chatModel,
                                   DateTimeTools dateTimeTools,
                                   CalculatorTools calculatorTools) {

        Map<String, ChatMemory> memoryStore = new ConcurrentHashMap<>();

        return AiServices.builder(SimpleAgent.class)
                .chatModel(chatModel)     // 1.x 新方法名（旧：chatLanguageModel）
                .tools(dateTimeTools, calculatorTools)
                .chatMemoryProvider(memoryIdObj -> {
                    // 1.x 里 memoryId 泛型是 Object，转成 String 作为 Map key
                    String memoryId = memoryIdObj == null ? "default" : memoryIdObj.toString();
                    return memoryStore.computeIfAbsent(
                            memoryId,
                            id -> MessageWindowChatMemory.withMaxMessages(20)
                    );
                })
                .build();
    }
}
