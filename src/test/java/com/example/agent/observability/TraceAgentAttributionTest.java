package com.example.agent.observability;

import com.example.agent.observability.model.ToolTraceEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trace 工具事件的「Agent 归属」单测（Agent-as-Tool 嵌套归属）：
 *   - 主 Agent（无作用域）发起的工具调用 → agentId = null
 *   - 子代理作用域内发起的工具调用 → agentId = 子代理技能名
 *
 * 纯单测：不启 Spring、不连 LLM。
 */
class TraceAgentAttributionTest {

    private static ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();
    }

    @Test
    void mainAgentToolCallHasNullAgentId() {
        TraceCollector collector = TraceContextHolder.start("t-main", "u", 0L, null, null);
        try {
            collector.pendTool(request("id-1", "add"));
            collector.completeTool(ToolExecutionResultMessage.from("id-1", "add", "3"));

            assertThat(collector.tools()).hasSize(1);
            ToolTraceEvent event = collector.tools().get(0);
            assertThat(event.name()).isEqualTo("add");
            // 顶层（主 Agent）工具调用不归属任何子代理
            assertThat(event.agentId()).isNull();
        } finally {
            TraceContextHolder.clear();
        }
    }

    @Test
    void subAgentToolCallIsAttributedToItsSkillName() {
        TraceCollector collector = TraceContextHolder.start("t-sub", "u", 0L, null, null);
        try {
            // 模拟子代理执行：在子代理作用域内，其 LLM 决定调用内部工具
            AgentScope.runScoped("researcher", () -> {
                collector.pendTool(request("id-2", "webSearch"));
                return null;
            });
            collector.completeTool(ToolExecutionResultMessage.from("id-2", "webSearch", "结果文本"));

            assertThat(collector.tools()).hasSize(1);
            assertThat(collector.tools().get(0).agentId()).isEqualTo("researcher");
        } finally {
            TraceContextHolder.clear();
        }
    }
}
