package com.example.agent.mcp;

import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * MCP ToolProvider 容错包装：
 * 单个 MCP Server 不可达 / listTools 失败 / 超时，不拖垮整个 Agent 构建，
 * 退化为「该技能本轮无工具」+ WARN 日志，对话与其他技能不受影响。
 */
public class SafeMcpToolProvider implements ToolProvider {

    private static final Logger log = LoggerFactory.getLogger(SafeMcpToolProvider.class);

    private final ToolProvider delegate;
    private final String serverName;

    public SafeMcpToolProvider(ToolProvider delegate, String serverName) {
        this.delegate = delegate;
        this.serverName = serverName;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        try {
            return delegate.provideTools(request);
        } catch (Exception e) {
            log.warn("[MCP] Server「{}」工具列表获取失败，本轮跳过其工具: {}",
                    serverName, e.getMessage());
            return new ToolProviderResult(List.of());
        }
    }
}
