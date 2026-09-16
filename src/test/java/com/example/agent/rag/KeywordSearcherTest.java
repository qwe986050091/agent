package com.example.agent.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeywordSearcher#buildTerms} 切词规则单测（P1-9.3）：
 * 英文/编号整串保留、中文 bigram、去重与数量上限。
 */
class KeywordSearcherTest {

    @Test
    void keepsAsciiTokensAndCodes() {
        List<String> terms = KeywordSearcher.buildTerms("退款单编号 REF-2026-0001 怎么查 Q3");
        // 编号整体保留（专名精确命中的关键）
        assertTrue(terms.contains("REF-2026-0001"), terms.toString());
        assertTrue(terms.contains("Q3"), terms.toString());
        // 中文 bigram
        assertTrue(terms.contains("退款"), terms.toString());
        assertTrue(terms.contains("款单"), terms.toString());
    }

    @Test
    void buildsChineseBigrams() {
        List<String> terms = KeywordSearcher.buildTerms("七天无理由退款");
        assertEquals(List.of("七天", "天无", "无理", "理由", "由退", "退款"), terms);
    }

    @Test
    void blankQueryYieldsEmpty() {
        assertEquals(List.of(), KeywordSearcher.buildTerms(""));
        assertEquals(List.of(), KeywordSearcher.buildTerms("   "));
        assertEquals(List.of(), KeywordSearcher.buildTerms(null));
    }

    @Test
    void deduplicatesAndCapsTerms() {
        // "退款退款…" 产生的 bigram 去重；超长重复串不超过 MAX_TERMS
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            q.append("退款");
        }
        List<String> terms = KeywordSearcher.buildTerms(q.toString());
        assertEquals(2, terms.size(), "只有「退款」「款退」两种 bigram: " + terms);
    }

    @Test
    void capsAtTwentyTerms() {
        List<String> terms = KeywordSearcher.buildTerms("abc def ghi jkl mno pqr stu vwx yza bcd cde def efg fgh ghi hij");
        assertTrue(terms.size() <= 20, "关键词数量应受上限约束: " + terms.size());
    }
}
