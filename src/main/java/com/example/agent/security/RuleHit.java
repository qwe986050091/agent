package com.example.agent.security;

/**
 * 一条命中的注入检测规则（P4-17）。
 *
 * @param ruleId   规则编号（R1–R8，见 {@link InjectionRuleScanner} 权重表）
 * @param category 分类（instruction_override / role_hijack / ...）
 * @param weight   该规则权重（命中即计入总分，同一规则重复命中只计一次）
 * @param excerpt  命中片段（归一化文本中截取，≤80 字符，用于落库复盘）
 */
public record RuleHit(String ruleId, String category, int weight, String excerpt) {
}