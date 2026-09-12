package com.example.agent.mcp;

import com.example.agent.mcp.McpProperties.Server;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-03 回归：不可达 MCP server 在装配期（provideTools）失败时，
 * 只能退化为空工具集 + WARN，绝不能拖垮整回合对话。
 */
class UnreachableMcpServerTest {

    @Test
    void unreachableServerYieldsEmptyToolsWithoutThrowing() {
        McpProperties props = new McpProperties();
        Server server = new Server();
        server.setName("broken");
        server.setTransport("http");
        // 1 端口通常无监听，连接被立即拒绝（不需要等待初始化超时）
        server.setUrl("http://127.0.0.1:1/mcp");
        props.getServers().add(server);

        McpClientManager manager = new McpClientManager(props);
        ToolProviderRequest request = new ToolProviderRequest("m1", UserMessage.from("hi"));

        long t0 = System.currentTimeMillis();
        ToolProviderResult result = assertDoesNotThrow(
                () -> manager.toolProvider("broken").provideTools(request));
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(result.tools().isEmpty(), "不可达 server 应返回空工具集");
        assertTrue(elapsed < 20_000, "不可达时不应长时间阻塞装配（实际 " + elapsed + "ms）");

        // 再次调用同样安全（失败不缓存为成功）
        assertDoesNotThrow(() -> manager.toolProvider("broken").provideTools(request));
        manager.shutdown();
    }
}
