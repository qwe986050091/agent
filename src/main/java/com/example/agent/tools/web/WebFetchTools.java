package com.example.agent.tools.web;

import com.example.agent.approval.ApprovalGate;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 网页抓取工具：URL 用户可控（SSRF 风险）。
 * 顺序：先 {@link UrlSafetyGuard} 拒绝内网/非法地址（不弹窗），再人工确认，最后实际请求。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebFetchTools {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTools.class);

    private final WebFetchService webFetchService;
    private final UrlSafetyGuard urlSafetyGuard;

    public WebFetchTools(WebFetchService webFetchService, UrlSafetyGuard urlSafetyGuard) {
        this.webFetchService = webFetchService;
        this.urlSafetyGuard = urlSafetyGuard;
    }

    @Tool("""
            抓取指定网页的正文内容：输入完整 http(s) URL，返回网页标题与纯文本正文（超长自动截断）。
            适用于打开搜索结果链接、阅读用户给出的网页。仅支持网页与文本内容，不能下载图片/文件。
            该操作访问外部网站，执行前需要用户确认。
            """)
    public String fetchWebPage(String url) {
        log.info("[Tool] fetchWebPage(url={})", url);
        // 1) SSRF 前置校验：内网/元数据/非法协议在弹窗前直接拒绝（决策接口不会被调用）
        try {
            urlSafetyGuard.validate(url);
        } catch (UnsafeUrlException e) {
            return "网页抓取失败：" + e.getMessage();
        }
        // 2) 高危：人工确认（SSRF 已确认安全，审批后实际请求阶段会再次校验）
        ApprovalGate.requireConfirmation("fetchWebPage", url);
        return webFetchService.fetch(url);
    }
}
