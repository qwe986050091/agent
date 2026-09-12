package com.example.agent.tools.web;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 联网搜索工具（固定搜索源主机、参数仅查询词，低风险 → 自动放行，不需人工确认）。
 * 仅当 agent.tools.web.enabled=true（默认）时注册。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private final WebSearchService webSearchService;

    public WebSearchTools(WebSearchService webSearchService) {
        this.webSearchService = webSearchService;
    }

    @Tool("""
            联网搜索：当需要最新信息（新闻、天气、实时数据、近期事件、你不确定的事实）时使用。
            输入查询关键词，返回搜索引擎的多条结果（标题、URL、摘要）。
            参数 query 为搜索词；maxResults 可选（默认 5，最大 10）。
            """)
    public String webSearch(String query, Integer maxResults) {
        log.info("[Tool] webSearch(query={}, maxResults={})", query, maxResults);
        SearchResults results = webSearchService.search(query, maxResults);
        return webSearchService.render(results);
    }
}
