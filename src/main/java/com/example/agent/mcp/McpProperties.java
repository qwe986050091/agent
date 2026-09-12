package com.example.agent.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP Server 配置（agent.mcp.servers[]）。
 * 默认空列表：功能休眠，启动/对话行为与未引入 MCP 时完全一致。
 *
 * 示例：
 * <pre>
 * agent:
 *   mcp:
 *     servers:
 *       - name: everything
 *         transport: http                 # http = Streamable HTTP；stdio = 本地子进程
 *         url: http://127.0.0.1:3001/mcp
 *         approval: confirm               # confirm（默认，每次人工确认）| auto（自动放行）
 *         description: 示例 MCP Server
 *       - name: fs
 *         transport: stdio
 *         command: ["npx", "-y", "@modelcontextprotocol/server-everything"]
 *         environment: {}
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "agent.mcp")
public class McpProperties {

    private List<Server> servers = new ArrayList<>();

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers;
    }

    public static class Server {
        private String name;
        /** http（Streamable HTTP）或 stdio */
        private String transport = "http";
        /** transport=http 时的 MCP POST 端点 */
        private String url;
        /** transport=stdio 时的启动命令（含参数） */
        private List<String> command = new ArrayList<>();
        /** stdio 子进程环境变量 */
        private Map<String, String> environment;
        private String description;
        /** confirm（默认，每工具人工确认）/ auto（自动放行，仅用于可信内网 server） */
        private String approval = "confirm";
        private boolean enabled = true;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public List<String> getCommand() { return command; }
        public void setCommand(List<String> command) { this.command = command; }
        public Map<String, String> getEnvironment() { return environment; }
        public void setEnvironment(Map<String, String> environment) { this.environment = environment; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getApproval() { return approval; }
        public void setApproval(String approval) { this.approval = approval; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
