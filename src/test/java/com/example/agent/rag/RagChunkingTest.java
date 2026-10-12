package com.example.agent.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RagService#chunkDocument} 切块规则单测（P2-12）。
 *
 * 关注的是「引用定位依赖的不变量」：
 *   1. 短文本不切（1 块），metadata 四键齐全
 *   2. 长文本切成多块时 chunkIndex 从 0 起连续，且每块不超过 chunkSize
 *   3. 切块不丢内容（每句原文都能在某块里找到）
 *   4. 空白正文由 langchain4j 直接拒绝（抛 IllegalArgumentException，不是返回空列表）
 *
 * 实测备注：DocumentSplitters.recursive 在「整句组成的连续中文段落」这种语料形状下
 * 按句打包、**未观察到重叠**，因此本测试不断言 overlap（避免固化对 langchain4j
 * 内部策略的假设）；overlap 配置只保证「切分点落在句内时」生效。
 *
 * 纯单测：不加载 ONNX 模型、不碰向量库（切块与向量化已解耦）。
 */
class RagChunkingTest {

    private static final int CHUNK_SIZE = 200;
    private static final int OVERLAP = 60;

    @Test
    void shortTextYieldsSingleChunkWithFullMetadata() {
        List<TextSegment> chunks = RagService.chunkDocument(
                CHUNK_SIZE, OVERLAP, "u1", "doc-1", "报销制度.txt", "员工出差后 30 天内提交报销单。");

        assertThat(chunks).hasSize(1);
        TextSegment only = chunks.get(0);
        assertThat(only.text()).isEqualTo("员工出差后 30 天内提交报销单。");

        Metadata md = only.metadata();
        assertThat(md.getString(RagService.META_USER_ID)).isEqualTo("u1");
        assertThat(md.getString(RagService.META_TYPE)).isEqualTo(RagService.TYPE_DOCUMENT);
        assertThat(md.getString(RagService.META_DOC_ID)).isEqualTo("doc-1");
        assertThat(md.getInteger(RagService.META_CHUNK_INDEX)).isZero();
        assertThat(md.getString(RagService.META_SOURCE)).isEqualTo("报销制度.txt");
    }

    @Test
    void nullSourceNameFallsBackToEmptyString() {
        List<TextSegment> chunks = RagService.chunkDocument(
                CHUNK_SIZE, OVERLAP, "u1", "doc-1", null, "短文本");

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).metadata().getString(RagService.META_SOURCE)).isEmpty();
    }

    @Test
    void longTextIsSplitIntoConsecutiveIndexedChunksWithinSizeLimit() {
        String text = longChineseText(24, 30);

        List<TextSegment> chunks = RagService.chunkDocument(
                CHUNK_SIZE, OVERLAP, "u1", "doc-2", "长文档.txt", text);

        assertThat(chunks.size()).as("长文本应被切成多块").isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            TextSegment seg = chunks.get(i);
            assertThat(seg.text().length())
                    .as("第 %d 块不应超过 chunkSize", i)
                    .isLessThanOrEqualTo(CHUNK_SIZE);
            assertThat(seg.text().isBlank()).as("第 %d 块不应为空白", i).isFalse();
            // chunkIndex 必须是 0..n-1 连续——引用编号 [n] 直接取自它
            assertThat(seg.metadata().getInteger(RagService.META_CHUNK_INDEX)).isEqualTo(i);
            assertThat(seg.metadata().getString(RagService.META_DOC_ID)).isEqualTo("doc-2");
        }
    }

    @Test
    void noContentIsLostAcrossChunks() {
        String text = longChineseText(12, 26);

        List<TextSegment> chunks = RagService.chunkDocument(
                CHUNK_SIZE, OVERLAP, "u1", "doc-3", "无丢失.txt", text);
        String joined = chunks.stream().map(TextSegment::text).collect(Collectors.joining(""));

        // 逐句核对：切块只允许重复（overlap），不允许丢内容
        int sentences = 0;
        for (String sentence : text.split("。")) {
            if (sentence.isBlank()) {
                continue;
            }
            sentences++;
            assertThat(joined).as("句子丢失于切块结果: %s", sentence).contains(sentence);
        }
        assertThat(sentences).isEqualTo(12);
    }

    @Test
    void blankTextIsRejectedRatherThanSilentlyIgnored() {
        // Document.from() 对空白正文抛 IllegalArgumentException。
        // 生产路径上 indexDocument/indexFile 都在更早一层拦掉了空正文，
        // 这里固化「不会静默产出 0 块」这一契约。
        assertThatThrownBy(() -> RagService.chunkDocument(CHUNK_SIZE, OVERLAP, "u1", "doc-5", "空.txt", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");

        assertThatThrownBy(() -> RagService.chunkDocument(CHUNK_SIZE, OVERLAP, "u1", "doc-5", "空.txt", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 生成 count 个句子的中文长文本（句间以「。」分隔，段内无换行） */
    private static String longChineseText(int count, int sentenceLen) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            String head = String.format("第%02d条规则要求员工在系统内提交申请并等待审批", i);
            sb.append(head);
            int filler = Math.max(0, sentenceLen - head.length());
            sb.append("细则".repeat(filler / 2));
            sb.append("。");
        }
        return sb.toString();
    }
}