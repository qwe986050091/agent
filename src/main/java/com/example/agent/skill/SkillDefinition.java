package com.example.agent.skill;

import java.util.List;

/**
 * Skill 的 JSON 配置定义（用户上传 Skill 时的格式）
 *
 * 示例 JSON：
 * {
 *   "name": "weather_time_report",
 *   "shortDescription": "生成天气时间报告",
 *   "detailedDescription": "查询天气和时间，生成一份报告",
 *   "systemPrompt": "当用户要求生成天气时间报告时，必须：1.调用 getWeather 2.调用 getCurrentTime 3.生成文档",
 *   "toolNames": ["getWeather", "getCurrentTime"]
 * }
 *
 * type=agent 时（子代理 / Agent-as-Tool）：
 *   - systemPrompt 变为该子代理自己的 System Message（它按此独立完成子任务）
 *   - toolNames 变为该子代理的私有工具（不直接暴露给主 Agent）
 *   - 主 Agent 只看到一个委派工具 delegate_{name}，调用它即把子任务交给子代理
 */
public class SkillDefinition {

    /** type 取值：普通技能包（工具直挂主 Agent） */
    public static final String TYPE_TOOL = "tool";
    /** type 取值：子代理（独立推理循环 + 私有工具，经委派工具调用） */
    public static final String TYPE_AGENT = "agent";

    private String name;
    private String shortDescription;
    private String detailedDescription;
    private String systemPrompt;
    /** 引用平台已注册的内置 Tool 名称 */
    private List<String> toolNames;
    /** 技能类型：tool（默认）| agent（子代理）。null/空串视为 tool，保证向后兼容 */
    private String type;

    public SkillDefinition() {}

    /** 是否声明为子代理（type=agent，大小写不敏感） */
    public boolean isAgentType() {
        return TYPE_AGENT.equalsIgnoreCase(type == null ? "" : type.trim());
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getShortDescription() { return shortDescription; }
    public void setShortDescription(String d) { this.shortDescription = d; }
    public String getDetailedDescription() { return detailedDescription; }
    public void setDetailedDescription(String d) { this.detailedDescription = d; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String p) { this.systemPrompt = p; }
    public List<String> getToolNames() { return toolNames; }
    public void setToolNames(List<String> t) { this.toolNames = t; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
