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
 * [系统指令(若有)] [摘要 SystemMessage(至多一条)] [最近 keepRecent 条对话]
 * </pre>
 *
 * <b>无状态化说明：</b>本类不再由 agent 长期持有。每次对话请求由 backend 把持久化的摘要
 * （MySQL agent_memory 表）与近期历史（chat_message 表）传入，通过 {@link #restore} 重建
 * 请求级实例；回合结束后 backend 调 {@link #summaryText()} 取回最新摘要并落库。
 * agent 重启/多实例部署均不丢失上下文。
 *
 * 摘要调用使用同步 {@link ChatModel} 完成，仅在超阈值时触发；若摘要调用失败则保留原文，不中断对话。
 */
public class SummarizingChatMemory implements ChatMemory {

    /** 摘要 SystemMessage 的引导前缀，用于让模型识别这是历史摘要而非用户当前诉求 */
    static final String SUMMARY_PREFIX = """

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

    /** 多轮压缩时，若已有旧摘要，把它合并进新材料的引导语 */
    private static final String MERGE_OLD_SUMMARY_HINT = """
            【更早之前的历史摘要】
            %s

            【之后又发生的对话】
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

    /**
     * 按 backend 持久化的状态重建记忆（请求级实例，用完即弃）。
     *
     * @param summaryText 上次压缩产生的摘要纯文本（不含 {@link #SUMMARY_PREFIX}），null/空白表示无摘要
     * @param history     当前消息之前的近期对话（时间升序），role 仅取 user/assistant，其余忽略
     */
    public static SummarizingChatMemory restore(Object id, ChatModel summarizerModel,
                                                int maxMessages, int keepRecent,
                                                String summaryText, List<HistoryMessage> history) {
        SummarizingChatMemory memory = new SummarizingChatMemory(id, summarizerModel, maxMessages, keepRecent);
        if (summaryText != null && !summaryText.isBlank()) {
            memory.messages.add(SystemMessage.from(SUMMARY_PREFIX + "\n" + summaryText.trim()));
        }
        if (history != null) {
            for (HistoryMessage m : history) {
                if (m == null || m.content() == null || m.content().isBlank()) {
                    continue;
                }
                if ("assistant".equalsIgnoreCase(m.role())) {
                    memory.messages.add(AiMessage.from(m.content()));
                } else if ("user".equalsIgnoreCase(m.role())) {
                    memory.messages.add(UserMessage.from(m.content()));
                }
                // 其他角色（如 tool）不参与跨回合记忆
            }
        }
        return memory;
    }

    /** 历史消息（backend chat_message 表映射） */
    public record HistoryMessage(String role, String content) {}

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
     * 回合结束后取当前摘要纯文本（去掉引导前缀），供 backend 持久化；无摘要返回 null。
     */
    public synchronized String summaryText() {
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm && isSummaryMessage(sm.text())) {
                String body = stripPrefix(sm.text());
                return body.isBlank() ? null : body;
            }
        }
        return null;
    }

    /** 判断一条 SystemMessage 是否为历史摘要（前缀文本块以换行开头，需 trim 后匹配） */
    private static boolean isSummaryMessage(String text) {
        return text != null && text.trim().startsWith(SUMMARY_PREFIX.trim());
    }

    /**
     * 语义压缩：保留非摘要类系统指令（如人设），把旧摘要与最早的对话合并浓缩为
     * <b>一条</b>新的摘要 SystemMessage，其后保留最近 {@link #keepRecent} 条原文。
     * 旧摘要会作为材料喂给 LLM 合并，避免多轮压缩后摘要无限堆叠。
     */
    private void compress() {
        List<ChatMessage> systemMessages = new ArrayList<>();
        String oldSummary = null;
        List<ChatMessage> dialogue = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof SystemMessage sm) {
                if (isSummaryMessage(sm.text())) {
                    oldSummary = stripPrefix(sm.text());
                } else {
                    systemMessages.add(sm); // 人设等非摘要系统指令原样保留
                }
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
            summary = summarize(oldSummary, toSummarize);
        } catch (Exception e) {
            // 摘要失败不阻塞对话：保持原样，让窗口继续滑动
            return;
        }
        if (summary == null || summary.isBlank()) {
            return;
        }

        // 重组：非摘要系统指令 + 单条新摘要 + 最近对话
        messages.clear();
        messages.addAll(systemMessages);
        messages.add(SystemMessage.from(SUMMARY_PREFIX + "\n" + summary.trim()));
        messages.addAll(keep);
    }

    /** 调用 LLM 把旧摘要（若有）与消息列表合并压缩成一段中文摘要 */
    private String summarize(String oldSummary, List<ChatMessage> toSummarize) {
        StringBuilder history = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            history.append(MERGE_OLD_SUMMARY_HINT.formatted(oldSummary.trim()));
        }
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

    private static String stripPrefix(String text) {
        String trimmed = text.trim();
        String prefix = SUMMARY_PREFIX.trim();
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()).trim() : trimmed;
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
