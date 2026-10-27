package com.example.agent.context;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolResultGovernor} 单测（P0 上下文治理）。
 *
 * 固化四条契约：
 *   1. 单条超限保留头尾 + 截断标记，长度显著下降；
 *   2. 累计超限从最旧工具结果开始清理，最新一条始终保留；
 *   3. 小结果不动，关闭开关时完全不治理；
 *   4. 原文按配置落盘（审计用），重复治理幂等。
 */
class ToolResultGovernorTest {

    private static ToolResultProperties props(int perResult, int total, int head, int tail) {
        ToolResultProperties props = new ToolResultProperties();
        props.setMaxCharsPerResult(perResult);
        props.setMaxTotalCharsPerTurn(total);
        props.setHeadChars(head);
        props.setTailChars(tail);
        return props;
    }

    private static ToolExecutionResultMessage toolResult(String id, String tool, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        return ToolExecutionResultMessage.from(id, tool, sb.toString());
    }

    @Test
    void truncatesOversizedResultKeepingHeadAndTail() {
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 1000, 20, 10));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("id-1", "fetchWebPage", 1000));

        governor.govern(messages);

        String text = ((ToolExecutionResultMessage) messages.get(0)).text();
        assertThat(text).contains(ToolResultGovernor.TRUNCATED_MARK);
        assertThat(text).startsWith("abcdefghijklmnopqrst"); // 头 20 字符
        assertThat(text).endsWith("cdefghijkl");             // 尾 10 字符
        assertThat(text.length()).isLessThan(1000);
        assertThat(governor.truncatedCount()).isEqualTo(1);
    }

    @Test
    void keepsSmallResultsUntouched() {
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 1000, 20, 10));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("id-1", "add", 50));

        governor.govern(messages);

        assertThat(((ToolExecutionResultMessage) messages.get(0)).text()).hasSize(50);
        assertThat(governor.truncatedCount()).isZero();
        assertThat(governor.evictedCount()).isZero();
    }

    @Test
    void evictsOldestResultsWhenTotalExceedsBudget() {
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 250, 20, 10));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("id-1", "toolA", 100));
        messages.add(toolResult("id-2", "toolB", 100));
        messages.add(toolResult("id-3", "toolC", 100));

        governor.govern(messages);

        // 300 > 250：清理最旧 1 条后为 200，达标
        assertThat(((ToolExecutionResultMessage) messages.get(0)).text())
                .startsWith(ToolResultGovernor.EVICTED_PREFIX);
        assertThat(((ToolExecutionResultMessage) messages.get(1)).text()).hasSize(100);
        assertThat(((ToolExecutionResultMessage) messages.get(2)).text()).hasSize(100);
        assertThat(governor.evictedCount()).isEqualTo(1);
    }

    @Test
    void alwaysKeepsNewestToolResult() {
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 100, 20, 10));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("id-1", "toolA", 100));
        messages.add(toolResult("id-2", "toolB", 100));
        messages.add(toolResult("id-3", "toolC", 100));

        governor.govern(messages);

        assertThat(((ToolExecutionResultMessage) messages.get(0)).text())
                .startsWith(ToolResultGovernor.EVICTED_PREFIX);
        assertThat(((ToolExecutionResultMessage) messages.get(1)).text())
                .startsWith(ToolResultGovernor.EVICTED_PREFIX);
        // 最新一条保持完整（否则模型看不到当前这一步的结果）
        assertThat(((ToolExecutionResultMessage) messages.get(2)).text()).hasSize(100);
    }

    @Test
    void disabledGovernorDoesNothing() {
        ToolResultProperties props = props(100, 200, 20, 10);
        props.setEnabled(false);
        ToolResultGovernor governor = new ToolResultGovernor(props);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("id-1", "toolA", 1000));

        governor.govern(messages);

        assertThat(((ToolExecutionResultMessage) messages.get(0)).text()).hasSize(1000);
    }

    @Test
    void nonToolMessagesAreIgnored() {
        ToolResultGovernor governor = new ToolResultGovernor(props(10, 20, 5, 5));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("超长用户消息".repeat(100)));
        messages.add(AiMessage.from("超长助手消息".repeat(100)));

        governor.govern(messages);

        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AiMessage.class);
    }

    @Test
    void spillsOriginalTextToDiskWhenConfigured(@TempDir Path tempDir) throws Exception {
        ToolResultProperties props = props(100, 1000, 20, 10);
        props.setSpillDir(tempDir.toString());
        ToolResultGovernor governor = new ToolResultGovernor(props, "u-1::9");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("call-1", "fetchWebPage", 500));

        governor.govern(messages);

        try (var files = Files.list(tempDir)) {
            List<Path> spilled = files.toList();
            assertThat(spilled).hasSize(1);
            assertThat(Files.readString(spilled.get(0))).hasSize(500);
        }
    }

    @Test
    void governingTwiceIsIdempotent() {
        ToolResultGovernor governor = new ToolResultGovernor(props(100, 1000, 20, 10));
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(toolResult("id-1", "toolA", 1000));

        governor.govern(messages);
        int truncatedAfterFirst = governor.truncatedCount();
        String textAfterFirst = ((ToolExecutionResultMessage) messages.get(0)).text();

        governor.govern(messages);

        assertThat(governor.truncatedCount()).isEqualTo(truncatedAfterFirst);
        assertThat(((ToolExecutionResultMessage) messages.get(0)).text()).isEqualTo(textAfterFirst);
    }
}
