package com.example.agent.tools.web;

import java.util.List;

/**
 * 一次搜索的结果。
 *
 * @param provider 实际命中的 provider（bing/duckduckgo）
 * @param results  结构化结果
 * @param error    全部 provider 失败时的错误描述（results 为空）
 */
public record SearchResults(String provider, List<Item> results, String error) {

    public record Item(String title, String url, String snippet) {
    }

    public static SearchResults ok(String provider, List<Item> results) {
        return new SearchResults(provider, results, null);
    }

    public static SearchResults failed(String error) {
        return new SearchResults(null, List.of(), error);
    }
}
