package com.example.agent.tools.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 免 Key 联网搜索：按配置顺序尝试 Bing 国内版 → DuckDuckGo HTML，任一成功即返回。
 *
 * 两者均为公开 HTML 结果页解析（非官方接口），可能随页面改版失效；
 * 解析/请求失败时自动回退下一 provider，全部失败返回友好错误（不抛异常打断对话）。
 */
@Component
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final SafeHttpClient httpClient;
    private final WebToolsProperties properties;

    public WebSearchService(SafeHttpClient httpClient, WebToolsProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public SearchResults search(String query, Integer maxResults) {
        int limit = clampLimit(maxResults);
        if (query == null || query.isBlank()) {
            return SearchResults.failed("搜索关键词不能为空");
        }
        List<String> providers = properties.getSearchProviders() == null
                ? List.of() : properties.getSearchProviders();
        for (String provider : providers) {
            try {
                SearchResults results = switch (provider.trim().toLowerCase()) {
                    case "bing" -> searchBing(query.trim(), limit);
                    case "duckduckgo", "ddg" -> searchDuckDuckGo(query.trim(), limit);
                    default -> {
                        log.warn("[WebSearch] 未知搜索 provider 配置，跳过: {}", provider);
                        yield null;
                    }
                };
                if (results != null && !results.results().isEmpty()) {
                    return results;
                }
            } catch (Exception e) {
                log.warn("[WebSearch] provider={} 失败，尝试下一个: {}", provider, e.getMessage());
            }
        }
        return SearchResults.failed("所有免 Key 搜索源暂时不可用，请稍后再试，或直接给出明确网址改用网页抓取");
    }

    /** 把搜索结果渲染为给 LLM 的编号文本 */
    public String render(SearchResults results) {
        if (results.error() != null) {
            return "联网搜索失败：" + results.error();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是联网搜索返回的 ").append(results.results().size())
                .append(" 条结果（来源：").append(results.provider()).append("），请据此回答并在必要时附链接：\n\n");
        int i = 1;
        for (SearchResults.Item item : results.results()) {
            sb.append(i++).append(". ").append(item.title()).append('\n');
            sb.append("   URL: ").append(item.url()).append('\n');
            if (item.snippet() != null && !item.snippet().isBlank()) {
                sb.append("   摘要: ").append(item.snippet()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private int clampLimit(Integer maxResults) {
        int def = properties.getSearchDefaultResults() <= 0 ? 5 : properties.getSearchDefaultResults();
        int max = properties.getSearchMaxResults() <= 0 ? 10 : properties.getSearchMaxResults();
        if (maxResults == null || maxResults <= 0) {
            return def;
        }
        return Math.min(maxResults, max);
    }

    // ==================== Bing 国内版 ====================

    private SearchResults searchBing(String query, int limit) {
        String url = "https://cn.bing.com/search?q=" + enc(query)
                + "&setlang=zh-CN&ensearch=0&count=" + (limit + 2);
        SafeHttpResponse resp = httpClient.get(url);
        if (resp.status() != 200) {
            throw new SafeHttpException("Bing 返回状态 " + resp.status());
        }
        Document doc = Jsoup.parse(resp.body());
        Elements blocks = doc.select("li.b_algo");
        List<SearchResults.Item> items = new ArrayList<>();
        for (Element block : blocks) {
            Element link = block.selectFirst("h2 a");
            if (link == null) {
                continue;
            }
            String href = link.absUrl("href");
            if (href.isBlank()) {
                href = link.attr("href");
            }
            Element snippetEl = block.selectFirst(".b_caption p");
            if (snippetEl == null) {
                snippetEl = block.selectFirst("p");
            }
            items.add(new SearchResults.Item(
                    clean(link.text()), href, snippetEl == null ? "" : clean(snippetEl.text())));
            if (items.size() >= limit) {
                break;
            }
        }
        return SearchResults.ok("bing", items);
    }

    // ==================== DuckDuckGo HTML ====================

    private SearchResults searchDuckDuckGo(String query, int limit) {
        String url = "https://html.duckduckgo.com/html/?q=" + enc(query) + "&kl=cn-zh";
        SafeHttpResponse resp = httpClient.get(url);
        if (resp.status() != 200) {
            throw new SafeHttpException("DuckDuckGo 返回状态 " + resp.status());
        }
        Document doc = Jsoup.parse(resp.body());
        Elements blocks = doc.select(".result");
        List<SearchResults.Item> items = new ArrayList<>();
        for (Element block : blocks) {
            Element link = block.selectFirst(".result__a");
            if (link == null) {
                continue;
            }
            String href = unwrapDdgRedirect(link.absUrl("href").isBlank() ? link.attr("href") : link.absUrl("href"));
            Element snippetEl = block.selectFirst(".result__snippet");
            items.add(new SearchResults.Item(
                    clean(link.text()), href, snippetEl == null ? "" : clean(snippetEl.text())));
            if (items.size() >= limit) {
                break;
            }
        }
        return SearchResults.ok("duckduckgo", items);
    }

    /** DDG HTML 的链接形如 //duckduckgo.com/l/?uddg=<编码后的真实 URL>&rut=...，解出真实地址 */
    private static String unwrapDdgRedirect(String href) {
        try {
            String abs = href.startsWith("//") ? "https:" + href : href;
            int idx = abs.indexOf("uddg=");
            if (idx < 0) {
                return abs;
            }
            String tail = abs.substring(idx + 5);
            int amp = tail.indexOf('&');
            String encoded = amp < 0 ? tail : tail.substring(0, amp);
            return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return href;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
