package com.example.agent.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * MCP 工具参数注入 user_id（方案 A：按用户隔离 kb_search 等工具）的契约测试。
 *
 * 关键约定：
 *  1. user_id 是隐藏字段，不在任何 inputSchema 中公开，模型构造不出来；
 *  2. 注入时一律**覆盖**，即使模型臆造了 user_id 也以服务端身份为准；
 *  3. 任何异常情况（参数非对象 / 坏 JSON / 无会话身份）都原样回退，绝不拖垮工具调用。
 */
class McpUserIdInjectionTest {

    private static ToolExecutionRequest request(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    @Test
    void injectsUserIdAndKeepsOtherFields() {
        ToolExecutionRequest original = request("c1", "kb_search", "{\"query\":\"部署流程\"}");

        ToolExecutionRequest injected = McpClientManager.withUserId(original, "user-42::9001");

        assertEquals("c1", injected.id());
        assertEquals("kb_search", injected.name());
        assertEquals("{\"query\":\"部署流程\",\"user_id\":\"user-42\"}", injected.arguments());
    }

    @Test
    void stripsConversationSuffixFromMemoryId() {
        // 记忆键是 userId::conversationId，而 RAG 隔离用的是裸 userId —— S6 真机实测踩过坑：
        // 若原样注入 "zlf::9001"，检索过滤条件与文档库里的 userId 不匹配，恒为 0 命中。
        assertEquals("zlf", McpClientManager.userIdOf("zlf::9001"));
        assertEquals("zlf", McpClientManager.userIdOf("zlf"));
        // 非字符串的记忆键（如 Long）走 toString，无分隔符即整串为 userId
        assertEquals("42", McpClientManager.userIdOf(42L));
        // conversationId 之后的 "::" 不会出现在真实数据里；含 "::" 的 userId 仍按最后一次切分还原。
        assertEquals("u::a", McpClientManager.userIdOf("u::a::7"));
        // 空前缀（异常构造的 "::0"）不注入
        assertEquals("", McpClientManager.userIdOf("::0"));
        assertEquals("", McpClientManager.userIdOf(null));
    }

    @Test
    void legacyBareMemoryIdStillWorks() {
        // 兼容没有会话后缀的调用路径（例如 memoryId 直接是 userId）。
        assertEquals("{\"query\":\"x\",\"user_id\":\"u1\"}",
                McpClientManager.withUserId(request("c9", "kb_search", "{\"query\":\"x\"}"), "u1").arguments());
    }

    @Test
    void overwritesModelSuppliedUserId() {
        // 模型若把 user_id 写进参数（例如幻觉或提示注入），也必须被服务端身份覆盖。
        ToolExecutionRequest original = request("c2", "kb_search",
                "{\"query\":\"x\",\"user_id\":\"someone-else\"}");

        ToolExecutionRequest injected = McpClientManager.withUserId(original, "real-user");

        assertEquals("{\"query\":\"x\",\"user_id\":\"real-user\"}", injected.arguments());
    }

    @Test
    void createsArgumentsObjectWhenMissing() {
        assertEquals("{\"user_id\":\"u1\"}",
                McpClientManager.withUserId(request("c3", "kb_search", null), "u1").arguments());
        assertEquals("{\"user_id\":\"u1\"}",
                McpClientManager.withUserId(request("c4", "kb_search", "  "), "u1").arguments());
    }

    @Test
    void returnsSameInstanceWhenConversationIdentityIsAbsent() {
        ToolExecutionRequest original = request("c5", "kb_search", "{\"query\":\"x\"}");
        assertSame(original, McpClientManager.withUserId(original, null));
        assertSame(original, McpClientManager.withUserId(original, "   "));
    }

    @Test
    void trimsUserId() {
        assertEquals("{\"user_id\":\"u9\"}",
                McpClientManager.withUserId(request("c6", "kb_search", null), "  u9  ").arguments());
    }

    @Test
    void fallsBackOnNonObjectArguments() {
        // 参数不是 JSON 对象（数组 / 字符串标量）时无法注入，必须原样回退。
        for (String arguments : new String[]{"[1,2]", "\"plain\"", "42", "{not json"}) {
            ToolExecutionRequest original = request("c7", "kb_search", arguments);
            ToolExecutionRequest result = McpClientManager.withUserId(original, "u1");
            assertSame(original, result, "参数 " + arguments + " 应原样回退");
        }
    }

    @Test
    void injectedFieldNameMatchesGoSideContract() {
        assertEquals("user_id", McpClientManager.USER_ID_FIELD);
    }
}
