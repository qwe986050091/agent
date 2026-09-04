package com.example.agent.observability;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * ReAct 日志监听器 —— 把 Agent 的每一轮思考过程以人类可读的格式打印到控制台
 *
 * 实现 ChatModelListener，监听 LLM 的请求和响应：
 *   - onRequest  : 打印「这一轮发给 LLM 的消息摘要」（用户输入、历史工具结果）
 *   - onResponse : 打印「LLM 的回复」
 *                 · 若 LLM 决定调用工具 → 打印 Action（工具名 + 参数）
 *                 · 若 LLM 给出最终答案 → 打印 Final Answer
 *
 * 启动后你会在控制台看到类似：
 *   [ReAct] >>> 用户输入: (12+8)*3/5 等于多少
 *   [ReAct] <<< Action: add(a=12.0, b=8.0)
 *   [ReAct] <<< Action: multiply(a=20.0, b=3.0)
 *   [ReAct] <<< Action: divide(a=60.0, b=5.0)
 *   [ReAct] <<< Final Answer: 结果是 12.0
 */
public class ReActLoggerListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ReActLoggerListener.class);

    /** 轮次计数器，同一个 AiService 调用可能触发多次 LLM 请求（工具调用循环） */
    private final ThreadLocal<Integer> round = ThreadLocal.withInitial(() -> 0);

    @Override
    public void onRequest(ChatModelRequestContext context) {
        round.set(round.get() + 1);
        int r = round.get();

        List<ChatMessage> messages = context.chatRequest().messages();
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ReAct Round ").append(r).append(" >>> REQUEST ==========");

        for (ChatMessage msg : messages) {
            if (msg instanceof SystemMessage sm) {
                // SystemMessage 太长，只打第一行
                String preview = sm.text().replaceAll("\\s+", " ");
                if (preview.length() > 80) preview = preview.substring(0, 80) + "...";
                sb.append("\n  [System] ").append(preview);
            } else if (msg instanceof UserMessage um) {
                sb.append("\n  [User]   ").append(um.singleText());
            } else if (msg instanceof AiMessage am) {
                if (am.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest req : am.toolExecutionRequests()) {
                        sb.append("\n  [AI->Tool] ").append(req.name()).append("(").append(req.arguments()).append(")");
                    }
                } else if (am.text() != null) {
                    sb.append("\n  [AI]     ").append(am.text());
                }
            } else if (msg instanceof ToolExecutionResultMessage ter) {
                String text = ter.text();
                if (text != null && text.length() > 100) text = text.substring(0, 100) + "...";
                sb.append("\n  [Tool->AI] ").append(ter.toolName()).append(" => ").append(text);
            }
        }
        log.info(sb.toString());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        int r = round.get();
        AiMessage ai = context.chatResponse().aiMessage();

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ReAct Round ").append(r).append(" <<< RESPONSE ==========");

        if (ai.hasToolExecutionRequests()) {
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                sb.append("\n  🎯 Action: ").append(req.name()).append("(").append(req.arguments()).append(")");
            }
            sb.append("\n  (LLM 决定调用工具，等待执行结果后继续...)");
        } else {
            sb.append("\n  ✅ Final Answer: ").append(ai.text());
        }
        log.info(sb.toString());

        // 如果是最终答案，重置轮次
        if (!ai.hasToolExecutionRequests()) {
            round.remove();
        }
    }

    @Override
    public void onError(dev.langchain4j.model.chat.listener.ChatModelErrorContext context) {
        log.error("[ReAct] ❌ LLM 调用出错: {}", context.error().getMessage());
        round.remove();
    }
}
