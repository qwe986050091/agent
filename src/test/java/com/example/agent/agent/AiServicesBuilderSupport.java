package com.example.agent.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

/**
 * 多模态测试用 AiServices 装配：最小内存窗口，无工具/RAG/系统提示。
 */
final class AiServicesBuilderSupport {

    private AiServicesBuilderSupport() {}

    static SimpleAgent newSyncAgent(ChatModel model) {
        return AiServices.builder(SimpleAgent.class)
                .chatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }
}
