package com.example.agent.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测配置（eval.*）。
 *
 * <p>默认 {@code eval.enabled=false}：普通运行（web 模式）绝不会触发评测，
 * 只有显式开启（{@code --spring.profiles.active=eval} 或 {@code --eval.enabled=true}）才跑 CLI。
 */
@Component
@ConfigurationProperties(prefix = "eval")
public class EvalProperties {

    /** 总开关：false 时评测 CLI 不执行（web 模式默认关） */
    private boolean enabled = false;

    /** 数据集位置：classpath: 前缀读 jar 内资源，否则读文件路径 */
    private String dataset = "classpath:eval/dataset/agent-eval-v1.json";

    /** 数据集名（写入报告；留空则用位置串） */
    private String datasetName = "agent-eval-v1";

    /** 调用方式：in-process（同进程直调 AgentFactory）| http（对已部署 agent 打 HTTP） */
    private String mode = "in-process";

    /** http 模式下 agent 服务地址 */
    private String baseUrl = "http://localhost:8080";

    /** http 模式下的服务间鉴权 token（请求头 X-Agent-Token） */
    private String internalToken = "dev-internal-token";

    /** 评测专用隔离用户（记忆/知识库按该 userId 隔离，避免污染真实用户） */
    private String userId = "eval-runner";

    /** 报告输出目录 */
    private String outputDir = "target/eval";

    /** 本次运行标签（用于两次运行对比，如 "rerank-on" / "baseline"） */
    private String label = "run";

    /** 基线报告 JSON 路径（设置后产出与基线的 diff） */
    private String baseline = "";

    /** RAG 评测知识库夹具 JSON 路径（in-process 模式会先把夹具文档索引进评测用户的向量库） */
    private String kbFixture = "";

    /** 是否启用 LLM-as-judge（真实 LLM 评测建议开；CI 离线档恒关） */
    private boolean judgeEnabled = false;

    /** 每条用例重复次数（>1 用多数票抵消抖动；离线档恒 1） */
    private int repetitions = 1;

    /** 上报 backend 的 URL（POST /api/eval/runs）；留空则不上报 */
    private String persistUrl = "";

    /** 上报鉴权 token（请求头 X-Eval-Token） */
    private String persistToken = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDataset() { return dataset; }
    public void setDataset(String dataset) { this.dataset = dataset; }
    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getBaseline() { return baseline; }
    public void setBaseline(String baseline) { this.baseline = baseline; }
    public String getKbFixture() { return kbFixture; }
    public void setKbFixture(String kbFixture) { this.kbFixture = kbFixture; }
    public boolean isJudgeEnabled() { return judgeEnabled; }
    public void setJudgeEnabled(boolean judgeEnabled) { this.judgeEnabled = judgeEnabled; }
    public int getRepetitions() { return repetitions; }
    public void setRepetitions(int repetitions) { this.repetitions = repetitions; }
    public String getPersistUrl() { return persistUrl; }
    public void setPersistUrl(String persistUrl) { this.persistUrl = persistUrl; }
    public String getPersistToken() { return persistToken; }
    public void setPersistToken(String persistToken) { this.persistToken = persistToken; }
}
