package com.example.agent.skill;

import com.example.agent.agent.SubAgentFactory;
import com.example.agent.mcp.McpClientManager;
import com.example.agent.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillManager#resolveSkills} 解析规则单测（P2-12）：
 *   1. 内置技能以代码定义为准（名称命中即返回内置实例，忽略下发定义里的其余字段）
 *   2. 自定义技能按 toolNames 从 {@link ToolRegistry} 装配（顺序即声明顺序）
 *   3. 引用了未注册 Tool 时必须抛异常（数据不一致要暴露，不静默降级）
 *
 * 纯单测：不启 Spring、不连外部依赖。
 */
class SkillManagerResolveTest {

    private ToolRegistry toolRegistry;
    private SkillManager skillManager;
    private Skill builtinSkill;
    private SubAgentFactory subAgentFactory;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        McpClientManager mcpClientManager = mock(McpClientManager.class);
        when(mcpClientManager.skills()).thenReturn(List.of());

        // 默认以「工具型技能」路径解析；subagent 能力启用但仅 type=agent 才走子代理分支
        subAgentFactory = mock(SubAgentFactory.class);
        when(subAgentFactory.isEnabled()).thenReturn(true);

        builtinSkill = new StubSkill("calculator");
        skillManager = new SkillManager(List.of(builtinSkill), toolRegistry, mcpClientManager, subAgentFactory);
    }

    // ==================== 内置技能目录 ====================

    @Test
    void mcpDynamicSkillsJoinBuiltinCatalog() {
        McpClientManager mcp = mock(McpClientManager.class);
        Skill mcpSkill = new StubSkill("mcp:weather");
        when(mcp.skills()).thenReturn(List.of(mcpSkill));

        SkillManager manager = new SkillManager(List.of(builtinSkill), toolRegistry, mcp, subAgentFactory);

        // MCP 动态技能（mcp:{server}）与代码内置技能同在一个目录里
        assertThat(manager.builtinSkillNames()).containsExactly("calculator", "mcp:weather");
        assertThat(manager.getBuiltin("mcp:weather")).isSameAs(mcpSkill);
    }

    @Test
    void builtinCatalogIsReadOnly() {
        assertThatThrownBy(() -> skillManager.listBuiltin().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== 请求级解析 ====================

    @Test
    void nullDefinitionsYieldEmptyList() {
        assertThat(skillManager.resolveSkills(null)).isEmpty();
    }

    @Test
    void skipsNullEntriesAndBlankNames() {
        List<SkillDefinition> defs = new ArrayList<>();
        defs.add(null);
        defs.add(definition(null, "toolA"));
        defs.add(definition("", "toolA"));
        defs.add(definition("   ", "toolA"));

        assertThat(skillManager.resolveSkills(defs)).isEmpty();
    }

    @Test
    void builtinNameWinsOverDefinitionFields() {
        SkillDefinition def = definition("calculator", "someUnknownTool");
        def.setSystemPrompt("下发的兜底提示（应被忽略）");

        List<Skill> resolved = skillManager.resolveSkills(List.of(def));

        assertThat(resolved).hasSize(1);
        // 内置技能以代码为准：返回的就是同一个实例，定义里的字段不参与
        assertThat(resolved.get(0)).isSameAs(builtinSkill);
    }

    @Test
    void customSkillResolvesToolsInDeclarationOrder() {
        Object toolA = new Object();
        Object toolB = new Object();
        when(toolRegistry.getTool("toolA")).thenReturn(toolA);
        when(toolRegistry.getTool("toolB")).thenReturn(toolB);

        List<Skill> resolved = skillManager.resolveSkills(List.of(definition("report", "toolB", "toolA")));

        assertThat(resolved).hasSize(1);
        Skill skill = resolved.get(0);
        assertThat(skill).isInstanceOf(ConfigurableSkill.class);
        assertThat(skill.tools()).containsExactly(toolB, toolA);
    }

    @Test
    void configurableSkillDelegatesAllFieldsToDefinition() {
        Object toolA = new Object();
        when(toolRegistry.getTool("toolA")).thenReturn(toolA);

        Skill skill = skillManager.resolveSkills(List.of(definition("demo", "toolA"))).get(0);

        assertThat(skill.name()).isEqualTo("demo");
        assertThat(skill.shortDescription()).isEqualTo("短描述-demo");
        assertThat(skill.detailedDescription()).isEqualTo("详述-demo");
        assertThat(skill.systemPrompt()).isEqualTo("提示-demo");
        assertThat(skill.toolProvider()).isNull();
        assertThat(skill.toString()).contains("ConfigurableSkill{name=demo");
    }

    @Test
    void nullToolNamesYieldsEmptyToolsWithoutError() {
        List<Skill> resolved = skillManager.resolveSkills(List.of(definition("no-tools", (String[]) null)));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).tools()).isEmpty();
    }

    @Test
    void unknownToolNameThrowsActionableError() {
        when(toolRegistry.getTool("ghostTool")).thenReturn(null);
        when(toolRegistry.getAllToolNames()).thenReturn(Set.of("toolA", "toolB"));

        assertThatThrownBy(() -> skillManager.resolveSkills(List.of(definition("broken", "ghostTool"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken")
                .hasMessageContaining("ghostTool")
                .hasMessageContaining("toolA");
    }

    @Test
    void duplicateDefinitionsAreNotDeduplicated() {
        // 已知缺陷留痕：resolveSkills 的 javadoc 声明「顺序与入参一致，去重」，
        // 但实现里没有去重逻辑，重复名称会原样出现两条。
        // 本用例按实际行为断言；将来若修复去重，此用例应随之更新。
        Object toolA = new Object();
        when(toolRegistry.getTool("toolA")).thenReturn(toolA);

        List<Skill> resolved = skillManager.resolveSkills(List.of(
                definition("dup", "toolA"), definition("dup", "toolA")));

        assertThat(resolved).hasSize(2);
    }

    // ==================== 子代理（Agent-as-Tool）分支 ====================

    @Test
    void agentTypeSkillIsBuiltAsSubAgentNotPlainTools() {
        SkillDefinition def = definition("researcher", "webSearchTools");
        def.setType(SkillDefinition.TYPE_AGENT);
        Skill subAgent = mock(Skill.class);
        when(subAgentFactory.buildAgentSkill(def)).thenReturn(subAgent);

        List<Skill> resolved = skillManager.resolveSkills(List.of(def));

        // type=agent 走子代理分支：交给 SubAgentFactory 构建，而非把私有工具直挂主 Agent
        assertThat(resolved).containsExactly(subAgent);
    }

    @Test
    void agentTypeFallsBackToPlainSkillWhenSubAgentDisabled() {
        when(subAgentFactory.isEnabled()).thenReturn(false);
        Object toolA = new Object();
        when(toolRegistry.getTool("toolA")).thenReturn(toolA);
        SkillDefinition def = definition("downgraded", "toolA");
        def.setType(SkillDefinition.TYPE_AGENT);

        Skill skill = skillManager.resolveSkills(List.of(def)).get(0);

        // 子代理能力关闭：退化为普通技能（工具直挂主 Agent），保证可用性不因开关而丢失
        assertThat(skill).isInstanceOf(ConfigurableSkill.class);
        assertThat(skill.tools()).containsExactly(toolA);
    }

    @Test
    void typeToolIsNotTreatedAsAgent() {
        Object toolA = new Object();
        when(toolRegistry.getTool("toolA")).thenReturn(toolA);
        SkillDefinition def = definition("plain", "toolA");
        def.setType(SkillDefinition.TYPE_TOOL);

        Skill skill = skillManager.resolveSkills(List.of(def)).get(0);

        assertThat(skill).isInstanceOf(ConfigurableSkill.class);
    }

    // ==================== 测试夹具 ====================

    /** 构造一个自定义技能定义；toolNames 传 {@code (String[]) null} 表示该字段缺失 */
    private static SkillDefinition definition(String name, String... toolNames) {
        SkillDefinition def = new SkillDefinition();
        def.setName(name);
        def.setShortDescription("短描述-" + name);
        def.setDetailedDescription("详述-" + name);
        def.setSystemPrompt("提示-" + name);
        def.setToolNames(toolNames == null ? null : List.of(toolNames));
        return def;
    }

    /** 测试用最小内置 Skill 实现（内置目录只需要 name()） */
    private static final class StubSkill implements Skill {
        private final String name;

        StubSkill(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String shortDescription() {
            return "内置短描述";
        }

        @Override
        public String detailedDescription() {
            return "内置详述";
        }

        @Override
        public String systemPrompt() {
            return "内置提示";
        }

        @Override
        public List<Object> tools() {
            return List.of();
        }
    }
}