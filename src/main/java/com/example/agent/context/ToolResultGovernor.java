package com.example.agent.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 工具结果治理器（P0 上下文治理，回合级）。
 *
 * <p>与 {@link ContextBudget} 分工：预算负责「请求开始前」的四段裁剪；本类负责
 * 「ReAct 循环进行中」不断追加的工具结果，防止单条或累计超限把上下文挤爆。
 *
 * <p>幂等性：已截断/已清理的消息带固定标记，重复调用不会二次处理。
 * 只处理单文本工具结果（多内容结果无法安全裁剪，跳过）。
 *
 * <p>落盘仅用于审计/排障（agent 没有文件读取工具，模型无法回读原文），
 * 因此模型侧只看到「已截断，请缩小范围或分页」的提示。
 */
public class ToolResultGovernor {

    private static final Logger log = LoggerFactory.getLogger(ToolResultGovernor.class);

    /** 截断标记（幂等判断 + 模型可见提示；public 供测试与日志解析） */
    public static final String TRUNCATED_MARK = "【工具结果已截断】";

    /** 清理占位符前缀（幂等判断；public 供测试与日志解析） */
    public static final String EVICTED_PREFIX = "[旧工具结果已清理";

    private final boolean enabled;
    private final int maxCharsPerResult;
    private final int maxTotalChars;
    private final int headChars;
    private final int tailChars;
    private final Path spillDir;
    private final String memoryId;

    /** 最近一次治理的统计（供日志/测试断言） */
    private int truncatedCount;
    private int evictedCount;

    public ToolResultGovernor(ToolResultProperties props) {
        this(props, null);
    }

    public ToolResultGovernor(ToolResultProperties props, String memoryId) {
        this.enabled = props != null && props.isEnabled();
        int perResult = props == null ? 8000 : props.getMaxCharsPerResult();
        this.maxCharsPerResult = Math.max(200, perResult);
        int total = props == null ? 24000 : props.getMaxTotalCharsPerTurn();
        this.maxTotalChars = Math.max(this.maxCharsPerResult, total);
        this.headChars = props == null ? 3000 : Math.max(0, props.getHeadChars());
        this.tailChars = props == null ? 1000 : Math.max(0, props.getTailChars());
        String dir = props == null ? null : props.getSpillDir();
        this.spillDir = dir == null || dir.isBlank() ? null : Paths.get(dir.trim());
        this.memoryId = memoryId == null || memoryId.isBlank() ? "unknown" : memoryId;
    }

    public boolean enabled() {
        return enabled;
    }

    public int truncatedCount() {
        return truncatedCount;
    }

    public int evictedCount() {
        return evictedCount;
    }

    /**
     * 就地治理消息列表中的工具结果：
     * 1) 单条超限 → 保留头尾 + 截断标记（原文按配置落盘）；
     * 2) 累计超限 → 从最旧工具结果开始替换为占位符，始终保留最新一条。
     */
    public synchronized void govern(List<ChatMessage> messages) {
        if (!enabled || messages == null || messages.isEmpty()) {
            return;
        }

        int before = totalChars(messages);
        truncateOversized(messages);
        evictOldest(messages);
        int after = totalChars(messages);

        if (before != after) {
            log.info("[ToolResult] 治理完成：memoryId={}, {} -> {} 字符，截断 {} 条，清理 {} 条",
                    memoryId, before, after, truncatedCount, evictedCount);
        }
    }

    private void truncateOversized(List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolExecutionResultMessage result)) {
                continue;
            }
            String text = safeText(result);
            if (text.length() <= maxCharsPerResult || text.contains(TRUNCATED_MARK)
                    || text.startsWith(EVICTED_PREFIX)) {
                continue;
            }
            Path spilled = spill(result, text);
            String replacement = buildTruncatedText(text, spilled != null);
            messages.set(i, replaceText(result, replacement));
            truncatedCount++;
            log.warn("[ToolResult] 单条结果超限已截断：memoryId={}, tool={}, id={}, {} -> {} 字符{}",
                    memoryId, result.toolName(), result.id(), text.length(), replacement.length(),
                    spilled == null ? "" : "，原文已落盘 " + spilled);
        }
    }

    private void evictOldest(List<ChatMessage> messages) {
        int total = totalChars(messages);
        if (total <= maxTotalChars) {
            return;
        }
        int newestToolIndex = lastToolResultIndex(messages);
        for (int i = 0; i < newestToolIndex && total > maxTotalChars; i++) {
            if (!(messages.get(i) instanceof ToolExecutionResultMessage result)) {
                continue;
            }
            String text = safeText(result);
            if (text.isEmpty() || text.startsWith(EVICTED_PREFIX)) {
                // 无文本/非纯文本结果不替换（多内容结果无法安全降级为文本占位符）
                continue;
            }
            String placeholder = EVICTED_PREFIX + "（tool=" + result.toolName()
                    + "，原因=上下文预算）]";
            messages.set(i, replaceText(result, placeholder));
            total -= Math.max(0, text.length() - placeholder.length());
            evictedCount++;
            log.warn("[ToolResult] 累计超限，清理旧工具结果：memoryId={}, tool={}, id={}, 释放 {} 字符",
                    memoryId, result.toolName(), result.id(), Math.max(0, text.length() - placeholder.length()));
        }
    }

    private String buildTruncatedText(String text, boolean spilled) {
        int head = Math.min(headChars, text.length());
        int tailStart = Math.max(head, text.length() - tailChars);
        String tail = tailStart < text.length() ? text.substring(tailStart) : "";
        StringBuilder sb = new StringBuilder(head + tail.length() + 160);
        sb.append(text, 0, head);
        sb.append("\n\n").append(TRUNCATED_MARK)
                .append(" 原文共 ").append(text.length()).append(" 字，已保留开头 ")
                .append(head).append(" 字");
        if (!tail.isEmpty()) {
            sb.append(" 与结尾 ").append(tail.length()).append(" 字");
        }
        sb.append("；如需完整内容，请缩小查询范围、分页或改用更精确的参数。");
        if (spilled) {
            sb.append("（完整原文已留档）");
        }
        sb.append("\n\n").append(tail);
        return sb.toString();
    }

    /** 用相同 id/toolName 重建消息，避免破坏 LangChain4j 的 tool_call 配对 */
    private static ToolExecutionResultMessage replaceText(ToolExecutionResultMessage original, String text) {
        ToolExecutionResultMessage.Builder builder = ToolExecutionResultMessage.builder()
                .id(original.id())
                .toolName(original.toolName())
                .text(text);
        if (original.isError() != null) {
            builder.isError(original.isError());
        }
        if (original.attributes() != null && !original.attributes().isEmpty()) {
            builder.attributes(original.attributes());
        }
        return builder.build();
    }

    private Path spill(ToolExecutionResultMessage result, String text) {
        if (spillDir == null) {
            return null;
        }
        try {
            Files.createDirectories(spillDir);
            String name = safeName(memoryId) + "_" + safeName(result.toolName()) + "_"
                    + safeName(result.id()) + ".txt";
            Path file = spillDir.resolve(name);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (Exception e) {
            log.warn("[ToolResult] 原文落盘失败（不影响对话）: {}", e.getMessage());
            return null;
        }
    }

    private static String safeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() <= 60 ? cleaned : cleaned.substring(0, 60);
    }

    private static String safeText(ToolExecutionResultMessage message) {
        try {
            String text = message.text();
            return text == null ? "" : text;
        } catch (IllegalStateException e) {
            // 多内容工具结果（如多模态）无法安全裁剪
            return "";
        }
    }

    private static int totalChars(List<ChatMessage> messages) {
        int sum = 0;
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result) {
                sum += safeText(result).length();
            }
        }
        return sum;
    }

    private static int lastToolResultIndex(List<ChatMessage> messages) {
        int last = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof ToolExecutionResultMessage) {
                last = i;
            }
        }
        return last;
    }
}
