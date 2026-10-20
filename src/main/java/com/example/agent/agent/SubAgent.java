package com.example.agent.agent;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.UserMessage;

import java.util.List;

/**
 * 子代理（SubAgent）—— 拥有独立推理循环的 LangChain4j 声明式 AiService（Agent-as-Tool 模式）。
 *
 * 与主 {@link SimpleAgent} 的区别：
 *   - 无 {@code @MemoryId}、不挂 chatMemory：子代理按「单次委派」即用即弃，不跨请求保留上下文，
 *     记忆统一由主 Agent 持有（主 Agent 在委派时把必要背景写进 task）。
 *   - 它有自己的 System Message 与私有工具集，内部跑自己的多轮 ReAct 循环，
 *     对主 Agent 而言它只是一个普通工具。
 *
 * 返回值用 {@link Result} 而非 String：委派事件需要统计子代理的 token 用量
 * （计入整轮成本 + 挂到父子 Agent 调用树上）。
 *
 * 入参与 {@link SimpleAgent} 一致：{@code @UserMessage List<Content>}（纯文本时为单个 TextContent）。
 */
public interface SubAgent {

    Result<String> chat(@UserMessage List<Content> contents);
}
