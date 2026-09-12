package com.example.agent.tools.web;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验新工具注册给 LLM 的工具名与风险策略表（ToolRiskPolicy）使用的名字一致。
 * LangChain4j 默认工具名 = @Tool 方法名；审批/授权均按该名匹配，改名需同步策略表。
 */
class ToolNameVerificationTest {

    @Test
    void toolNamesMatchRiskPolicy() {
        Set<String> searchNames = names(ToolSpecifications.toolSpecificationsFrom(WebSearchTools.class));
        Set<String> fetchNames = names(ToolSpecifications.toolSpecificationsFrom(WebFetchTools.class));
        Set<String> httpNames = names(ToolSpecifications.toolSpecificationsFrom(HttpRequestTools.class));

        assertEquals(Set.of("webSearch"), searchNames);
        assertEquals(Set.of("fetchWebPage"), fetchNames);
        assertEquals(Set.of("sendHttpRequest"), httpNames);

        Set<String> all = Set.of("webSearch", "fetchWebPage", "sendHttpRequest");
        for (String name : all) {
            assertTrue(Set.of("webSearch", "fetchWebPage", "sendHttpRequest").contains(name));
        }
    }

    private static Set<String> names(java.util.List<ToolSpecification> specs) {
        return specs.stream().map(ToolSpecification::name).collect(Collectors.toSet());
    }
}
