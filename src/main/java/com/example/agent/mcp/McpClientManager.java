package com.example.agent.mcp;

import com.example.agent.approval.ApprovalGate;
import com.example.agent.approval.ApprovalMode;
import com.example.agent.mcp.McpProperties.Server;
import com.example.agent.skill.Skill;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端管理器：按 yml 配置懒加载长连 McpClient（单例），
 * 为每个 server 产出一个带审批包装的 {@link ToolProvider} 与一个动态内置技能。
 *
 * 无配置时为空壳：不发起任何连接、不产出技能，启动行为零变化。
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    /** 校验通过的启用配置（name → config），保持 yml 顺序 */
    private final Map<String, Server> configs = new LinkedHashMap<>();
    /** 懒加载客户端（仅缓存成功初始化的实例；失败下次重试） */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** 每个 server 的（安全包装）ToolProvider 缓存 */
    private final Map<String, ToolProvider> providers = new ConcurrentHashMap<>();

    public McpClientManager(McpProperties properties) {
        if (properties.getServers() == null) {
            return;
        }
        for (Server server : properties.getServers()) {
            String validationError = validate(server);
            if (validationError != null) {
                log.error("[MCP] Server 配置非法，已跳过: name={}, 原因={}", server.getName(), validationError);
                continue;
            }
            if (!server.isEnabled()) {
                log.info("[MCP] Server「{}」已配置但 enabled=false，跳过", server.getName());
                continue;
            }
            configs.put(server.getName(), server);
        }
        if (!configs.isEmpty()) {
            log.info("[MCP] 已配置 {} 个 MCP Server（懒加载，首次使用时连接）: {}",
                    configs.size(), configs.keySet());
        }
    }

    private String validate(Server s) {
        if (s.getName() == null || s.getName().isBlank()) {
            return "缺少 name";
        }
        String transport = s.getTransport() == null ? "" : s.getTransport().trim().toLowerCase();
        if ("http".equals(transport)) {
            if (s.getUrl() == null || s.getUrl().isBlank()) {
                return "transport=http 时必须配置 url";
            }
        } else if ("stdio".equals(transport)) {
            if (s.getCommand() == null || s.getCommand().isEmpty()) {
                return "transport=stdio 时必须配置 command（含可执行文件与参数）";
            }
        } else {
            return "不支持的 transport: " + s.getTransport() + "（仅 http/stdio）";
        }
        String approval = s.getApproval() == null ? "" : s.getApproval().trim().toLowerCase();
        if (!approval.isEmpty() && !"confirm".equals(approval) && !"auto".equals(approval)) {
            return "approval 仅支持 confirm/auto";
        }
        return null;
    }

    public boolean isEmpty() {
        return configs.isEmpty();
    }

    /** 动态内置技能（mcp:{name}）；不触发连接 */
    public List<Skill> skills() {
        List<Skill> result = new ArrayList<>();
        for (Server server : configs.values()) {
            result.add(new McpSkill(server.getName(),
                    server.getDescription() != null && !server.getDescription().isBlank()
                            ? server.getDescription() : "MCP 服务：" + server.getName(),
                    this));
        }
        return result;
    }

    /**
     * 取某 server 的安全 ToolProvider。
     * 连接/listTools 延迟到 provideTools（每回合装配期）内进行，且任何失败都被
     * {@link SafeMcpToolProvider} 吞成空工具集：不可达 server 绝不拖垮整回合对话。
     */
    public ToolProvider toolProvider(String serverName) {
        return providers.computeIfAbsent(serverName, name -> {
            Server cfg = configs.get(name);
            ToolProvider dynamic = request -> {
                // client 懒加载单例；失败抛异常由外层 SafeMcpToolProvider 兜底为空结果
                McpClient mcpClient = client(name);
                ToolProvider delegate = McpToolProvider.builder()
                        .mcpClients(mcpClient)
                        .failIfOneServerFails(false)
                        .toolWrapper(executor -> approvalAwareExecutor(executor, cfg))
                        .build();
                return delegate.provideTools(request);
            };
            return new SafeMcpToolProvider(dynamic, name);
        });
    }

    /** 工具执行器包装：confirm 策略 → 每次人工确认（可被会话授权放行）；auto → 直接放行 */
    private ToolExecutor approvalAwareExecutor(ToolExecutor delegate, Server cfg) {
        boolean auto = isAutoApprove(cfg);
        return (ToolExecutionRequest request, Object memoryId) -> {
            if (!auto) {
                // 审批/授权键命名空间化：mcp:{server}:{tool}，避免跨 server/内置同名工具串用授权
                String approvalKey = "mcp:" + cfg.getName() + ":" + request.name();
                String displayName = "MCP 工具（" + cfg.getName() + "/" + request.name() + "）";
                com.example.agent.approval.ToolRiskPolicy.Policy policy =
                        new com.example.agent.approval.ToolRiskPolicy.Policy(
                                ApprovalMode.CONFIRM, "high", displayName);
                ApprovalGate.requireConfirmation(approvalKey, request.arguments(), policy);
            }
            return delegate.execute(request, memoryId);
        };
    }

    private static boolean isAutoApprove(Server cfg) {
        return cfg.getApproval() != null && "auto".equalsIgnoreCase(cfg.getApproval().trim());
    }

    /** 懒加载并校验连接（initialize + listTools）；失败不缓存，下次重试 */
    private McpClient client(String name) {
        McpClient existing = clients.get(name);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            existing = clients.get(name);
            if (existing != null) {
                return existing;
            }
            Server cfg = configs.get(name);
            if (cfg == null) {
                throw new IllegalStateException("MCP Server 未配置: " + name);
            }
            McpClient client = createClient(cfg);
            try {
                int count = client.listTools().size();
                log.info("[MCP] Server「{}」连接成功，可用工具 {} 个", name, count);
            } catch (Exception e) {
                closeQuietly(client);
                throw new IllegalStateException("MCP Server「" + name + "」连接/工具列表获取失败: " + e.getMessage(), e);
            }
            clients.put(name, client);
            return client;
        }
    }

    private static McpClient createClient(Server cfg) {
        String transport = cfg.getTransport().trim().toLowerCase();
        McpTransport t;
        if ("http".equals(transport)) {
            t = StreamableHttpMcpTransport.builder()
                    .url(cfg.getUrl().trim())
                    .timeout(Duration.ofSeconds(20))
                    .followRedirects(false)
                    .build();
        } else {
            StdioMcpTransport.Builder builder = StdioMcpTransport.builder()
                    .command(cfg.getCommand())
                    .logEvents(false);
            if (cfg.getEnvironment() != null && !cfg.getEnvironment().isEmpty()) {
                builder.environment(cfg.getEnvironment());
            }
            t = builder.build();
        }
        return DefaultMcpClient.builder()
                .key("mcp:" + cfg.getName())
                .transport(t)
                .initializationTimeout(Duration.ofSeconds(15))
                .toolExecutionTimeout(Duration.ofSeconds(60))
                .cacheToolList(true)
                .build();
    }

    private static void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (Exception ignore) {
            // 初始化失败路径，忽略关闭异常
        }
    }

    @PreDestroy
    void shutdown() {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            closeQuietly(entry.getValue());
            log.info("[MCP] Server「{}」客户端已关闭", entry.getKey());
        }
        clients.clear();
    }
}
