package com.example.agent.mcp;

import com.example.agent.skill.Skill;
import dev.langchain4j.service.tool.ToolProvider;

import java.util.List;

/**
 * 由 MCP Server 配置动态生成的内置技能（mcp:{serverName}）。
 * 工具不在编译期固定，运行时经 {@link ToolProvider} 从 MCP Server 动态发现。
 */
public class McpSkill implements Skill {

    private final String serverName;
    private final String description;
    private final McpClientManager manager;

    public McpSkill(String serverName, String description, McpClientManager manager) {
        this.serverName = serverName;
        this.description = description;
        this.manager = manager;
    }

    @Override
    public String name() {
        return "mcp:" + serverName;
    }

    @Override
    public String shortDescription() {
        return description;
    }

    @Override
    public String detailedDescription() {
        return "【MCP 技能已激活】工具来自外部 MCP Server「" + serverName + "」，运行时动态发现。";
    }

    @Override
    public String systemPrompt() {
        return """
                【MCP 技能：%s】
                你可以使用来自外部 MCP Server「%s」的工具（工具清单已动态注册）。
                - 仅在用户意图与这些工具能力相关时调用；调用前确认参数完整。
                - 该 Server 的工具属于高危外部操作，执行前会请求用户确认；被拒绝时如实告知用户，不要重试或绕过。
                - 工具失败时把错误信息简要转述给用户，不要编造执行结果。
                """.formatted(serverName, serverName);
    }

    @Override
    public List<Object> tools() {
        // 工具经 ToolProvider 动态提供，无静态 @Tool Bean
        return List.of();
    }

    @Override
    public ToolProvider toolProvider() {
        return manager.toolProvider(serverName);
    }

    public String serverName() {
        return serverName;
    }
}
