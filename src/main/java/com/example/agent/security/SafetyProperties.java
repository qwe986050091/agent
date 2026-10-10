package com.example.agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内容安全配置（agent.safety.*，P4-17）。
 *
 * 设计原则：默认全开但 <b>mode=monitor</b>（只记录不拦截）——
 * 先观察真实流量与误报率，再经 warn 段切到 block（灰度三段）。
 * 关闭总开关（enabled=false）等同完全回滚，不需要回滚 jar。
 */
@Component
@ConfigurationProperties(prefix = "agent.safety")
public class SafetyProperties {

    /** 总开关（false = 完全不检测，等同回滚） */
    private boolean enabled = true;

    /** 处置模式：monitor（只记录）| warn（灰区注入警戒语，不拒绝）| block（≥ 阈值拒绝） */
    private String mode = "monitor";

    /** RAG/工具结果包裹边界 + System Prompt 安全边界声明 */
    private boolean untrustedWrap = true;

    /** 输出通道扫描：off | monitor（v1 只记录，不拦截已发出的流） */
    private String outputScan = "monitor";

    private final Rule rule = new Rule();
    private final LlmClassifier llmClassifier = new LlmClassifier();
    private final Dlp dlp = new Dlp();

    /** 规则评分阈值 */
    public static class Rule {
        /** ≥ 该分数（且 mode=block）：拒绝输入 */
        private int blockThreshold = 8;
        /** ≥ 该分数：灰区（记录 suspicious + 触发 LLM 分类 + 警戒语） */
        private int grayThreshold = 4;

        public int getBlockThreshold() { return blockThreshold; }
        public void setBlockThreshold(int blockThreshold) { this.blockThreshold = blockThreshold; }
        public int getGrayThreshold() { return grayThreshold; }
        public void setGrayThreshold(int grayThreshold) { this.grayThreshold = grayThreshold; }
    }

    /** 灰区 LLM 语义分类器（M4） */
    public static class LlmClassifier {
        private boolean enabled = true;
        /** 判定模型：空 = 复用主模型 */
        private String model = "";
        private int timeoutSeconds = 5;
        /** 输入 hash 结果缓存条数（内存 LRU，抗重复刷） */
        private int cacheSize = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getCacheSize() { return cacheSize; }
        public void setCacheSize(int cacheSize) { this.cacheSize = cacheSize; }
    }

    /** 出站敏感信息防护（M3） */
    public static class Dlp {
        private boolean enabled = true;
        /** 出站请求命中真实凭据（env 加载的实际值）时直接拒绝 */
        private boolean blockOutboundSecrets = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isBlockOutboundSecrets() { return blockOutboundSecrets; }
        public void setBlockOutboundSecrets(boolean blockOutboundSecrets) { this.blockOutboundSecrets = blockOutboundSecrets; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isUntrustedWrap() { return untrustedWrap; }
    public void setUntrustedWrap(boolean untrustedWrap) { this.untrustedWrap = untrustedWrap; }
    public String getOutputScan() { return outputScan; }
    public void setOutputScan(String outputScan) { this.outputScan = outputScan; }
    public Rule getRule() { return rule; }
    public LlmClassifier getLlmClassifier() { return llmClassifier; }
    public Dlp getDlp() { return dlp; }
}