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

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SubAgentToolProvider} 单测（Agent-as-Tool）：
 *   - 对主 Agent 暴露单个委派工具，名 delegate_{skill}，入参 task 必填
 *   - 执行委派时把 task 交给 runner 并回传其结论文本
 *   - 回合级全局预算：达上限后拒绝委派（runner 不再被调用）
 *   - 缺 task 参数时不调用 runner，返回可读提示
 *
 * 纯单测：不启 Spring、不连 LLM。
 */
class SubAgentToolProviderTest {

    private static ToolProviderRequest request() {
        return new ToolProviderRequest(0L, UserMessage.from(TextContent.from("hi")));
    }

    private static ToolExecutionRequest call(String toolName, String arguments) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(toolName)
                .arguments(arguments)
                .build();
    }

    @Test
    void exposesSingleDelegateToolWithRequiredTaskParam() {
        SubAgentToolProvider provider = new SubAgentToolProvider(
                "researcher", "研究子代理", (task, memoryId) -> "n/a", 4, 4000);

        ToolProviderResult result = provider.provideTools(request());

        assertThat(result.tools()).hasSize(1);
        assertThat(provider.toolName()).isEqualTo("delegate_researcher");
        ToolSpecification spec = result.toolSpecificationByName("delegate_researcher");
        assertThat(spec).isNotNull();
        assertThat(spec.description()).isEqualTo("研究子代理");
        assertThat(spec.parameters().required()).containsExactly(SubAgentToolProvider.TASK_PARAM);
        assertThat(spec.parameters().properties()).containsKey(SubAgentToolProvider.TASK_PARAM);
    }

    @Test
    void executorRunsSubAgentAndReturnsItsAnswer() {
        SubAgentToolProvider provider = new SubAgentToolProvider(
                "researcher", "研究子代理",
                (task, memoryId) -> {
                    // 模拟子代理内部 LLM 用量：应累计到本次委派事件
                    AgentScope.addTokens(42);
                    return "结论:" + task;
                }, 4, 4000);
        ToolProviderResult result = provider.provideTools(request());
        ToolExecutor executor = result.toolExecutorByName("delegate_researcher");

        TraceCollector collector = TraceContextHolder.start("t-1", "u-1", 0L, null, null);
        try {
            String answer = executor.execute(
                    call("delegate_researcher", "{\"task\":\"分析一下\"}"), 0L);

            assertThat(answer).isEqualTo("结论:分析一下");
            assertThat(collector.delegationEvents()).hasSize(1);
            var event = collector.delegationEvents().get(0);
            assertThat(event.agent()).isEqualTo("researcher");
            assertThat(event.parentId()).isNull();
            assertThat(event.success()).isTrue();
            assertThat(event.tokens()).isEqualTo(42);
            assertThat(event.task()).contains("分析一下");
        } finally {
            TraceContextHolder.clear();
        }
    }

    @Test
    void missingTaskDoesNotInvokeRunner() {
        AtomicInteger calls = new AtomicInteger();
        SubAgentToolProvider provider = new SubAgentToolProvider(
                "researcher", "研究子代理", (task, memoryId) -> {
                    calls.incrementAndGet();
                    return "x";
                }, 4, 4000);
        ToolExecutor executor = provider.provideTools(request()).toolExecutorByName("delegate_researcher");

        String answer = executor.execute(call("delegate_researcher", "{}"), 0L);

        assertThat(calls).hasValue(0);
        assertThat(answer).contains("缺少").contains(SubAgentToolProvider.TASK_PARAM);
    }

    @Test
    void delegationBudgetStopsFurtherCalls() {
        AtomicInteger calls = new AtomicInteger();
        SubAgentToolProvider provider = new SubAgentToolProvider(
                "researcher", "研究子代理", (task, memoryId) -> {
                    calls.incrementAndGet();
                    return "ok";
                }, 1, 4000);
        ToolExecutor executor = provider.provideTools(request()).toolExecutorByName("delegate_researcher");

        // 绑定回合级 trace 采集器：委派次数在这里累计（全局预算）
        TraceCollector collector = TraceContextHolder.start("t-1", "u-1", 0L, null, null);
        try {
            String first = executor.execute(call("delegate_researcher", "{\"task\":\"a\"}"), 0L);
            String second = executor.execute(call("delegate_researcher", "{\"task\":\"b\"}"), 0L);

            assertThat(first).isEqualTo("ok");
            assertThat(calls).hasValue(1);
            assertThat(second).contains("上限");
            assertThat(collector.delegations()).isEqualTo(1);
        } finally {
            TraceContextHolder.clear();
        }
    }

    @Test
    void toolNameIsSanitizedAndBounded() {
        assertThat(SubAgentToolProvider.toolNameOf("my skill.v2")).isEqualTo("delegate_my_skill_v2");
        assertThat(SubAgentToolProvider.toolNameOf("")).isEqualTo("delegate_sub");
        String longName = SubAgentToolProvider.toolNameOf("x".repeat(200));
        assertThat(longName.length()).isLessThanOrEqualTo(64);
        assertThat(longName).startsWith("delegate_");
    }

    @Test
    void delegationRunsUnderSubAgentScopeAndRestoresAfterwards() {
        java.util.concurrent.atomic.AtomicReference<String> seenInRunner = new java.util.concurrent.atomic.AtomicReference<>();
        SubAgentToolProvider provider = new SubAgentToolProvider(
                "researcher", "研究子代理", (task, memoryId) -> {
                    // 子代理内部执行时应处于自己的 Agent 作用域（trace 工具事件据此归属）
                    seenInRunner.set(AgentScope.current());
                    return "ok";
                }, 4, 4000);
        ToolExecutor executor = provider.provideTools(request()).toolExecutorByName("delegate_researcher");

        executor.execute(call("delegate_researcher", "{\"task\":\"a\"}"), 0L);

        assertThat(seenInRunner).hasValue("researcher");
        // 委派结束后恢复：主 Agent 作用域不受影响（此处无人设置，应为 null）
        assertThat(AgentScope.current()).isNull();
    }
}
