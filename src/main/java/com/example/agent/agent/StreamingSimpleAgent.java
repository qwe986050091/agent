package com.example.agent.agent;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * 流式版 Agent 接口 —— 返回 TokenStream，逐 token 回调。
 *
 * 与 {@link SimpleAgent} 对应：一个同步返回 String，一个流式返回 TokenStream；
 * System Message / Tools / ChatMemory 同样由 AgentFactory 动态装配。
 * P1-8 起入参为 {@code @UserMessage List<Content>}（支持 TextContent + ImageContent 块）。
 */
public interface StreamingSimpleAgent {

    TokenStream chat(@MemoryId String userId, @UserMessage List<Content> contents);
}
