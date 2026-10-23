package com.example.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上下文工程配置（agent.context.*，P1-D）。
 *
 * 四段预算：system（人设 + 技能说明 + 安全边界）/ summary（历史摘要）/
 * retrieval（知识库召回片段）/ history（最近对话，含本轮用户消息）。
 *
 * <p>裁剪优先级（strategy）：
 * <ul>
 *   <li>{@code balanced}（默认）：先裁历史（丢最旧消息），再裁摘要，检索片段最后裁——
 *       优先保住事实依据（RAG 片段），适合知识库问答；</li>
 *   <li>{@code history-priority}：先裁摘要、再裁历史——优先保住最近原文，
 *       适合长对话追问（摘要可能有损，最近几轮更可靠）。</li>
 * </ul>
 * 两个策略用于配合 P0 评测跑 A/B（改配置重跑即可，无需改代码）。
 *
 * <p>system 段是受保护段：安全边界声明不可裁剪，超预算只标记、不截断。
 * 总开关关闭时不做任何裁剪（等同回滚），面板仍展示真实构成。
 */
@Component
@ConfigurationProperties(prefix = "agent.context")
public class ContextProperties {

    /** 总开关：false = 不裁剪（保留观测） */
    private boolean enabled = true;

    /** 裁剪策略：balanced | history-priority */
    private String strategy = "balanced";

    /** 四段合计的输入 token 预算（不含模型输出） */
    private int totalBudgetTokens = 10000;

    /** system 段预算（受保护，超限只标记） */
    private int systemMaxTokens = 2500;

    /** 摘要段预算（超出硬裁剪） */
    private int summaryMaxTokens = 1200;

    /** 历史段预算（含本轮用户消息；超出从最旧消息开始丢弃） */
    private int historyMaxTokens = 4500;

    /** 检索片段预算（含引用规则固定开销；超出按排序丢弃尾部片段） */
    private int retrievalMaxTokens = 2500;

    /** 历史段至少保留的最近消息条数（即便超预算也不再丢，防止上下文完全失忆） */
    private int minHistoryMessages = 2;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public int getTotalBudgetTokens() { return totalBudgetTokens; }
    public void setTotalBudgetTokens(int totalBudgetTokens) { this.totalBudgetTokens = totalBudgetTokens; }
    public int getSystemMaxTokens() { return systemMaxTokens; }
    public void setSystemMaxTokens(int systemMaxTokens) { this.systemMaxTokens = systemMaxTokens; }
    public int getSummaryMaxTokens() { return summaryMaxTokens; }
    public void setSummaryMaxTokens(int summaryMaxTokens) { this.summaryMaxTokens = summaryMaxTokens; }
    public int getHistoryMaxTokens() { return historyMaxTokens; }
    public void setHistoryMaxTokens(int historyMaxTokens) { this.historyMaxTokens = historyMaxTokens; }
    public int getRetrievalMaxTokens() { return retrievalMaxTokens; }
    public void setRetrievalMaxTokens(int retrievalMaxTokens) { this.retrievalMaxTokens = retrievalMaxTokens; }
    public int getMinHistoryMessages() { return minHistoryMessages; }
    public void setMinHistoryMessages(int minHistoryMessages) { this.minHistoryMessages = minHistoryMessages; }
}
