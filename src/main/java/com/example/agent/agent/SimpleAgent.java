package com.example.agent.agent;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * 核心 Agent 接口 —— LangChain4j 声明式 AiService
 *
 * 注意：
 * 1. @SystemMessage 已移除，改由 AgentFactory 通过 .systemMessageProvider() 动态注入。
 * 2. P1-8 起入参用 {@code @UserMessage List<Content>}（LC4j 1.17.2 契约：Content/List<Content>
 *    形参会被直接装配成 UserMessage 的内容块；传 String/UserMessage 对象只会被 toString）。
 *    纯文本时列表为单个 TextContent；多模态时为 TextContent + ImageContent。
 */
public interface SimpleAgent {

    String chat(@MemoryId String userId, @UserMessage List<Content> contents);
}
