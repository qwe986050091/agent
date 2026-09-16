package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HybridContentRetriever#composeFinalOrder} 契约单测（P1-9.3）：
 * 融合顺序里文档可能被大量对话片段包围/靠后，但最终名额中文档必须按序保留，
 * 对话只能补文档用不满的空位。
 */
class HybridComposeOrderTest {

    private final Set<String> docs = Set.of("d1", "d2");

    private List<String> compose(List<String> ordered, int limit) {
        return HybridContentRetriever.composeFinalOrder(ordered, docs::contains, limit);
    }

    @Test
    void docsKeepSlotsEvenWhenRankedAfterConversations() {
        // 融合顺序：3 条对话在前，2 个文档靠后；limit=4 时两个文档都必须保留
        List<String> out = compose(List.of("c1", "c2", "c3", "d1", "c4", "d2"), 4);
        assertEquals(List.of("d1", "d2", "c1", "c2"), out);
    }

    @Test
    void conversationsFillWhenFewDocs() {
        List<String> out = compose(List.of("c1", "d1", "c2", "c3"), 4);
        assertEquals(List.of("d1", "c1", "c2", "c3"), out);
    }

    @Test
    void noDocsAllConversations() {
        List<String> out = compose(List.of("c1", "c2", "c3"), 2);
        assertEquals(List.of("c1", "c2"), out);
    }

    @Test
    void limitOneStillPicksTheDocument() {
        List<String> out = compose(List.of("c1", "c2", "d1", "c3"), 1);
        assertEquals(List.of("d1"), out);
    }

    @Test
    void orderAmongDocsFollowsFusionRank() {
        List<String> out = compose(List.of("d2", "c1", "d1"), 3);
        assertEquals(List.of("d2", "d1", "c1"), out, "文档之间保持融合顺序");
        assertTrue(out.size() <= 3);
    }

    @Test
    void degenerateInputs() {
        assertEquals(List.of(), compose(List.of(), 4));
        assertEquals(List.of(), compose(List.of("d1"), 0));
    }
}
