package com.example.agent.rag;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 关键词切词（两种关键词实现共用，保证对比实验口径一致）。
 */
final class KeywordTerms {

    /** 英文/数字串（允许内部 - _ 连接），长度 >=2 保留，如 Q3、REF-2026-0001 */
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*");
    /** 连续中文片段，内部按 bigram 切 */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    /** 切词数量上限，避免长 query 撑出过多条件 */
    private static final int MAX_TERMS = 20;

    private KeywordTerms() {
    }

    /**
     * query → 关键词集合：英文/数字整串（含 - _）+ 中文 bigram，去重，最多 20 个。
     */
    static List<String> build(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        Matcher ascii = ASCII_TOKEN.matcher(query);
        while (ascii.find() && terms.size() < MAX_TERMS) {
            String t = ascii.group();
            if (t.length() >= 2) {
                terms.add(t);
            }
        }
        Matcher cjk = CJK_RUN.matcher(query);
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i < run.length() - 1 && terms.size() < MAX_TERMS; i++) {
                terms.add(run.substring(i, i + 2));
            }
        }
        return List.copyOf(terms);
    }
}
