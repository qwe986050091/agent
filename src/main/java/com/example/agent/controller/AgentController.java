package com.example.agent.controller;

import com.example.agent.agent.AgentFactory;
import com.example.agent.agent.SummarizingChatMemory;
import com.example.agent.approval.ApprovalContextHolder;
import com.example.agent.approval.ApprovalDeniedException;
import com.example.agent.approval.ApprovalGate;
import com.example.agent.approval.ApprovalRegistry;
import com.example.agent.approval.ApprovalRequest;
import com.example.agent.observability.AgentMetricsSink;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.observability.TraceMdcFilter;
import com.example.agent.observability.model.ToolTraceEvent;
import com.example.agent.observability.model.TraceEvent;
import com.example.agent.observability.model.UsageTraceEvent;
import com.example.agent.skill.Skill;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.skill.SkillManager;
import com.example.agent.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Agent REST API（无状态版：每次请求自带记忆状态与技能定义）
 *
 * 接口一览：
 *   GET  /api/agent/health            健康检查
 *   GET  /api/agent/chat              对话（GET，无历史的便捷测试入口）
 *   POST /api/agent/chat              对话（POST，body 携带 summary/history/skills）
 *   POST /api/agent/chat/stream       流式对话（SSE）；token 事件逐片段推送，
 *                                     过程中发 tool/rag 事件，结束前发 usage、memory，再 data: [DONE]
 *   GET  /api/agent/tools             列出平台内置可用 Tool 名称
 *
 * 可观测：traceId 由 {@link TraceMdcFilter} 入站确定；本控制器在工作线程开启
 * {@link TraceCollector}（ThreadLocal），listener/RAG 采集的事件经 SSE 命名事件
 * 实时回传 backend，回合末随同步 JSON 或 usage 事件返回聚合用量。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** SSE 单次对话最长时间（5 分钟），超时主动结束，避免线程/连接被无限占用 */
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    /**
     * SSE 流式对话的工作线程池（TokenStream.start() 为阻塞调用）。
     * 有界：core 8 / max 24 / 队列 100，满载 AbortPolicy 拒绝（返回 503，快速失败而非无限堆积）。
     */
    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(
            8, 24, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "agent-sse-stream-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdown();
    }

    private final AgentFactory agentFactory;
    private final ToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final AgentMetricsSink metrics;
    private final ApprovalRegistry approvalRegistry;

    /** 高危工具审批等待时长（agent.approval.timeout-seconds），超时自动拒绝 */
    @org.springframework.beans.factory.annotation.Value("${agent.approval.timeout-seconds:60}")
    private long approvalTimeoutSeconds;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentController(AgentFactory agentFactory, ToolRegistry toolRegistry,
                           SkillManager skillManager,
                           AgentMetricsSink metrics, ApprovalRegistry approvalRegistry) {
        this.agentFactory = agentFactory;
        this.toolRegistry = toolRegistry;
        this.skillManager = skillManager;
        this.metrics = metrics;
        this.approvalRegistry = approvalRegistry;
    }

    // ==================== 对话接口 ====================

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(HttpServletRequest httpRequest,
                                     @RequestParam(defaultValue = "default") String userId,
                                     @RequestParam String message) {
        ResponseEntity<?> bad = requireMessage(message);
        if (bad != null) return bad;
        // GET 便捷入口：不带历史/技能，纯无状态对话（默认会话 0，无会话级工具授权）
        AgentFactory.AgentChatRequest req =
                new AgentFactory.AgentChatRequest(userId, 0L, message, null, List.of(), List.of(), List.of());
        return runChat(req, traceIdOf(httpRequest));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chatPost(HttpServletRequest httpRequest, @RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "请求体为空"));
        }
        request.normalize();
        ResponseEntity<?> bad = requireMessage(request.getMessage());
        if (bad != null) return bad;
        return runChat(request.toAgentRequest(), traceIdOf(httpRequest));
    }

    private ResponseEntity<?> runChat(AgentFactory.AgentChatRequest req, String traceId) {
        // 同步对话整条 AiServices 链在 Tomcat 线程执行：直接在当前线程开启采集
        TraceCollector collector = TraceContextHolder.start(
                traceId, req.userId(), req.conversationId(), null, metrics);
        // 同步通道无 SSE 确认出口：gate 存在但 sink=null，高危工具一律自动拒绝
        ApprovalContextHolder.start(new ApprovalGate(
                approvalRegistry, approvalTimeoutSeconds, req.userId(), req.conversationId(), traceId,
                req.allowedTools() == null ? Set.of() : new HashSet<>(req.allowedTools()), null));
        long start = System.nanoTime();
        try {
            AgentFactory.AgentChatResult result;
            try {
                result = agentFactory.chat(req);
            } catch (IllegalArgumentException e) {
                // Skill 定义引用了不存在的 Tool 等数据不一致问题
                metrics.recordChat("sync", "error", elapsedMs(start));
                return ResponseEntity.badRequest().body(new ErrorResponse("SKILL_INVALID", e.getMessage()));
            } catch (Exception e) {
                if (AgentFactory.isToolLoopExceeded(e)) {
                    metrics.recordChat("sync", "converged", elapsedMs(start));
                    log.warn("[chat] 工具调用轮次超限，收敛回复: {}", e.getMessage());
                    return ResponseEntity.ok(buildResponse(req, collector,
                            TOOL_LOOP_LIMIT_MESSAGE, req.summary()));
                }
                metrics.recordChat("sync", "error", elapsedMs(start));
                log.error("[chat] 对话处理失败", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(new ErrorResponse("CHAT_FAILED", "对话处理失败: " + e.getMessage()));
            }
            metrics.recordChat("sync", "success", elapsedMs(start));
            return ResponseEntity.ok(buildResponse(req, collector, result.answer(), result.summary()));
        } finally {
            ApprovalContextHolder.clear();
            TraceContextHolder.clear();
        }
    }

    /** 构造同步对话响应（含 traceId/usage/tools/retrievals） */
    private ChatResponse buildResponse(AgentFactory.AgentChatRequest req, TraceCollector collector,
                                       String answer, String summary) {
        UsageTraceEvent usage = collector.toUsageEvent(false);
        return new ChatResponse(req.userId(), req.message(), answer, summary,
                collector.traceId(), usage, collector.tools(), collector.retrievals());
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 流式对话（SSE）：逐 token 推送
     *
     * 事件格式（标准 text/event-stream）：
     *   data: token                ← 每个 token 一个默认事件
     *   event: tool  data: {...}   ← 工具调用（参数/结果/成败/耗时）
     *   event: rag   data: {...}   ← RAG 召回片段（来源/文本/相似度）
     *   event: usage data: {...}   ← 整轮聚合 token 用量/模型/轮次/耗时
     *   event: approval-request    ← 高危工具执行前请求用户确认（工具线程阻塞等待决策）
     *   event: memory data: {...}  ← 回合摘要（backend 落库用，不转发前端）
     *   data: [DONE]               ← 结束标记
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chatStream(HttpServletRequest httpRequest, @RequestBody(required = false) ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "请求体为空"));
        }
        request.normalize();
        if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("EMPTY_MESSAGE", "参数 message 不能为空或纯空白字符"));
        }

        // 5 分钟超时：超长回复/僵死 LLM 调用不会无限占用线程与连接
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        // 请求级取消状态：客户端断开/超时 → 置位并中断流式执行线程，让 LLM 生成真正终止
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<Thread> streamThread = new AtomicReference<>();
        // 审批闸门引用（工作线程创建后回填）：断连/超时/完成时拒绝全部待审批，工具线程不悬挂
        AtomicReference<ApprovalGate> gateRef = new AtomicReference<>();

        String traceId = traceIdOf(httpRequest);
        long acceptedAt = System.nanoTime();

        emitter.onTimeout(() -> {
            log.warn("[chatStream] SSE 超过 {}ms 未完成，主动断开并取消生成", SSE_TIMEOUT_MS);
            cancelled.set(true);
            ApprovalGate gate = gateRef.get();
            if (gate != null) gate.denyAllPending();
            Thread t = streamThread.get();
            if (t != null) t.interrupt();
            emitter.completeWithError(new TimeoutException("响应超时，请重试"));
        });
        emitter.onError(e -> {
            // 客户端断开 / 发送失败：取消正在进行的 LLM 生成（中断静默期阻塞的读取）
            log.warn("[chatStream] SSE 连接异常（{}），取消生成",
                    e == null ? "客户端断开" : e.getMessage());
            cancelled.set(true);
            ApprovalGate gate = gateRef.get();
            if (gate != null) gate.denyAllPending();
            Thread t = streamThread.get();
            if (t != null) t.interrupt();
        });
        emitter.onCompletion(() -> {
            // 正常结束（complete 已由流线程调用）；兜底置位，避免任何残留回调继续发送
            cancelled.set(true);
            ApprovalGate gate = gateRef.get();
            if (gate != null) gate.denyAllPending();
        });

        final AgentFactory.AgentChatRequest req;
        try {
            req = request.toAgentRequest();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("SKILL_INVALID", e.getMessage()));
        }

        try {
            metrics.changeSseActive(+1);
            streamExecutor.execute(() -> runStream(req, emitter, cancelled, streamThread, gateRef, traceId));
        } catch (RejectedExecutionException e) {
            // 线程池/队列已满：快速失败，不排队堆积
            metrics.changeSseActive(-1);
            metrics.recordChat("stream", "busy", elapsedMs(acceptedAt));
            log.warn("[chatStream] 流式线程池已满，拒绝请求");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SERVICE_BUSY", "当前对话繁忙，请稍后重试"));
        }

        return emitter;
    }

    // ==================== 流式执行（护栏：取消传播 / fallback / 轮次收敛 / trace） ====================

    /** 工具调用轮次超限时返回给用户的收敛提示 */
    private static final String TOOL_LOOP_LIMIT_MESSAGE =
            "已达到最大工具调用轮次，已自动中止以避免死循环。请尝试更简单的指令，或减少连续工具调用。";

    /**
     * 在流式线程池中执行一次流式对话。在工作线程开启请求级 trace 采集，
     * live sink 把 tool/rag 事件即时发为 SSE 命名事件。
     */
    private void runStream(AgentFactory.AgentChatRequest req, SseEmitter emitter,
                           AtomicBoolean cancelled, AtomicReference<Thread> streamThread,
                           AtomicReference<ApprovalGate> gateRef, String traceId) {
        streamThread.set(Thread.currentThread());
        TraceCollector collector = TraceContextHolder.start(
                traceId, req.userId(), req.conversationId(),
                event -> emitTraceEvent(emitter, cancelled, event), metrics);
        // 请求级审批闸门：sink 把审批请求实时发为 SSE 命名事件，@Tool 方法在本工作线程内阻塞等决策
        ApprovalGate gate = new ApprovalGate(
                approvalRegistry, approvalTimeoutSeconds, req.userId(), req.conversationId(), traceId,
                req.allowedTools() == null ? Set.of() : new HashSet<>(req.allowedTools()),
                approvalRequest -> emitApprovalRequest(emitter, cancelled, approvalRequest));
        gateRef.set(gate);
        ApprovalContextHolder.start(gate);
        long start = System.nanoTime();
        try {
            runStreamWithModel(req, emitter, cancelled, collector, false, start, gate);
        } finally {
            ApprovalContextHolder.clear();
            TraceContextHolder.clear();
            metrics.changeSseActive(-1);
        }
    }

    /** 发送高危工具审批请求事件（approval-request）；连接已取消/发送失败时抛异常阻止工具继续 */
    private void emitApprovalRequest(SseEmitter emitter, AtomicBoolean cancelled,
                                     ApprovalRequest approvalRequest) {
        if (cancelled.get()) {
            throw new ApprovalDeniedException("连接已取消，审批请求无法下发");
        }
        try {
            emitter.send(SseEmitter.event()
                    .name("approval-request")
                    .data(objectMapper.writeValueAsString(approvalRequest), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new ApprovalDeniedException("审批请求下发失败（连接可能已断开）");
        }
    }

    /**
     * 用指定（主/备用）流式模型执行对话，带四类护栏：
     * 1. 取消传播：客户端断开（cancelled 置位）后，token 回调抛异常终止生成；
     * 2. 备用模型降级：主模型在「未输出任何 token」前失败 → 用备用模型重跑一次（避免重复输出）；
     * 3. 工具轮次超限：收敛为友好提示而非报错；
     * 4. trace 采集：tool/rag 即时事件 + 回合末 usage 事件 + outcome 指标（仅记一次）。
     */
    private void runStreamWithModel(AgentFactory.AgentChatRequest req, SseEmitter emitter,
                                    AtomicBoolean cancelled, TraceCollector collector,
                                    boolean useFallback, long start, ApprovalGate gate) {
        if (useFallback) {
            // 重放前清空首轮 trace 并通知 backend/前端 reset（工具可能已在首轮真实执行）
            collector.prepareForFallback();
        }
        AtomicBoolean emitted = new AtomicBoolean(false);
        AtomicBoolean fallbackUsed = new AtomicBoolean(useFallback);
        AtomicBoolean outcomeRecorded = new AtomicBoolean(false);

        // 回合 outcome 只允许记录一次（complete/error/cancel 可能多路径触发）
        Consumer<String> recordOutcome = outcome -> {
            if (outcomeRecorded.compareAndSet(false, true)) {
                metrics.recordChat("stream", outcome, elapsedMs(start));
            }
        };

        try {
            // 每个 token 片段
            Consumer<String> onToken = token -> {
                if (cancelled.get()) {
                    throw new RuntimeException("客户端已断开，取消生成");
                }
                emitted.set(true);
                try {
                    emitter.send(SseEmitter.event().data(token));
                } catch (IOException e) {
                    // 客户端（backend）已断开：置位取消，让 handleStreamFailure 走优雅终止而非报错
                    cancelled.set(true);
                    throw new RuntimeException("SSE 发送失败，客户端可能已断开", e);
                }
            };
            // 完整回复 + 最新摘要（工具调用循环结束后的最终状态）
            BiConsumer<String, String> onComplete = (fullAnswer, summary) -> {
                if (cancelled.get()) {
                    recordOutcome.accept("cancelled");
                    return; // 已取消：不再补发结束事件
                }
                try {
                    // usage 必须在 memory/[DONE] 之前发送（backend 落库 + 前端底栏）
                    emitTraceEvent(emitter, cancelled, collector.toUsageEvent(false));
                    // 摘要回传事件：JSON 序列化（换行等特殊字符被转义，保证单行 data）
                    emitter.send(SseEmitter.event()
                            .name("memory")
                            .data(Map.of("summary", summary == null ? "" : summary),
                                    MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                    recordOutcome.accept("success");
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    recordOutcome.accept("error");
                }
            };
            // 异常（TokenStream 回调路径）
            Consumer<Throwable> onError = error -> handleStreamFailure(
                    req, emitter, cancelled, emitted, fallbackUsed, collector, useFallback,
                    error, start, recordOutcome, gate);

            // 主模型失败且未输出 → 用备用模型重跑（入口由 useFallback 决定）；
            // gate/collector 显式传入：本路径可能运行在模型回调线程，ThreadLocal 不可靠
            TokenStream ts = useFallback
                    ? agentFactory.streamChatFallback(req, onToken, onComplete, onError, gate, collector)
                    : agentFactory.streamChat(req, onToken, onComplete, onError, gate, collector);
            ts.start();
        } catch (Exception e) {
            // start() 抛出的异常（请求构造/启动阶段失败，如模型不可用）
            handleStreamFailure(req, emitter, cancelled, emitted, fallbackUsed, collector,
                    useFallback, e, start, recordOutcome, gate);
        } finally {
            if (cancelled.get()) {
                log.info("[chatStream] 流式生成已终止（客户端取消）");
            }
        }
    }

    /** 统一处理流式失败：取消静默 / 轮次超限收敛 / 未输出则降级备用模型 / 否则报错 */
    private void handleStreamFailure(AgentFactory.AgentChatRequest req, SseEmitter emitter,
                                     AtomicBoolean cancelled, AtomicBoolean emitted,
                                     AtomicBoolean fallbackUsed, TraceCollector collector,
                                     boolean useFallback, Throwable error, long start,
                                     Consumer<String> recordOutcome, ApprovalGate gate) {
        if (cancelled.get()) {
            // 用户主动停止：静默结束，不补发错误事件（连接通常已断）
            recordOutcome.accept("cancelled");
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // emitter 已断开则忽略
            }
            return;
        }
        if (AgentFactory.isToolLoopExceeded(error)) {
            log.warn("[chatStream] 工具调用轮次超限，收敛回复: {}", error.getMessage());
            sendConverged(emitter, req, collector, recordOutcome);
            return;
        }
        if (!emitted.get() && !fallbackUsed.getAndSet(true)) {
            log.warn("[chatStream] 主模型流式失败（未开始输出），降级备用模型重试: {}", error.getMessage());
            runStreamWithModel(req, emitter, cancelled, collector, true, start, gate);
            return;
        }
        log.error("[chatStream] 流式对话出错: {}", error.getMessage());
        recordOutcome.accept("error");
        emitter.completeWithError(error);
    }

    /** 工具轮次超限时：发送 usage + 收敛提示 + 摘要 + [DONE]，正常结束流 */
    private void sendConverged(SseEmitter emitter, AgentFactory.AgentChatRequest req,
                               TraceCollector collector, Consumer<String> recordOutcome) {
        try {
            emitTraceEvent(emitter, new AtomicBoolean(false), collector.toUsageEvent(false));
            emitter.send(SseEmitter.event().data(TOOL_LOOP_LIMIT_MESSAGE));
            emitter.send(SseEmitter.event().name("memory")
                    .data(Map.of("summary", req.summary() == null ? "" : req.summary()),
                            MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
            recordOutcome.accept("converged");
        } catch (IOException e) {
            emitter.completeWithError(e);
            recordOutcome.accept("error");
        }
    }

    /** 发送一个命名 trace 事件（tool/rag/usage）；客户端已取消/断连则静默跳过 */
    private void emitTraceEvent(SseEmitter emitter, AtomicBoolean cancelled, TraceEvent event) {
        if (cancelled.get()) {
            return;
        }
        try {
            String name = switch (event) {
                case ToolTraceEvent ignored -> "tool";
                case com.example.agent.observability.model.RagTraceEvent ignored -> "rag";
                case UsageTraceEvent ignored -> "usage";
                case com.example.agent.observability.model.TraceResetEvent ignored -> "trace-reset";
            };
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name(name).data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 断连/序列化失败不影响主流程
            log.debug("[chatStream] trace 事件 {} 发送失败: {}",
                    event.getClass().getSimpleName(), e.getMessage());
        }
    }

    @GetMapping("/health")
    public String health() {
        return "Simple Agent is running! Powered by LangChain4j + Spring Boot (Stateless Mode).";
    }

    // ==================== Tool 目录 ====================

    /** 列出平台内置可用的 Tool 名称（供 backend/前端上传 Skill 时选择） */
    @GetMapping("/tools")
    public ResponseEntity<?> listTools() {
        return ResponseEntity.ok(java.util.Map.of("tools", toolRegistry.getAllToolNames()));
    }

    /**
     * 内置技能目录（静态内置 + MCP 动态 mcp:{server}）。
     * backend 启动时据此同步 skill 表（upsert 内置行、清理已下线 mcp:* 行）。
     */
    @GetMapping("/skills/catalog")
    public ResponseEntity<?> skillCatalog() {
        List<Map<String, Object>> skills = new java.util.ArrayList<>();
        for (Skill skill : skillManager.listBuiltin()) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("name", skill.name());
            item.put("shortDescription", skill.shortDescription());
            item.put("builtin", true);
            skills.add(item);
        }
        return ResponseEntity.ok(Map.of("skills", skills));
    }

    // ==================== 入参校验 ====================

    private ResponseEntity<?> requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("EMPTY_MESSAGE", "参数 message 不能为空或纯空白字符"));
        }
        return null;
    }

    /** 从 TraceMdcFilter 设置的请求属性取 traceId（兜底防御生成） */
    private static String traceIdOf(HttpServletRequest request) {
        Object traceId = request.getAttribute(TraceMdcFilter.TRACE_REQUEST_ATTR);
        return traceId == null ? java.util.UUID.randomUUID().toString() : traceId.toString();
    }

    // ==================== DTO ====================

    public static class ChatRequest {
        private String userId = "default";
        /** 会话 id（多会话：同一用户不同会话记忆隔离；缺省 0） */
        private Long conversationId = 0L;
        private String message;
        /** 持久化的历史摘要纯文本（无则 null） */
        private String summary;
        /** 当前消息之前的近期对话（升序），仅取 role/content */
        private List<HistoryMessage> history;
        /** 该用户已挂载的 Skill 定义（内置技能可只填 name） */
        private List<SkillDefinition> skills;
        /** 会话级已授权免确认的工具名（backend 从 Redis 授权集合下发） */
        private List<String> allowedTools;

        public ChatRequest() {}

        /** 兜底默认值，避免 NPE */
        public void normalize() {
            if (userId == null || userId.isBlank()) {
                userId = "default";
            }
            if (conversationId == null) {
                conversationId = 0L;
            }
            if (history == null) {
                history = List.of();
            }
            if (skills == null) {
                skills = List.of();
            }
            if (allowedTools == null) {
                allowedTools = List.of();
            }
        }

        public AgentFactory.AgentChatRequest toAgentRequest() {
            List<SummarizingChatMemory.HistoryMessage> hist = history.stream()
                    .filter(h -> h != null && h.getRole() != null && h.getContent() != null)
                    .map(h -> new SummarizingChatMemory.HistoryMessage(h.getRole(), h.getContent()))
                    .toList();
            return new AgentFactory.AgentChatRequest(userId, conversationId, message, summary,
                    hist, skills, allowedTools);
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Long getConversationId() { return conversationId; }
        public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public List<HistoryMessage> getHistory() { return history; }
        public void setHistory(List<HistoryMessage> history) { this.history = history; }
        public List<SkillDefinition> getSkills() { return skills; }
        public void setSkills(List<SkillDefinition> skills) { this.skills = skills; }
        public List<String> getAllowedTools() { return allowedTools; }
        public void setAllowedTools(List<String> allowedTools) { this.allowedTools = allowedTools; }
    }

    public static class HistoryMessage {
        private String role;
        private String content;
        public HistoryMessage() {}
        public HistoryMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class ChatResponse {
        private String userId;
        private String question;
        private String answer;
        /** 回合后的最新摘要（backend 持久化用；无摘要为 null） */
        private String summary;
        /** 全链路 traceId（backend 落库关联 + 日志串联） */
        private String traceId;
        /** 整轮 token 用量聚合 */
        private UsageTraceEvent usage;
        /** 本轮发生的工具调用（按发生顺序） */
        private List<ToolTraceEvent> tools;
        /** 本轮发生的 RAG 检索（含召回片段） */
        private List<com.example.agent.observability.model.RagTraceEvent> retrievals;

        public ChatResponse() {}

        public ChatResponse(String userId, String question, String answer, String summary,
                            String traceId, UsageTraceEvent usage,
                            List<ToolTraceEvent> tools,
                            List<com.example.agent.observability.model.RagTraceEvent> retrievals) {
            this.userId = userId;
            this.question = question;
            this.answer = answer;
            this.summary = summary;
            this.traceId = traceId;
            this.usage = usage;
            this.tools = tools;
            this.retrievals = retrievals;
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public String getTraceId() { return traceId; }
        public void setTraceId(String traceId) { this.traceId = traceId; }
        public UsageTraceEvent getUsage() { return usage; }
        public void setUsage(UsageTraceEvent usage) { this.usage = usage; }
        public List<ToolTraceEvent> getTools() { return tools; }
        public void setTools(List<ToolTraceEvent> tools) { this.tools = tools; }
        public List<com.example.agent.observability.model.RagTraceEvent> getRetrievals() { return retrievals; }
        public void setRetrievals(List<com.example.agent.observability.model.RagTraceEvent> retrievals) {
            this.retrievals = retrievals;
        }
    }

    public static class ErrorResponse {
        private String code;
        private String message;
        public ErrorResponse() {}
        public ErrorResponse(String code, String message) {
            this.code = code; this.message = message;
        }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
