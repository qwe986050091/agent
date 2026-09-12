package com.example.agent.skill.impl;

import com.example.agent.skill.Skill;
import com.example.agent.tools.web.HttpRequestTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HTTP 请求技能包：对第三方 API 发起真实 HTTP 调用（高危，每次人工确认）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.http", name = "enabled", havingValue = "true", matchIfMissing = true)
public class HttpApiSkill implements Skill {

    private final HttpRequestTools httpRequestTools;

    public HttpApiSkill(HttpRequestTools httpRequestTools) {
        this.httpRequestTools = httpRequestTools;
    }

    @Override
    public String name() {
        return "http-api";
    }

    @Override
    public String shortDescription() {
        return "HTTP 请求：调用第三方 HTTP API（高危，每次需确认）";
    }

    @Override
    public String detailedDescription() {
        return """
                【HTTP 请求技能已激活】
                sendHttpRequest 可对任意外部 http(s) 接口发起 GET/POST/PUT/PATCH/DELETE，
                每次执行前用户需确认；内网地址会被安全策略直接拦截。
                """;
    }

    @Override
    public String systemPrompt() {
        return """
                【HTTP 请求技能】
                - 仅在用户明确要求调用某个第三方接口时使用 sendHttpRequest。
                - 方法仅限 GET/POST/PUT/PATCH/DELETE；不要用于读取内网地址或云元数据地址（会被拦截）。
                - 每次调用都会弹窗请用户确认（展示方法、URL、请求体），未确认前不会执行；被拒绝时如实告知用户。
                - 根据返回的状态码与正文判断结果，不要臆测接口响应。
                """;
    }

    @Override
    public List<Object> tools() {
        return List.of(httpRequestTools);
    }
}
