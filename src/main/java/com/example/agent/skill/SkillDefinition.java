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
 */
public class SkillDefinition {

    private String name;
    private String shortDescription;
    private String detailedDescription;
    private String systemPrompt;
    /** 引用平台已注册的内置 Tool 名称 */
    private List<String> toolNames;

    public SkillDefinition() {}

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
}
