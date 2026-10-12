package com.example.agent.skill;

import com.example.agent.controller.AgentController;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Skill 定义的 JSON 下发格式单测（P2-12）。
 *
 * backend 每次对话把「该用户已挂载的技能定义」随 payload 下发（{@code ChatRequest.skills}），
 * agent 侧按 {@link SkillDefinition} 反序列化。这里固化三件事：
 *   1. 字段映射（内置技能可以只填 name）
 *   2. {@code toolNames} 是有序数组（顺序即装配顺序，不能被解析成无序集合）
 *   3. 向前兼容契约：payload 里多出 agent 还不认识的字段不会打挂解析
 *      （{@code @RequestBody} 走 Spring 的消息转换器，Spring Boot 默认关闭
 *       FAIL_ON_UNKNOWN_PROPERTIES；用同款 builder 复现该行为）
 *
 * 纯单测：不启 Spring。
 */
class SkillDefinitionJsonTest {

    private static final String PAYLOAD = """
            {
              "userId": "u1",
              "conversationId": 7,
              "message": "帮我生成一份天气时间报告",
              "summary": "早前聊过报告格式",
              "skills": [
                { "name": "calculator" },
                {
                  "name": "weather_time_report",
                  "shortDescription": "生成天气时间报告",
                  "detailedDescription": "查询天气和时间，生成一份报告",
                  "systemPrompt": "先取天气再取时间，最后生成文档",
                  "toolNames": ["webSearchTools", "dateTimeTools"],
                  "futureFieldFromBackend": "backend 将来新增的字段"
                }
              ]
            }
            """;

    /** 用 Spring Boot 同款 builder，等价于 @RequestBody 实际走的消息转换器 */
    private static AgentController.ChatRequest parse(String json) throws Exception {
        return Jackson2ObjectMapperBuilder.json().build()
                .readValue(json, AgentController.ChatRequest.class);
    }

    @Test
    void mapsSkillFieldsAndKeepsToolNameOrder() throws Exception {
        AgentController.ChatRequest req = parse(PAYLOAD);

        assertThat(req.getSkills()).hasSize(2);

        // 内置技能：只填 name 也合法
        SkillDefinition builtin = req.getSkills().get(0);
        assertThat(builtin.getName()).isEqualTo("calculator");
        assertThat(builtin.getToolNames()).isNull();

        // 自定义技能：字段完整映射
        SkillDefinition custom = req.getSkills().get(1);
        assertThat(custom.getName()).isEqualTo("weather_time_report");
        assertThat(custom.getShortDescription()).isEqualTo("生成天气时间报告");
        assertThat(custom.getDetailedDescription()).isEqualTo("查询天气和时间，生成一份报告");
        assertThat(custom.getSystemPrompt()).isEqualTo("先取天气再取时间，最后生成文档");
        // 顺序必须与下发的数组一致（装配顺序会影响 LLM 看到的工具顺序）
        assertThat(custom.getToolNames()).containsExactly("webSearchTools", "dateTimeTools");
    }

    @Test
    void missingFieldsBecomeNull() throws Exception {
        AgentController.ChatRequest req = parse("{\"skills\":[{\"name\":\"only-name\"}]}");

        SkillDefinition def = req.getSkills().get(0);
        assertThat(def.getName()).isEqualTo("only-name");
        assertThat(def.getShortDescription()).isNull();
        assertThat(def.getDetailedDescription()).isNull();
        assertThat(def.getSystemPrompt()).isNull();
        assertThat(def.getToolNames()).isNull();
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() throws Exception {
        // PAYLOAD 里带了 "futureFieldFromBackend"：backend 加字段不应打挂 agent
        AgentController.ChatRequest req = parse(PAYLOAD);

        assertThat(req.getSkills()).hasSize(2);
        assertThat(req.getUserId()).isEqualTo("u1");
        assertThat(req.getConversationId()).isEqualTo(7L);
        assertThat(req.getMessage()).contains("天气时间报告");
    }

    @Test
    void emptySkillsArrayYieldsEmptyList() throws Exception {
        AgentController.ChatRequest req = parse("{\"skills\":[]}");

        assertThat(req.getSkills()).isEmpty();
    }

    @Test
    void normalizeFillsDefaultsForMissingCollections() throws Exception {
        AgentController.ChatRequest req = parse("{\"message\":\"hi\"}");

        // 缺字段时由 normalize() 兜底，避免下游 NPE
        req.normalize();
        assertThat(req.getUserId()).isEqualTo("default");
        assertThat(req.getConversationId()).isEqualTo(0L);
        assertThat(req.getSkills()).isEmpty();
        assertThat(req.getAllowedTools()).isEmpty();
        assertThat(req.getHistory()).isEmpty();
        assertThat(req.getImages()).isEmpty();
    }
}