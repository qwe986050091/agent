package com.example.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 核心 Agent 接口 —— LangChain4j 声明式 AiService
 *
 * 注意：@SystemMessage 已移除，改由 AgentFactory 通过 .systemMessageProvider() 动态注入。
 * 这样可以把用户挂载的 Skill 的 systemPrompt 动态拼接到系统提示里。
 */
public interface SimpleAgent {

    @UserMessage("{{message}}")
    String chat(@MemoryId String userId, @V("message") String userMessage);
}
