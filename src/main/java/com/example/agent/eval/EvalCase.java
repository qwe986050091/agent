package com.example.agent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 一条评测用例（P0-B 评测集的最小单元）。
 *
 * <p>用例从 JSON 数据集加载（见 {@code resources/eval/dataset/*.json}），字段分两类：
 * <ul>
 *   <li><b>输入</b>：{@code input}（用户问题）+ {@code skills}（挂载技能）+ {@code allowedTools}（预授权工具，
 *       让评测不被人工审批闸门挡住）；</li>
 *   <li><b>期望（断言目标）</b>：{@code expectedTools}（应当调用的工具集）、{@code expectedDocs}
 *       （应当引用的知识库文档 docId）、{@code requiredPoints}/{@code forbiddenPoints}（答案必须/不得包含的要点）、
 *       {@code expectRefusal}（安全用例：应拒答）。</li>
 * </ul>
 *
 * <p>四类 {@code category}（见下方常量）决定断言适用面：rag_qa 走要点+引用，tool_call 走工具+要点，
 * safety 只走拒答，plain 只走要点。断言实际是否执行以「期望字段是否非空」为准，与 category 解耦，
 * 便于后续扩展。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String id,
        String category,
        String input,
        List<String> skills,
        List<String> allowedTools,
        List<String> expectedTools,
        List<String> expectedDocs,
        List<String> requiredPoints,
        List<String> forbiddenPoints,
        Boolean expectRefusal,
        List<String> refusalKeywords,
        List<String> tags,
        String note) {

    /** RAG 问答：期望命中知识库文档并引用（要点 + 引用断言） */
    public static final String CATEGORY_RAG_QA = "rag_qa";
    /** 工具调用：期望正确选择工具（工具 + 要点断言） */
    public static final String CATEGORY_TOOL_CALL = "tool_call";
    /** 安全：期望拒答（拒答断言） */
    public static final String CATEGORY_SAFETY = "safety";
    /** 普通对话：只校验要点 */
    public static final String CATEGORY_PLAIN = "plain";

    public EvalCase {
        skills = listOf(skills);
        allowedTools = listOf(allowedTools);
        expectedTools = listOf(expectedTools);
        expectedDocs = listOf(expectedDocs);
        requiredPoints = listOf(requiredPoints);
        forbiddenPoints = listOf(forbiddenPoints);
        refusalKeywords = listOf(refusalKeywords);
        tags = listOf(tags);
        expectRefusal = Boolean.TRUE.equals(expectRefusal);
    }

    /** 安全用例：要求模型/闸门拒答 */
    public boolean refuses() {
        return expectRefusal;
    }

    public boolean checksTools() {
        return !expectedTools.isEmpty();
    }

    public boolean checksCitations() {
        return !expectedDocs.isEmpty();
    }

    public boolean checksPoints() {
        return !requiredPoints.isEmpty() || !forbiddenPoints.isEmpty();
    }

    private static List<String> listOf(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
