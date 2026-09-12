package com.example.agent.tools.web;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 联网工具配置（agent.tools.web.*）
 */
@Component
@ConfigurationProperties(prefix = "agent.tools.web")
public class WebToolsProperties {

    /** 联网搜索/抓取总开关（关闭后对应 Bean 不注册） */
    private boolean enabled = true;

    /** 免 Key 搜索 Provider 顺序（bing=cn.bing.com，duckduckgo=html.duckduckgo.com），前者失败依次回退 */
    private List<String> searchProviders = List.of("bing", "duckduckgo");

    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);

    /** 响应体下载上限（字节），超出截断 */
    private int maxBodyBytes = 2 * 1024 * 1024;

    /** 返回给 LLM 的文本上限（字符） */
    private int maxTextChars = 8000;

    /** 搜索结果条数默认/上限 */
    private int searchDefaultResults = 5;
    private int searchMaxResults = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getSearchProviders() { return searchProviders; }
    public void setSearchProviders(List<String> searchProviders) { this.searchProviders = searchProviders; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int maxBodyBytes) { this.maxBodyBytes = maxBodyBytes; }
    public int getMaxTextChars() { return maxTextChars; }
    public void setMaxTextChars(int maxTextChars) { this.maxTextChars = maxTextChars; }
    public int getSearchDefaultResults() { return searchDefaultResults; }
    public void setSearchDefaultResults(int searchDefaultResults) { this.searchDefaultResults = searchDefaultResults; }
    public int getSearchMaxResults() { return searchMaxResults; }
    public void setSearchMaxResults(int searchMaxResults) { this.searchMaxResults = searchMaxResults; }
}
