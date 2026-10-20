package com.example.agent.agent;

import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.observability.model.DelegationTraceEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlannerToolProvider} 单测：计划编排的串行执行、调用树父子关系、预算与校验回环。
 * 用 lambda 替身注入分解器与子代理，不连 LLM。
 */
class PlannerToolProviderTest {

    private static ToolProviderRequest request() {
        return new ToolProviderRequest(0L, UserMessage.from(TextContent.from("hi")));
    }

    private static ToolExecutionRequest call(String goal) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(PlannerToolProvider.TOOL_NAME)
                .arguments("{\"goal\":\"" + goal + "\"}")
                .build();
    }

    private static Map<String, SubAgentToolProvider.SubAgentRunner> runners(
            Map<String, AtomicReference<String>> seenTasks, List<String> order) {
        Map<String, SubAgentToolProvider.SubAgentRunner> map = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicReference<String>> e : seenTasks.entrySet()) {
            map.put(e.getKey(), (task, memoryId) -> {
                e.getValue().set(task);
                order.add(e.getKey());
                return e.getKey() + " 完成";
            });
        }
        return map;
    }

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("researcher", "检索专家");
        labels.put("executor", "执行专家");
        return labels;
    }

    private static ToolExecutor executor(AgentPlanner.PlanDecomposer planner,
                                         Map<String, SubAgentToolProvider.SubAgentRunner> runners) {
        PlannerToolProvider provider = new PlannerToolProvider(
                planner, runners, labels(), 4, 4);
        return provider.provideTools(request()).toolExecutorByName(PlannerToolProvider.TOOL_NAME);
    }

    @Test
    void executesStepsSeriallyAndBuildsParentChildTree() {
        List<String> order = new ArrayList<>();
        Map<String, AtomicReference<String>> seen = new LinkedHashMap<>();
        seen.put("researcher", new AtomicReference<>());
        seen.put("executor", new AtomicReference<>());
        AgentPlanner.PlanDecomposer planner = (goal, candidates) -> Optional.of(new AgentPlanner.Plan(
                goal, List.of(new AgentPlanner.PlanStep("researcher", "查资料"),
                        new AgentPlanner.PlanStep("executor", "算结果"))));

        TraceCollector collector = TraceContextHolder.start("t-plan", "u-1", 0L, null, null);
        try {
            String answer = executor(planner, runners(seen, order))
                    .execute(call("做一个调研"), 0L);

            assertThat(order).containsExactly("researcher", "executor");
            // 第二步能看到第一步的结果（串行依赖）
            assertThat(seen.get("executor").get()).contains("算结果")
                    .contains("researcher 完成");
            assertThat(answer).contains("【任务计划】").contains("【步骤结果】");

            List<DelegationTraceEvent> events = collector.delegationEvents();
            assertThat(events).hasSize(3);
            DelegationTraceEvent planNode = events.get(0);
            assertThat(planNode.agent()).isEqualTo(PlannerToolProvider.PLANNER_AGENT);
            assertThat(planNode.parentId()).isNull();
            assertThat(events.get(1).parentId()).isEqualTo(planNode.id());
            assertThat(events.get(2).parentId()).isEqualTo(planNode.id());
        } finally {
            TraceContextHolder.clear();
        }
    }

    @Test
    void fallsBackWhenPlannerCannotDecompose() {
        AtomicReference<String> called = new AtomicReference<>();
        Map<String, AtomicReference<String>> seen = new LinkedHashMap<>();
        seen.put("researcher", called);
        AgentPlanner.PlanDecomposer planner = (goal, candidates) -> Optional.empty();

        String answer = executor(planner, runners(seen, new ArrayList<>()))
                .execute(call("做一个调研"), 0L);

        assertThat(called).hasValue(null);
        assertThat(answer).contains("计划编排失败").contains("delegate_");
    }

    @Test
    void rejectsPlanExceedingDelegationBudget() {
        Map<String, AtomicReference<String>> seen = new LinkedHashMap<>();
        seen.put("researcher", new AtomicReference<>());
        AgentPlanner.PlanDecomposer planner = (goal, candidates) -> Optional.of(new AgentPlanner.Plan(
                goal, List.of(new AgentPlanner.PlanStep("researcher", "a"),
                        new AgentPlanner.PlanStep("researcher", "b"),
                        new AgentPlanner.PlanStep("researcher", "c"))));
        // 预算 2 < 需要 3
        PlannerToolProvider provider = new PlannerToolProvider(planner, runners(seen, new ArrayList<>()),
                labels(), 2, 4);
        ToolExecutor executor = provider.provideTools(request())
                .toolExecutorByName(PlannerToolProvider.TOOL_NAME);

        // 预算计数挂在回合级采集器上，测试需绑定一个
        TraceContextHolder.start("t-budget", "u-1", 0L, null, null);
        try {
            String answer = executor.execute(call("大任务"), 0L);

            assertThat(answer).contains("预算不足");
            assertThat(seen.get("researcher")).hasValue(null);
        } finally {
            TraceContextHolder.clear();
        }
    }

    @Test
    void verifierFailureTriggersSingleRetryOfPreviousStep() {
        List<String> order = new ArrayList<>();
        Map<String, AtomicReference<String>> seen = new LinkedHashMap<>();
        seen.put("researcher", new AtomicReference<>());
        seen.put("verifier", new AtomicReference<>());
        Map<String, SubAgentToolProvider.SubAgentRunner> map = new LinkedHashMap<>();
        map.put("researcher", (task, memoryId) -> {
            order.add("researcher");
            seen.get("researcher").set(task);
            return "初稿";
        });
        java.util.concurrent.atomic.AtomicInteger verifyCalls = new java.util.concurrent.atomic.AtomicInteger();
        map.put("verifier", (task, memoryId) -> {
            order.add("verifier");
            seen.get("verifier").set(task);
            // 第一次不通过，重做后通过
            return verifyCalls.incrementAndGet() == 1 ? "不通过：缺少来源" : "通过，结论自洽";
        });
        AgentPlanner.PlanDecomposer planner = (goal, candidates) -> Optional.of(new AgentPlanner.Plan(
                goal, List.of(new AgentPlanner.PlanStep("researcher", "查资料"),
                        new AgentPlanner.PlanStep("verifier", "复核"))));

        String answer = executor(planner, map).execute(call("做完它"), 0L);

        // 检索 → 校验（不通过）→ 重做检索 → 再校验
        assertThat(order).containsExactly("researcher", "verifier", "researcher", "verifier");
        assertThat(seen.get("researcher").get()).contains("校验意见").contains("缺少来源");
        assertThat(answer).contains("通过");
    }

    @Test
    void verificationFailureDetectsCommonPhrases() {
        assertThat(PlannerToolProvider.verificationFailed(
                new PlannerToolProvider.StepResult("verifier", "不通过：来源不足", true))).isTrue();
        assertThat(PlannerToolProvider.verificationFailed(
                new PlannerToolProvider.StepResult("verifier", "FAIL: inconsistent", true))).isTrue();
        assertThat(PlannerToolProvider.verificationFailed(
                new PlannerToolProvider.StepResult("verifier", "通过，结论自洽", true))).isFalse();
    }
}
