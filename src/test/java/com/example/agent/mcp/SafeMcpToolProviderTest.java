package com.example.agent.mcp;

import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单个 MCP Server 故障不得影响 Agent 构建：provider 退化为空工具集。
 */
class SafeMcpToolProviderTest {

    @Test
    void delegateFailureReturnsEmptyResult() {
        SafeMcpToolProvider provider = new SafeMcpToolProvider(
                request -> {
                    throw new RuntimeException("MCP server down");
                }, "broken");

        ToolProviderResult result = provider.provideTools(null);

        assertTrue(result.tools().isEmpty());
        assertEquals(0, result.tools().size());
    }
}
