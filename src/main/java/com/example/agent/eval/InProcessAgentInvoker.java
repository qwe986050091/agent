package com.example.agent.eval;

import com.example.agent.agent.AgentFactory;
import com.example.agent.approval.ApprovalContextHolder;
import com.example.agent.approval.ApprovalGate;
import com.example.agent.approval.ApprovalRegistry;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.observability.model.SafetyTraceEvent;
import com.example.agent.security.InjectionRuleScanner;
import com.example.agent.security.SafetyContextHolder;
import com.example.agent.security.SafetyProperties;
import com.example.agent.security.ScanResult;
import com.example.agent.skill.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 同进程直接调用 {@code AgentFactory} 的评测调用器（评测首选方式）。
 *
 * <p>为什么就近调用而不是走 HTTP：直接复用 agent 既有的 {@link AgentFactory} 与
 * {@link TraceCollector}——工具选择、RAG 召回、内容安全、token 用量四种轨迹一次拿全，
 * 且无需另外起一个服务；这正是「runner 复用现有 TraceCollector」的落地。
 *
 * <p><b>与生产路径的差异（诚实标注）：</b>输入侧安全闸门只跑 L1 规则引擎（
 * {@link InjectionRuleScanner}），不跑 L2 LLM 语义分类器——后者会引入非确定性且多一次模型调用。
 * 评测 profile 把 {@code agent.safety.mode} 设为 block、分类器关闭（规则单闸），
 * 等价于生产在「分类器显式关闭」下的行为，安全用例因此可确定性复现。
 */
public class InProcessAgentInvoker implements AgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(InProcessAgentInvoker.class);

    private final AgentFactory agentFactory;
    private final ApprovalRegistry approvalRegistry;
    private final InjectionRuleScanner injectionRuleScanner;
    private final SafetyProperties safetyProperties;
    private final long approvalTimeoutSeconds;
    private final String modelName;

    public InProcessAgentInvoker(AgentFactory agentFactory,
                                 ApprovalRegistry approvalRegistry,
                                 InjectionRuleScanner injectionRuleScanner,
                                 SafetyProperties safetyProperties,
                                 long approvalTimeoutSeconds,
                                 String modelName) {
        this.agentFactory = agentFactory;
        this.approvalRegistry = approvalRegistry;
        this.injectionRuleScanner = injectionRuleScanner;
        this.safetyProperties = safetyProperties;
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
        this.modelName = modelName == null ? "" : modelName;
    }

    @Override
    public String describe() {
        return "in-process";
    }

    @Override
    public String modelName() {
        return modelName;
    }

    @Override
    public EvalTurn invoke(EvalCase evalCase, String userId) {
        String traceId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        // 同步通道：live sink=null、metrics=null（评测不关心实时事件与指标，回合末统一读 collector）
        TraceCollector collector = TraceContextHolder.start(traceId, userId, 0L, null, null);
        try {
            ScanResult scan = scanInput(evalCase.input());
            SafetyTraceEvent safetyEvent = toSafetyEvent(scan);
            if (safetyEvent != null) {
                collector.recordSafety(safetyEvent);
            }
            if (blocked(scan)) {
                log.info("[Eval] 用例 {} 被输入安全闸门阻断（得分 {}）", evalCase.id(), scan.score());
                return new EvalTurn(traceId, "", null, List.of(), List.of(), collector.safeties(),
                        collector.toUsageEvent(false), true, null, elapsedMs(start));
            }

            // 预授权该用例期望调用的工具，避免人工审批闸门挡住评测
            Set<String> allowed = new HashSet<>(evalCase.allowedTools().isEmpty()
                    ? evalCase.expectedTools() : evalCase.allowedTools());
            ApprovalContextHolder.start(new ApprovalGate(approvalRegistry, approvalTimeoutSeconds,
                    userId, 0L, traceId, allowed, null));

            AgentFactory.AgentChatRequest request = new AgentFactory.AgentChatRequest(
                    userId, 0L, evalCase.input(), null, List.of(),
                    skillDefinitions(evalCase), List.copyOf(allowed), List.of());
            String answer;
            String summary;
            try {
                AgentFactory.AgentChatResult result = agentFactory.chat(request);
                answer = result.answer();
                summary = result.summary();
            } catch (Exception e) {
                log.warn("[Eval] 用例 {} 执行失败: {}", evalCase.id(), e.getMessage());
                return new EvalTurn(traceId, "", null, collector.tools(), collector.retrievals(),
                        collector.safeties(), collector.toUsageEvent(false), false,
                        e.getMessage(), elapsedMs(start));
            }
            return new EvalTurn(traceId, answer, summary, collector.tools(), collector.retrievals(),
                    collector.safeties(), collector.toUsageEvent(false), false, null, elapsedMs(start));
        } finally {
            ApprovalContextHolder.clear();
            SafetyContextHolder.clear();
            TraceContextHolder.clear();
        }
    }

    /** 评测的输入扫描：只跑 L1 规则引擎（确定性） */
    private ScanResult scanInput(String input) {
        if (!safetyProperties.isEnabled() || input == null || input.isBlank()) {
            return ScanResult.clean();
        }
        return injectionRuleScanner.scan(input);
    }

    /** 是否阻断：mode=block 且规则分达到阈值且未启用 L2 分类器（规则单闸，确定性） */
    private boolean blocked(ScanResult scan) {
        if (scan == null || scan.isClean()) {
            return false;
        }
        String mode = safetyProperties.getMode() == null ? "monitor"
                : safetyProperties.getMode().trim().toLowerCase();
        boolean classifierEnabled = safetyProperties.getLlmClassifier().isEnabled();
        return "block".equals(mode)
                && scan.score() >= safetyProperties.getRule().getBlockThreshold()
                && !classifierEnabled;
    }

    private static SafetyTraceEvent toSafetyEvent(ScanResult scan) {
        if (scan == null || scan.isClean()) {
            return null;
        }
        List<SafetyTraceEvent.RuleHitView> views = scan.hits().stream()
                .map(h -> new SafetyTraceEvent.RuleHitView(h.ruleId(), h.category(), h.weight()))
                .toList();
        return new SafetyTraceEvent("input", scan.score(), "suspicious", "rule", "none",
                null, views, scan.topExcerpt(), 0L);
    }

    /** 用例挂载的技能 → 只填 name 的 SkillDefinition（内置技能以代码为准，命中内置名即可） */
    private static List<SkillDefinition> skillDefinitions(EvalCase evalCase) {
        return evalCase.skills().stream().map(name -> {
            SkillDefinition def = new SkillDefinition();
            def.setName(name);
            return def;
        }).toList();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
