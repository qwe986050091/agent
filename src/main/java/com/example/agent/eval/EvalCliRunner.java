package com.example.agent.eval;

import com.example.agent.agent.AgentFactory;
import com.example.agent.approval.ApprovalRegistry;
import com.example.agent.rag.RagService;
import com.example.agent.security.InjectionRuleScanner;
import com.example.agent.security.SafetyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * 评测 CLI：{@code java -jar agent.jar --spring.profiles.active=eval}（或 {@code --eval.enabled=true}）。
 *
 * <p>流程：加载数据集 →（in-process 时可选索引进 RAG 夹具知识库）→ 逐例调用/打分 → 出报告
 * →（可选）与基线 diff →（可选）上报 backend 落库 → 打印控制台摘要。
 *
 * <p>默认关闭（{@code eval.enabled=false}），普通 web 运行绝不会触发。
 */
@Component
@ConditionalOnProperty(prefix = "eval", name = "enabled", havingValue = "true")
public class EvalCliRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalCliRunner.class);

    private final EvalProperties props;
    private final ObjectMapper mapper;
    private final Environment environment;
    private final AgentFactory agentFactory;
    private final ApprovalRegistry approvalRegistry;
    private final InjectionRuleScanner injectionRuleScanner;
    private final SafetyProperties safetyProperties;
    private final ObjectProvider<RagService> ragServiceProvider;
    private final ChatModel chatModel;

    @Value("${agent.approval.timeout-seconds:60}")
    private long approvalTimeoutSeconds;

    public EvalCliRunner(EvalProperties props,
                         ObjectMapper mapper,
                         Environment environment,
                         AgentFactory agentFactory,
                         ApprovalRegistry approvalRegistry,
                         InjectionRuleScanner injectionRuleScanner,
                         SafetyProperties safetyProperties,
                         ObjectProvider<RagService> ragServiceProvider,
                         ChatModel chatModel) {
        this.props = props;
        this.mapper = mapper;
        this.environment = environment;
        this.agentFactory = agentFactory;
        this.approvalRegistry = approvalRegistry;
        this.injectionRuleScanner = injectionRuleScanner;
        this.safetyProperties = safetyProperties;
        this.ragServiceProvider = ragServiceProvider;
        this.chatModel = chatModel;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String model = environment.getProperty("langchain4j.open-ai.chat-model.model-name", "");
        EvalDataset dataset = EvalDataset.load(props.getDataset(), props.getDatasetName(), mapper);
        log.info("[Eval] 加载数据集 {}：{} 条用例", dataset.name(), dataset.size());

        AgentInvoker invoker = buildInvoker(model);
        seedKnowledgeBase(invoker);

        JudgeScorer judge = props.isJudgeEnabled() ? new LlmJudgeScorer(chatModel) : JudgeScorer.DISABLED;
        EvalReport report = new EvalRunner(invoker, judge, props.getUserId(), props.getRepetitions())
                .run(dataset, props.getLabel(), props.isJudgeEnabled());

        RunDiff diff = loadBaseline(report);
        Path dir = Path.of(props.getOutputDir());
        String baseName = "report-" + report.runId() + "-" + props.getLabel();
        Path jsonFile = dir.resolve(baseName + ".json");
        Path mdFile = dir.resolve(baseName + ".md");
        EvalReportWriter.writeJson(jsonFile, report, mapper);
        EvalReportWriter.writeMarkdown(mdFile, report, diff);

        persist(report);

        printSummary(report, diff, jsonFile, mdFile);
    }

    private AgentInvoker buildInvoker(String model) {
        if ("http".equalsIgnoreCase(props.getMode())) {
            log.info("[Eval] 调用方式：http -> {}", props.getBaseUrl());
            return new HttpAgentInvoker(props.getBaseUrl(), props.getInternalToken(), mapper);
        }
        log.info("[Eval] 调用方式：in-process（就近复用 AgentFactory + TraceCollector）");
        return new InProcessAgentInvoker(agentFactory, approvalRegistry, injectionRuleScanner,
                safetyProperties, approvalTimeoutSeconds, model);
    }

    /** in-process 模式：把 RAG 评测夹具文档索引进评测用户的向量库，保证 rag_qa 用例可复现 */
    private void seedKnowledgeBase(AgentInvoker invoker) throws Exception {
        if (props.getKbFixture() == null || props.getKbFixture().isBlank()) {
            return;
        }
        if (!(invoker instanceof InProcessAgentInvoker)) {
            log.warn("[Eval] http 模式下不自动播种知识库夹具，请确认目标环境已索引相应文档");
            return;
        }
        RagService rag = ragServiceProvider.getIfAvailable();
        if (rag == null || !rag.available()) {
            log.warn("[Eval] RAG 不可用，跳过知识库夹具播种（rag_qa 用例将无法命中）");
            return;
        }
        KbFixture fixture = readFixture(props.getKbFixture());
        for (KbDoc doc : fixture.docs()) {
            int chunks = rag.indexDocument(props.getUserId(), doc.docId(), doc.name(), doc.text());
            log.info("[Eval] 播种知识库 《{}》（docId={}）：{} 个片段", doc.name(), doc.docId(), chunks);
        }
    }

    /** 读取知识库夹具：支持 classpath: 前缀（打进 jar）与普通文件路径 */
    private KbFixture readFixture(String location) throws Exception {
        if (location.startsWith("classpath:")) {
            try (var in = new org.springframework.core.io.ClassPathResource(
                    location.substring("classpath:".length())).getInputStream()) {
                return mapper.readValue(in, KbFixture.class);
            }
        }
        return mapper.readValue(Path.of(location).toFile(), KbFixture.class);
    }

    private RunDiff loadBaseline(EvalReport report) {
        if (props.getBaseline() == null || props.getBaseline().isBlank()) {
            return null;
        }
        try {
            EvalReport baseline = mapper.readValue(Path.of(props.getBaseline()).toFile(), EvalReport.class);
            return RunDiff.compare(baseline, report);
        } catch (Exception e) {
            log.warn("[Eval] 基线报告读取失败（跳过 diff）: {}", e.getMessage());
            return null;
        }
    }

    private void persist(EvalReport report) {
        if (props.getPersistUrl() == null || props.getPersistUrl().isBlank()) {
            return;
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.getPersistUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Eval-Token", props.getPersistToken())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(report)))
                    .build();
            HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("[Eval] 上报 backend：HTTP {} {}", resp.statusCode(), resp.body());
        } catch (Exception e) {
            log.warn("[Eval] 上报 backend 失败（不影响本次评测）: {}", e.getMessage());
        }
    }

    private void printSummary(EvalReport report, RunDiff diff, Path jsonFile, Path mdFile) {
        EvalMetrics m = report.metrics();
        StringBuilder sb = new StringBuilder("\n");
        sb.append("==================== Agent 评测结果 ====================\n");
        sb.append("运行 ID : ").append(report.runId()).append("（").append(report.label()).append("）\n");
        sb.append("数据集  : ").append(report.datasetName()).append(" / ").append(m.total()).append(" 条\n");
        sb.append(String.format("通过率  : %.1f%%（%d/%d）%n", m.passRate() * 100, m.passed(), m.total()));
        sb.append("工具选择正确率 : ").append(pct(m.toolAccuracy(), m.toolChecked())).append('\n');
        sb.append("引用正确率     : ").append(pct(m.citationAccuracy(), m.citationChecked())).append('\n');
        sb.append("要点覆盖率     : ").append(pct(m.pointCoverage(), m.pointChecked())).append('\n');
        sb.append("安全拒答率     : ").append(pct(m.refusalRate(), m.refusalChecked())).append('\n');
        if (diff != null) {
            sb.append("对比基线 ").append(diff.baseRunId())
                    .append("：修复 ").append(diff.fixedCases().size())
                    .append(" 例，回归 ").append(diff.brokenCases().size()).append(" 例\n");
            if (diff.hasRegression()) {
                sb.append("⚠️ 出现回归用例: ").append(String.join(", ", diff.brokenCases())).append('\n');
            }
        }
        sb.append("报告    : ").append(mdFile.toAbsolutePath()).append("\n");
        sb.append("        ").append(jsonFile.toAbsolutePath()).append("\n");
        sb.append("=======================================================");
        System.out.println(sb);
    }

    private static String pct(Double ratio, int sample) {
        return sample == 0 ? "—（无样本）" : String.format("%.1f%%（%d 例）", ratio * 100, sample);
    }

    /** RAG 评测知识库夹具：{@code {"docs":[{"docId","name","text"}]}} */
    record KbFixture(List<KbDoc> docs) {
        KbFixture {
            docs = docs == null ? List.of() : List.copyOf(docs);
        }
    }

    record KbDoc(String docId, String name, String text) {
    }
}
