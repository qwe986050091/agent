package com.example.agent.skill.impl;

import com.example.agent.skill.Skill;
import com.example.agent.tools.web.WebFetchTools;
import com.example.agent.tools.web.WebSearchTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 联网助手技能包：联网搜索（免 Key，自动放行）+ 网页抓取（URL 可控，执行前人工确认）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebSkill implements Skill {

    private final WebSearchTools webSearchTools;
    private final WebFetchTools webFetchTools;

    public WebSkill(WebSearchTools webSearchTools, WebFetchTools webFetchTools) {
        this.webSearchTools = webSearchTools;
        this.webFetchTools = webFetchTools;
    }

    @Override
    public String name() {
        return "web";
    }

    @Override
    public String shortDescription() {
        return "联网助手：联网搜索最新信息、抓取网页正文";
    }

    @Override
    public String detailedDescription() {
        return """
                【联网技能已激活】
                - webSearch：搜索最新新闻、实时数据、你知识截止后的信息
                - fetchWebPage：打开指定 URL 阅读网页正文（访问外部网站，需用户确认）
                """;
    }

    @Override
    public String systemPrompt() {
        return """
                【联网技能】
                - 涉及时效性（新闻、天气、近期事件、实时数据）或你不确定的事实时，先调用 webSearch 搜索。
                - 需要阅读某个网页具体内容（搜索结果链接或用户给出的 URL）时，调用 fetchWebPage；
                  该工具会先请求用户确认，拿到结果前不要编造网页内容。
                - 回答中引用搜索/抓取得到的事实时附上来源链接；搜索全部失败时如实说明联网不可用。
                """;
    }

    @Override
    public List<Object> tools() {
        return List.of(webSearchTools, webFetchTools);
    }
}
