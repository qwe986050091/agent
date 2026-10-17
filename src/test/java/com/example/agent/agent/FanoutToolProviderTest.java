package com.example.agent.agent;

import com.example.agent.observability.AgentScope;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FanoutToolProvider} 单测（fan-out 并行扇出工具）：
 *   - 暴露单个工具 delegate_parallel，入参 tasks（对象数组）
 *   - 多子代理结果合并为 【子代理·名称（描述）】...；失败显式标注
 *   - 未知名/缺参给可读提示
 *   - 回合级预算：一次扇出按任务数计入，预算不足直接拒绝且不执行
 */
class FanoutToolProviderTest {

    private static ToolProviderRequest request() {
        return new ToolProviderRequest(0L, UserMessage.from(TextContent.from("hi")));
    }

    private static ToolExecutionRequest call(String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(FanoutToolProvider.TOOL_NAME)
                .arguments(arguments)
                .build();
    }

    private static Map<String, SubAgentToolProvider.SubAgentRunner> runners(
            String agentA, String agentB) {
        Map<String, SubAgentToolProvider.SubAgentRunner> map = new LinkedHashMap<>();
        map.put(agentA, (task, memoryId) -> "结论-" + task);
        map.put(agentB, (task, memoryId) -> "结论-" + task);
        return map;
    }

    @Test
    void exposesParallelDelegateToolWithTasksArrayParam() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            FanoutToolProvider provider = new FanoutToolProvider(executor,
                    runners("a", "b"), Map.of("a", "甲", "b", "乙"), 4);

            ToolProviderResult result = provider.provideTools(request());

            ToolSpecification spec = result.toolSpecificationByName(FanoutToolProvider.TOOL_NAME);
            assertThat(spec).isNotNull();
            assertThat(spec.parameters().required()).containsExactly("tasks");
            assertThat(spec.description()).contains("a").contains("b");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void mergesResultsOfMultipleSubAgents() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            FanoutToolProvider provider = new FanoutToolProvider(executor,
                    runners("a", "b"), Map.of("a", "甲", "b", "乙"), 4);
            ToolExecutor tool = provider.provideTools(request())
                    .toolExecutorByName(FanoutToolProvider.TOOL_NAME);

            String merged = tool.execute(call(
                    "{\"tasks\":[{\"agent\":\"a\",\"task\":\"查资料\"},{\"agent\":\"b\",\"task\":\"写代码\"}]}"), 0L);

            assertThat(merged).contains("子代理·a（甲）").contains("结论-查资料");
            assertThat(merged).contains("子代理·b（乙）").contains("结论-写代码");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void partialFailureIsMarkedNotMasked() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            Map<String, SubAgentToolProvider.SubAgentRunner> map = new LinkedHashMap<>();
            map.put("ok", (task, memoryId) -> "正常结论");
            map.put("bad", (task, memoryId) -> {
                throw new IllegalStateException("子代理崩溃");
            });
            FanoutToolProvider provider = new FanoutToolProvider(executor, map,
                    Map.of("ok", "正常", "bad", "坏"), 4);
            ToolExecutor tool = provider.provideTools(request())
                    .toolExecutorByName(FanoutToolProvider.TOOL_NAME);

            String merged = tool.execute(call(
                    "{\"tasks\":[{\"agent\":\"ok\",\"task\":\"x\"},{\"agent\":\"bad\",\"task\":\"y\"}]}"), 0L);

            assertThat(merged).contains("正常结论");
            assertThat(merged).contains("子代理·bad（坏）·失败").contains("子代理崩溃");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void unknownAgentIsRejectedWithReadableMessage() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            FanoutToolProvider provider = new FanoutToolProvider(executor,
                    runners("a", "b"), Map.of("a", "甲", "b", "乙"), 4);
            ToolExecutor tool = provider.provideTools(request())
                    .toolExecutorByName(FanoutToolProvider.TOOL_NAME);

            String out = tool.execute(call("{\"tasks\":[{\"agent\":\"ghost\",\"task\":\"x\"}]}"), 0L);

            assertThat(out).contains("未知子代理").contains("ghost").contains("a, b");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void budgetExceededRejectsWithoutExecuting() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        TraceCollector collector = TraceContextHolder.start("t-fanout", "u", 0L, null, null);
        try {
            FanoutToolProvider provider = new FanoutToolProvider(executor,
                    runners("a", "b"), Map.of("a", "甲", "b", "乙"), 1); // 预算上限 1，本次需 2
            ToolExecutor tool = provider.provideTools(request())
                    .toolExecutorByName(FanoutToolProvider.TOOL_NAME);

            String out = tool.execute(call(
                    "{\"tasks\":[{\"agent\":\"a\",\"task\":\"x\"},{\"agent\":\"b\",\"task\":\"y\"}]}"), 0L);

            assertThat(out).contains("预算不足");
            assertThat(collector.delegations()).isZero(); // 拒绝时不计数、不执行
            assertThat(collector.tools()).isEmpty();
        } finally {
            TraceContextHolder.clear();
            executor.shutdown();
        }
    }

    @Test
    void missingTasksIsRejectedWithReadableMessage() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            FanoutToolProvider provider = new FanoutToolProvider(executor,
                    runners("a", "b"), Map.of("a", "甲", "b", "乙"), 4);
            ToolExecutor tool = provider.provideTools(request())
                    .toolExecutorByName(FanoutToolProvider.TOOL_NAME);

            String out = tool.execute(call("{}"), 0L);

            assertThat(out).contains("扇出失败").contains("tasks");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void subAgentRunsUnderItsOwnAgentScopeForTraceAttribution() {
        FanoutExecutor executor = new FanoutExecutor(3, 10);
        try {
            AtomicReference<String> seenScope = new AtomicReference<>();
            Map<String, SubAgentToolProvider.SubAgentRunner> map = new LinkedHashMap<>();
            map.put("a", (task, memoryId) -> {
                // 扇出执行时也必须处于该子代理作用域，否则其工具调用会丢掉 trace 归属（agentId）
                seenScope.set(AgentScope.current());
                return "ok";
            });
            FanoutToolProvider provider = new FanoutToolProvider(executor, map, Map.of("a", "甲"), 4);
            ToolExecutor tool = provider.provideTools(request())
                    .toolExecutorByName(FanoutToolProvider.TOOL_NAME);

            tool.execute(call("{\"tasks\":[{\"agent\":\"a\",\"task\":\"x\"}]}"), 0L);

            assertThat(seenScope).hasValue("a");
        } finally {
            executor.shutdown();
        }
    }
}
