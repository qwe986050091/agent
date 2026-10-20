package com.example.agent.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planner 解析单测：只验证「模型输出 → 可执行计划」的确定性部分，不连 LLM。
 */
class AgentPlannerTest {

    private static List<AgentPlanner.Specialist> specialists() {
        return List.of(
                new AgentPlanner.Specialist("researcher", "检索专家"),
                new AgentPlanner.Specialist("executor", "执行专家"),
                new AgentPlanner.Specialist("verifier", "校验专家"));
    }

    @Test
    void parsesJsonArrayWithCodeFenceAndProse() {
        String raw = """
                好的，计划如下：
                ```json
                [{"agent":"researcher","task":"查退款政策"},
                 {"agent":"verifier","task":"核对来源"}]
                ```
                """;

        Optional<AgentPlanner.Plan> plan = AgentPlanner.parsePlan("目标", raw, specialists(), 4);

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(2);
        assertThat(plan.get().steps().get(0).agent()).isEqualTo("researcher");
        assertThat(plan.get().steps().get(1).task()).isEqualTo("核对来源");
    }

    @Test
    void dropsUnknownAgentAndBlankTask() {
        String raw = """
                [{"agent":"hacker","task":"越权"},
                 {"agent":"researcher","task":"  "},
                 {"agent":"executor","task":"计算 1+1"}]
                """;

        Optional<AgentPlanner.Plan> plan = AgentPlanner.parsePlan("目标", raw,
                List.of(new AgentPlanner.Specialist("researcher", "检索"),
                        new AgentPlanner.Specialist("executor", "执行")), 4);

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(1);
        assertThat(plan.get().steps().get(0).agent()).isEqualTo("executor");
    }

    @Test
    void truncatesToMaxSteps() {
        String raw = """
                [{"agent":"researcher","task":"a"},
                 {"agent":"executor","task":"b"},
                 {"agent":"verifier","task":"c"}]
                """;

        Optional<AgentPlanner.Plan> plan = AgentPlanner.parsePlan("目标", raw, specialists(), 2);

        assertThat(plan).isPresent();
        assertThat(plan.get().steps()).hasSize(2);
    }

    @Test
    void returnsEmptyOnGarbageOrEmptySteps() {
        assertThat(AgentPlanner.parsePlan("目标", "我不会规划", specialists(), 4)).isEmpty();
        assertThat(AgentPlanner.parsePlan("目标", "[]", specialists(), 4)).isEmpty();
        assertThat(AgentPlanner.parsePlan("目标", null, specialists(), 4)).isEmpty();
    }

    @Test
    void detectsVerifierCandidate() {
        assertThat(AgentPlanner.hasVerifier(specialists())).isTrue();
        assertThat(AgentPlanner.hasVerifier(List.of(
                new AgentPlanner.Specialist("researcher", "检索")))).isFalse();
    }
}
