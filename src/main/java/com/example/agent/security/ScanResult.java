package com.example.agent.security;

import java.util.List;

/**
 * 一次注入规则扫描的结果（P4-17）。
 *
 * @param score        加权总分 = Σ 命中规则权重（规则级去重）
 * @param hits         命中明细（按规则表顺序，稳定可测）
 * @param zeroWidth    是否检出零宽字符混入（>3 个）
 * @param base64Found  是否检出大段 base64 载荷（≥200 字符连续）
 */
public record ScanResult(int score, List<RuleHit> hits, boolean zeroWidth, boolean base64Found) {

    public static ScanResult clean() {
        return new ScanResult(0, List.of(), false, false);
    }

    public boolean isClean() {
        return score <= 0;
    }

    /** 首条命中片段（摘要用）；无命中返回 null */
    public String topExcerpt() {
        return hits.isEmpty() ? null : hits.get(0).excerpt();
    }
}