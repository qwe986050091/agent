package com.example.agent.tools.web;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 网页抓取服务：SSRF 校验由 {@link SafeHttpClient} 保证；
 * 这里负责类型检查、HTML→纯文本提取与长度上限。
 */
@Component
public class WebFetchService {

    private static final Logger log = LoggerFactory.getLogger(WebFetchService.class);

    private final SafeHttpClient httpClient;
    private final WebToolsProperties properties;

    public WebFetchService(SafeHttpClient httpClient, WebToolsProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    /** 抓取网页正文文本；任何失败返回中文错误文本（供 LLM 直接转述） */
    public String fetch(String url) {
        try {
            SafeHttpResponse resp = httpClient.get(url);
            if (resp.status() >= 400) {
                return "网页抓取失败：目标返回 HTTP " + resp.status();
            }
            String contentType = resp.contentType() == null ? "" : resp.contentType().toLowerCase();
            if (!isReadableText(contentType)) {
                return "网页抓取失败：目标不是可读文本页面（Content-Type: "
                        + (contentType.isBlank() ? "未知" : contentType) + "），仅支持网页/文本";
            }

            String text = extractText(resp.body(), contentType);
            int limit = properties.getMaxTextChars();
            String result = text.length() > limit
                    ? text.substring(0, limit) + "\n...（内容过长已截断，可用更具体的 URL 再试）"
                    : text;
            if (resp.truncated()) {
                result += "\n（原始页面超过下载上限，内容可能不完整）";
            }
            return "页面地址：" + resp.finalUrl() + "\n\n" + result;
        } catch (UnsafeUrlException | SafeHttpException e) {
            return "网页抓取失败：" + e.getMessage();
        } catch (Exception e) {
            log.warn("[WebFetch] 抓取失败: {}", e.getMessage());
            return "网页抓取失败：" + e.getMessage();
        }
    }

    /** HTML 用 jsoup 提取标题+正文；JSON/纯文本原样返回（压缩空白） */
    private String extractText(String body, String contentType) {
        boolean html = contentType.contains("html") || (!contentType.contains("json")
                && !contentType.contains("text/plain") && looksLikeHtml(body));
        if (!html) {
            return body.strip();
        }
        Document doc = Jsoup.parse(body);
        doc.select("script,style,noscript,iframe,svg,canvas,link,meta").remove();
        String title = doc.title() == null ? "" : doc.title().trim();
        String text = doc.body() == null ? doc.text() : doc.body().text();
        text = text.replaceAll("\\s+", " ").trim();
        return (title.isBlank() ? "" : "标题：" + title + "\n\n") + text;
    }

    private boolean looksLikeHtml(String body) {
        if (body == null) {
            return false;
        }
        String head = body.stripLeading().toLowerCase();
        return head.contains("<html") || head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    /** 可读文本类型：网页/JSON/XML/纯文本；图片/音视频/压缩包/二进制流拒绝 */
    private boolean isReadableText(String contentType) {
        if (contentType.isBlank()) {
            return true; // 部分站点不返回 Content-Type，按 HTML 尝试解析
        }
        return contentType.startsWith("text/")
                || contentType.contains("html")
                || contentType.contains("xml")
                || contentType.contains("json")
                || contentType.contains("javascript");
    }
}
