package com.example.agent.observability;

import com.example.agent.observability.model.RagSegment;
import com.example.agent.observability.model.RagTraceEvent;
import com.example.agent.observability.model.SafetyTraceEvent;
import com.example.agent.observability.model.ToolTraceEvent;
import com.example.agent.observability.model.TraceEvent;
import com.example.agent.observability.model.TraceResetEvent;
import com.example.agent.observability.model.UsageTraceEvent;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 单次对话回合的 trace 采集器（请求级，用完即弃，不跨请求保留）。
 *
 * 采集方法均为 {@code synchronized}：单条链在一个工作线程内运行时无竞争；
 * fan-out 并行子代理会在多个工作线程并发写同一个采集器（经
 * {@link TraceContextHolder#bind} 绑定），故所有可变状态的读写都必须串行化，
 * 尤其 {@link #completeTool} 的「按 id 配对」必须整体处于临界区内（否则会错配）。
 *
 * 工具配对原理：LLM 在某轮 onResponse 给出 ToolExecutionRequest，工具执行结果
 * 在下一轮 onRequest 的消息中以 ToolExecutionResultMessage 出现（id 对应），
 * 按 id（id 缺失时 FIFO）配对即可还原参数/结果/成败/近似耗时。
 */
public class TraceCollector {

    private static final Logger log = LoggerFactory.getLogger(TraceCollector.class);

    // 截断长度兼顾信息量与 NFR-1「单事件 <2KB」（中文 UTF-8 3 字节）：
    // rag 最坏 4 片段全命中 + 超长 query：4×100×3 + 150×3 + 结构开销 ≈ 1.95KB
    public static final int MAX_ARGUMENTS = 500;
    public static final int MAX_RESULT = 300;
    public static final int MAX_QUERY = 150;
    public static final int MAX_SEGMENT_TEXT = 100;

    /** 指标水槽缺省空实现（正常路径 Spring 会注入 Micrometer 实现） */
    private static final AgentMetricsSink NOOP_METRICS = new AgentMetricsSink() {
        @Override public void recordLlmCall(String model, boolean success, long durationMs,
                                            Integer inputTokens, Integer outputTokens) { }
        @Override public void recordToolCall(String toolName, boolean success, long durationMs) { }
        @Override public void recordRagRetrieval(boolean hit, int segmentCount) { }
        @Override public void recordSafetyMatch(String channel, String verdict, String action) { }
        @Override public void recordChat(String mode, String outcome, long durationMs) { }
        @Override public void changeSseActive(int delta) { }
    };

    private final String traceId;
    private final String userId;
    private final Long conversationId;
    private final long startNanos = System.nanoTime();
    /** 流式实时事件出口（同步对话为 null）；任何 sink 异常不得影响对话 */
    private final Consumer<TraceEvent> sink;
    /** 指标出口（T1 为 no-op） */
    private final AgentMetricsSink metrics;

    private final List<ToolTraceEvent> tools = new ArrayList<>();
    private final List<RagTraceEvent> retrievals = new ArrayList<>();
    /** 内容安全检测记录（P4-17）：一回合可多条（input/rag/tool_output/output 各通道） */
    private final List<SafetyTraceEvent> safeties = new ArrayList<>();
    /** key = 工具调用 id（id 缺失时用合成序号），等待配对的工具请求（按发生顺序） */
    private final Map<String, PendingTool> pendingTools = new LinkedHashMap();
    /** 已完成配对的工具调用 id（多轮请求会重复携带历史结果，用于去重） */
    private final java.util.Set<String> completedToolIds = new java.util.HashSet<>();
    private int fifoSeq = 0;

    private int rounds = 0;
    private int inputTokens = 0;
    private int outputTokens = 0;
    private int totalTokens = 0;
    private boolean usagePresent = false;
    private String model;
    private boolean fallback = false;
    private int llmErrors = 0;
    /** 本回合子代理委派次数（Agent-as-Tool 的「全局预算」计数：多子代理 fan-out 的成本闸门） */
    private int delegations = 0;

    private TraceCollector(String traceId, String userId, Long conversationId,
                           Consumer<TraceEvent> sink, AgentMetricsSink metrics) {
        this.traceId = traceId;
        this.userId = userId;
        this.conversationId = conversationId;
        this.sink = sink;
        this.metrics = metrics == null ? NOOP_METRICS : metrics;
    }

    /** 创建采集器（注册到线程由 {@link TraceContextHolder#start} 负责） */
    static TraceCollector start(String traceId, String userId, Long conversationId,
                                Consumer<TraceEvent> sink, AgentMetricsSink metrics) {
        return new TraceCollector(traceId, userId, conversationId, sink, metrics);
    }

    public String traceId() { return traceId; }
    public String userId() { return userId; }
    public Long conversationId() { return conversationId; }

    /**
     * 主模型失败、整条链即将用备用模型重放前调用：
     * 标记 fallback 并清空首轮已采集内容（失败尝试可能已真实执行工具/触发 RAG），
     * 同时向 live sink 发 trace-reset，让后端/前端清空已累积数据，避免重复审计/重复面板。
     */
    public synchronized void prepareForFallback() {
        this.fallback = true;
        this.rounds = 0;
        this.inputTokens = 0;
        this.outputTokens = 0;
        this.totalTokens = 0;
        this.usagePresent = false;
        this.model = null;
        this.llmErrors = 0;
        this.delegations = 0;
        this.pendingTools.clear();
        this.completedToolIds.clear();
        this.fifoSeq = 0;
        this.tools.clear();
        this.retrievals.clear();
        this.safeties.clear();
        emit(new TraceResetEvent());
    }

    /** 记录一次完成的 LLM 调用：轮次 +1，累加 token 用量，更新模型名 */
    public synchronized void recordLlmResponse(TokenUsage usage, String modelName) {
        rounds++;
        if (modelName != null && !modelName.isBlank()) {
            this.model = modelName;
        }
        if (usage != null) {
            usagePresent = true;
            inputTokens += nz(usage.inputTokenCount());
            outputTokens += nz(usage.outputTokenCount());
            // 优先用 total；个别提供商 total 缺失时退化为 input+output
            int total = nz(usage.totalTokenCount());
            totalTokens += total > 0 ? total : nz(usage.inputTokenCount()) + nz(usage.outputTokenCount());
        }
    }

    public synchronized void recordLlmError() {
        llmErrors++;
    }

    /** LLM 决定调用工具：按请求登记待配对项（round 为当前已完成轮次；agentId 记录归属的 Agent） */
    public synchronized void pendTool(ToolExecutionRequest request) {
        if (request == null || request.name() == null) {
            return;
        }
        String key = request.id() != null && !request.id().isBlank()
                ? request.id() : ("fifo-" + (fifoSeq++));
        pendingTools.put(key, new PendingTool(request, rounds, System.nanoTime(), AgentScope.current()));
    }

    /** 工具执行结果回到下一轮请求时配对并产出事件 */
    public synchronized void completeTool(ToolExecutionResultMessage result) {
        if (result == null) {
            return;
        }
        // 多轮工具循环中，后续请求会携带全部历史 ToolExecutionResultMessage，
        // 每轮都对消息列表全量扫描 → 已配对过的结果必须跳过，否则 FIFO 兜底会把
        // 旧结果错配到新请求上（线上冒烟实测：multiply/divide 都被错配成 add 的结果）
        if (result.id() != null && !result.id().isBlank() && completedToolIds.contains(result.id())) {
            return;
        }
        PendingTool pending = null;
        if (result.id() != null && !result.id().isBlank()) {
            pending = pendingTools.remove(result.id());
            if (pending != null) {
                completedToolIds.add(result.id());
            }
        }
        if (pending == null && (result.id() == null || result.id().isBlank())
                && !pendingTools.isEmpty()) {
            // 仅当结果真的没有 id 时才用 FIFO 兜底（有 id 但匹配不上的旧结果直接忽略）
            String firstKey = pendingTools.keySet().iterator().next();
            pending = pendingTools.remove(firstKey);
        }
        if (pending == null) {
            log.debug("[Trace] 工具结果无配对请求，忽略: id={} name={}", result.id(), result.toolName());
            return;
        }

        String name = pending.request.name();
        boolean error = Boolean.TRUE.equals(result.isError());
        String text = result.text() == null ? "" : result.text();
        String errorText = error ? truncate(text, MAX_RESULT) : null;
        String resultPreview = error ? null : truncate(text, MAX_RESULT);
        ToolTraceEvent event = new ToolTraceEvent(
                pending.round,
                pending.request.id(),
                name,
                truncate(pending.request.arguments(), MAX_ARGUMENTS),
                resultPreview,
                !error,
                errorText,
                (System.nanoTime() - pending.startNanos) / 1_000_000L,
                pending.agentId);
        tools.add(event);
        emit(event);
        try {
            metrics.recordToolCall(name, !error, event.durationMs());
        } catch (Exception ignore) {
            // 指标异常不影响对话
        }
    }

    /** 记录一次 RAG 检索（query + 召回片段），无命中时 segments 为空 */
    public synchronized void recordRag(String query, List<RagSegment> segments) {
        List<RagSegment> copy = List.copyOf(segments);
        RagTraceEvent event = new RagTraceEvent(truncate(query, MAX_QUERY), copy);
        retrievals.add(event);
        emit(event);
        try {
            metrics.recordRagRetrieval(!copy.isEmpty(), copy.size());
        } catch (Exception ignore) {
            // 指标异常不影响对话
        }
    }

    /** 记录一次内容安全检测（P4-17）；同时打指标，供监控期统计各通道命中率 */
    public synchronized void recordSafety(SafetyTraceEvent event) {
        if (event == null) {
            return;
        }
        safeties.add(event);
        emit(event);
        try {
            metrics.recordSafetyMatch(event.channel(), event.verdict(), event.action());
        } catch (Exception ignore) {
            // 指标异常不影响对话
        }
    }

    /** 组装回合末用量事件 */
    public synchronized UsageTraceEvent toUsageEvent(boolean cancelled) {
        return new UsageTraceEvent(
                model,
                inputTokens,
                outputTokens,
                totalTokens,
                rounds,
                fallback,
                cancelled,
                (System.nanoTime() - startNanos) / 1_000_000L);
    }

    public synchronized boolean usagePresent() { return usagePresent; }
    public synchronized int llmErrors() { return llmErrors; }

    /** 本回合已发生的子代理委派次数（Agent-as-Tool 全局预算：与单循环轮次上限共同约束总成本） */
    public synchronized int delegations() { return delegations; }

    /** 记录一次子代理委派（由 SubAgentToolProvider / FanoutToolProvider 在委派前调用） */
    public synchronized void recordDelegation() { delegations++; }

    public synchronized List<ToolTraceEvent> tools() { return List.copyOf(tools); }
    public synchronized List<RagTraceEvent> retrievals() { return List.copyOf(retrievals); }
    public synchronized List<SafetyTraceEvent> safeties() { return List.copyOf(safeties); }

    private void emit(TraceEvent event) {
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (Exception e) {
            // 实时发送失败（如连接已断）不影响采集与对话
            log.debug("[Trace] 事件发送失败（不影响对话）: {}", e.getMessage());
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 待配对的工具请求（agentId 为登记时所在 Agent 作用域，用于 trace 嵌套归属） */
    private record PendingTool(ToolExecutionRequest request, int round, long startNanos, String agentId) {
    }
}
