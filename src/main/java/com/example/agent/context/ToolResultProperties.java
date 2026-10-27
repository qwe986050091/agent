package com.example.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工具结果治理配置（agent.tool-result.*，P0 上下文治理）。
 *
 * <p>解决的问题：{@link ContextBudget} 只覆盖 system / summary / retrieval / history 四段，
 * 而 ReAct 循环里的工具结果不在预算内。一次超长网页、一次大文件解析或 MCP 工具返回，
 * 都可能把上下文挤爆，且历史裁剪只能整条丢消息。
 *
 * <p>治理策略（{@link ToolResultGovernor}，按回合生效）：
 * <ol>
 *   <li>单条超限：保留开头 + 结尾，中间替换为截断标记（原文可落盘供审计）；</li>
 *   <li>总量超限：从最旧的工具结果开始替换为占位符，只保留最新一条的完整内容。</li>
 * </ol>
 *
 * <p>与 Claude Code 的差异：对方有 Read 工具，落盘后模型能自己回读；本 agent 没有文件工具，
 * 所以落盘只用于审计与排障，模型侧看到的是「请缩小查询范围/分页」的提示。
 */
@Component
@ConfigurationProperties(prefix = "agent.tool-result")
public class ToolResultProperties {

    /** 总开关：false = 不治理（仅保留原有各工具自限行为） */
    private boolean enabled = true;

    /** 单条工具结果字符上限（超出保留头尾并截断） */
    private int maxCharsPerResult = 8000;

    /** 单回合工具结果累计字符上限（超出从最旧开始清理） */
    private int maxTotalCharsPerTurn = 24000;

    /** 截断时保留的开头字符数 */
    private int headChars = 3000;

    /** 截断时保留的结尾字符数 */
    private int tailChars = 1000;

    /** 原文落盘目录（空 = 不落盘；供审计/排障，不暴露给模型） */
    private String spillDir = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxCharsPerResult() { return maxCharsPerResult; }
    public void setMaxCharsPerResult(int maxCharsPerResult) { this.maxCharsPerResult = maxCharsPerResult; }
    public int getMaxTotalCharsPerTurn() { return maxTotalCharsPerTurn; }
    public void setMaxTotalCharsPerTurn(int maxTotalCharsPerTurn) { this.maxTotalCharsPerTurn = maxTotalCharsPerTurn; }
    public int getHeadChars() { return headChars; }
    public void setHeadChars(int headChars) { this.headChars = headChars; }
    public int getTailChars() { return tailChars; }
    public void setTailChars(int tailChars) { this.tailChars = tailChars; }
    public String getSpillDir() { return spillDir; }
    public void setSpillDir(String spillDir) { this.spillDir = spillDir; }
}
