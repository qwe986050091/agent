package com.example.agent.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感值登记表（P4-17 §6.3）：启动时从配置/环境读取<b>真实凭据值</b>，供出站 DLP 精确匹配。
 *
 * <p>为什么用真实值而不是正则：正则（如 sk- 前缀）误报高且抓不全；env 实际值的精确匹配
 * 误报面极小，是唯一可放心默认 block 的检查。同时预生成三种编码形态
 * （原文 / base64 / URL-encode）以覆盖编码外传。</p>
 *
 * <p><b>安全约束：</b>登记值与任何匹配结果都不得写入日志/审计/异常文案——
 * 对外只暴露「哪个键命中」的标签（env 变量名），不暴露值本身。</p>
 */
@Component
public class SensitiveValueRegistry {

    /** 注册的凭据来源键（env / 配置属性名；取不到则跳过） */
    static final List<String> SECRET_KEYS = List.of(
            "agent.internal-token",
            "OPENAI_API_KEY",
            "MYSQL_PASSWORD",
            "AGENT_INTERNAL_TOKEN",
            "RAG_PG_PASSWORD");

    /** 太短的値不登记（避免把 "1"、"true" 之类误当敏感值全局误伤） */
    private static final int MIN_LENGTH = 8;

    private record Entry(String label, String pattern) {
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * 生产装配入口。<b>必须标 @Autowired</b>：本类有双构造器（Environment / Map），
     * Spring 对「多构造器且无标注」的 Bean 会回退要求无参构造器 →
     * 「No default constructor found」BeanInstantiationException（2026-10-10 上线 crash-loop 实证）。
     */
    @Autowired
    public SensitiveValueRegistry(Environment env) {
        this(collect(env));
    }

    /** 测试/手工构造：label → 原始值 */
    public SensitiveValueRegistry(Map<String, String> rawByLabel) {
        for (Map.Entry<String, String> e : rawByLabel.entrySet()) {
            addValue(e.getKey(), e.getValue());
        }
    }

    private static Map<String, String> collect(Environment env) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : SECRET_KEYS) {
            String v = env.getProperty(key);
            if (v != null && !v.isBlank()) {
                out.putIfAbsent(key, v);
            }
        }
        return out;
    }

    private void addValue(String label, String raw) {
        if (raw == null || raw.length() < MIN_LENGTH) {
            return;
        }
        addPattern(label, raw);
        try {
            String b64 = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            addPattern(label + "(base64)", b64);
        } catch (Exception ignored) {
            // 编码失败不登记该形态
        }
        try {
            String url = URLEncoder.encode(raw, StandardCharsets.UTF_8);
            if (!url.equals(raw)) {
                addPattern(label + "(urlencode)", url);
            }
        } catch (Exception ignored) {
            // 同上
        }
    }

    private void addPattern(String label, String pattern) {
        if (pattern != null && pattern.length() >= MIN_LENGTH) {
            entries.add(new Entry(label, pattern));
        }
    }

    /** 文本是否包含任一登记凭据（原文/base64/urlencode 三形态）；命中返回登记标签，未命中 null */
    public String detect(String text) {
        if (text == null || text.isEmpty() || entries.isEmpty()) {
            return null;
        }
        for (Entry e : entries) {
            if (text.contains(e.pattern())) {
                return e.label();
            }
        }
        return null;
    }

    /** 登记条目数（启动日志/健康检查用；不暴露值） */
    public int size() {
        return entries.size();
    }
}