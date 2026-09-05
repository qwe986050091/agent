package com.example.agent.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 带"会话总结"能力的对话记忆。
 *
 * 相比 {@link dev.langchain4j.memory.chat.MessageWindowChatMemory}（窗口满了直接丢弃最老消息），
 * 本实现会在消息数超过 {@link #maxMessages} 时，把最早的一部分对话交给 LLM 浓缩成一则摘要
 * （语义压缩），并保留最近的 {@link #keepRecent} 条原文。这样即使对话很长，早期关键信息
 * 仍能通过摘要保留下来，而不是被直接截断。
 *
 * 记忆结构：
 * <pre>
 * [系统指令(若有)] [摘要 SystemMessage] [最近 keepRecent 条对话]
 * </pre>
 *
 * 摘要调用使用同步 {@link ChatModel} 完成，仅在超阈值时触发；若摘要调用失败则保留原文，不中断对话。
 */
public class SummarizingChatMemory implements ChatMemory {

    /** 摘要 SystemMessage 的引导前缀，用于让模型识别这是历史摘要而非用户当前诉求 */
    private static final String SUMMARY_PREFIX = """

            【历史对话摘要】
            以下是你和用户**之前**对话的浓缩摘要，用于保持长期上下文连贯。
            这是历史信息，请以用户的当前诉求为准。若当前问题与摘要冲突，以当前对话为准。
            """;

    /** 引导模型压缩对话的提示词 */
    private static final String SUMMARIZE_PROMPT = """
            请把下面的对话历史压缩成一段简洁、连贯的中文摘要，用于长期保持上下文。
            要求：
            - 保留关键的已交代事实、用户偏好与身份信息、未完成的任务、重要的工具调用结果
            - 逻辑清晰、语言精炼，控制在合理长度内
            - 不要编造历史中不存在的内容
            """;

    private final Object id;
    private final ChatModel summarizerModel;

    /** 超过该条数即触发一次压缩 */
    private final int maxMessages;
    /** 压缩后保留的最近对话条数 */
    private final int keepRecent;

    /** 当前保存的消息列表（含摘要 SystemMessage 与最近对话） */
    private final List<ChatMessage> messages = new ArrayList<>();

    public SummarizingChatMemory(Object id, ChatModel summarizerModel, int maxMessages, int keepRecent) {
        if (keepRecent >= maxMessages) {
            throw new IllegalArgumentException("keepRecent(" + keepRecent + ") 必须小于 maxMessages(" + maxMessages + ")，否则永远不会触发压缩");
        }
        this.id = id;
        this.summarizerModel = summarizerModel;
        this.maxMessages = maxMessages;
        this.keepRecent = keepRecent;
    }

    @Override
    public Object id() {
        return id;
    }

    @Override
    public synchronized void add(ChatMessage message) {
        messages.add(message);
        // 达到窗口上限时，把最早的一部分压成摘要，仅保留最近 keepRecent 条原文
        if (messages.size() > maxMessages) {
            compress();
        }
    }

    @Override
    public List<ChatMessage> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }

    /**
     * 语义压缩：保留系统指令，把最早未被保留的对话交给 LLM 浓缩成摘要 SystemMessage，
     * 在其后保留最近 {@link #keepRecent} 条原文。
     */
    private void compress() {
        // 拆分原有系统指令与对话消息
        List<ChatMessage> systemMessages = new ArrayList<>();
        List<ChatMessage> dialogue = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage) {
                systemMessages.add(m); // 已存在的摘要也属于系统指令，一并保留
            } else {
                dialogue.add(m);
            }
        }

        // 最早需要被压缩的部分（保留最近 keepRecent 条）
        if (dialogue.size() <= keepRecent) {
            return;
        }
        List<ChatMessage> toSummarize = new ArrayList<>(dialogue.subList(0, dialogue.size() - keepRecent));
        List<ChatMessage> keep = new ArrayList<>(dialogue.subList(dialogue.size() - keepRecent, dialogue.size()));

        String summary;
        try {
            summary = summarize(toSummarize);
        } catch (Exception e) {
            // 摘要失败不阻塞对话：保持原样，让窗口继续滑动
            return;
        }
        if (summary == null || summary.isBlank()) {
            return;
        }

        // 重组：系统指令 + 新摘要 + 最近对话
        messages.clear();
        messages.addAll(systemMessages);
        messages.add(SystemMessage.from(SUMMARY_PREFIX + "\n" + summary.trim()));
        messages.addAll(keep);
    }

    /** 调用 LLM 把消息列表压缩成一段中文摘要 */
    private String summarize(List<ChatMessage> toSummarize) {
        StringBuilder history = new StringBuilder();
        for (ChatMessage m : toSummarize) {
            history.append(roleLabel(m)).append(": ").append(textOf(m)).append("\n");
        }

        List<ChatMessage> request = new ArrayList<>();
        request.add(SystemMessage.from(SUMMARIZE_PROMPT));
        request.add(UserMessage.from(history.toString()));

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(request)
                .build();
        ChatResponse response = summarizerModel.chat(chatRequest);
        return response == null || response.aiMessage() == null ? null : response.aiMessage().text();
    }

    private String roleLabel(ChatMessage m) {
        if (m instanceof AiMessage) {
            return "助手";
        }
        if (m instanceof UserMessage) {
            return "用户";
        }
        return "工具结果";
    }

    private String textOf(ChatMessage m) {
        if (m instanceof AiMessage) {
            String t = ((AiMessage) m).text();
            return t == null ? "" : t;
        }
        if (m instanceof UserMessage) {
            UserMessage u = (UserMessage) m;
            return u.singleText() != null ? u.singleText() : String.valueOf(u.contents());
        }
        return m.toString();
    }
}