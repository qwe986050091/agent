package com.example.agent.controller;

import com.example.agent.agent.AgentFactory;
import com.example.agent.agent.SummarizingChatMemory;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.tools.ToolRegistry;
import dev.langchain4j.service.TokenStream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
 *                                     结束前发 event: memory（含最新摘要），再发 data: [DONE]
 *   GET  /api/agent/tools             列出平台内置可用 Tool 名称
 *
 * 说明：技能的上传/挂载/注销等状态管理全部由 backend（MySQL）负责，
 * agent 不再保存技能与挂载状态，故无 /skills/upload、/skills/mount 等接口。
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

    public AgentController(AgentFactory agentFactory, ToolRegistry toolRegistry) {
        this.agentFactory = agentFactory;
        this.toolRegistry = toolRegistry;
    }

    // ==================== 对话接口 ====================

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(@RequestParam(defaultValue = "default") String userId,
                                     @RequestParam String message) {
        ResponseEntity<?> bad = requireMessage(message);
        if (bad != null) return bad;
        // GET 便捷入口：不带历史/技能，纯无状态对话（默认会话 0）
        AgentFactory.AgentChatRequest req =
                new AgentFactory.AgentChatRequest(userId, 0L, message, null, List.of(), List.of());
        return runChat(req);
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chatPost(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "请求体为空"));
        }
        request.normalize();
        ResponseEntity<?> bad = requireMessage(request.getMessage());
        if (bad != null) return bad;
        return runChat(request.toAgentRequest());
    }

    private ResponseEntity<?> runChat(AgentFactory.AgentChatRequest req) {
        try {
            AgentFactory.AgentChatResult result = agentFactory.chat(req);
            return ResponseEntity.ok(new ChatResponse(req.userId(), req.message(), result.answer(), result.summary()));
        } catch (IllegalArgumentException e) {
            // Skill 定义引用了不存在的 Tool 等数据不一致问题
            return ResponseEntity.badRequest().body(new ErrorResponse("SKILL_INVALID", e.getMessage()));
        } catch (Exception e) {
            if (AgentFactory.isToolLoopExceeded(e)) {
                // 工具调用轮次超限：收敛为友好提示（200），而非 500 报错
                log.warn("[chat] 工具调用轮次超限，收敛回复: {}", e.getMessage());
                return ResponseEntity.ok(new ChatResponse(
                        req.userId(), req.message(), TOOL_LOOP_LIMIT_MESSAGE, req.summary()));
            }
            log.error("[chat] 对话处理失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("CHAT_FAILED", "对话处理失败: " + e.getMessage()));
        }
    }

    /**
     * 流式对话（SSE）：逐 token 推送
     *
     * 事件格式（标准 text/event-stream）：
     *   data: 你好                 ← 每个 token 一个事件（默认 event）
     *   event: memory              ← 回合结束，回传最新记忆摘要（backend 落库用，不转发前端）
     *   data: {"summary":"..."}
     *   data: [DONE]               ← 结束标记
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object chatStream(@RequestBody(required = false) ChatRequest request) {
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

        emitter.onTimeout(() -> {
            log.warn("[chatStream] SSE 超过 {}ms 未完成，主动断开并取消生成", SSE_TIMEOUT_MS);
            cancelled.set(true);
            Thread t = streamThread.get();
            if (t != null) t.interrupt();
            emitter.completeWithError(new TimeoutException("响应超时，请重试"));
        });
        emitter.onError(e -> {
            // 客户端断开 / 发送失败：取消正在进行的 LLM 生成（中断静默期阻塞的读取）
            log.warn("[chatStream] SSE 连接异常（{}），取消生成",
                    e == null ? "客户端断开" : e.getMessage());
            cancelled.set(true);
            Thread t = streamThread.get();
            if (t != null) t.interrupt();
        });
        emitter.onCompletion(() -> {
            // 正常结束（complete 已由流线程调用）；兜底置位，避免任何残留回调继续发送
            cancelled.set(true);
        });

        final AgentFactory.AgentChatRequest req;
        try {
            req = request.toAgentRequest();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("SKILL_INVALID", e.getMessage()));
        }

        try {
            streamExecutor.execute(() -> runStream(req, emitter, cancelled, streamThread));
        } catch (RejectedExecutionException e) {
            // 线程池/队列已满：快速失败，不排队堆积
            log.warn("[chatStream] 流式线程池已满，拒绝请求");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SERVICE_BUSY", "当前对话繁忙，请稍后重试"));
        }

        return emitter;
    }

    // ==================== 流式执行（护栏：取消传播 / fallback / 轮次收敛） ====================

    /** 工具调用轮次超限时返回给用户的收敛提示 */
    private static final String TOOL_LOOP_LIMIT_MESSAGE =
            "已达到最大工具调用轮次，已自动中止以避免死循环。请尝试更简单的指令，或减少连续工具调用。";

    /**
     * 在流式线程池中执行一次流式对话（主模型）。
     */
    private void runStream(AgentFactory.AgentChatRequest req, SseEmitter emitter,
                           AtomicBoolean cancelled, AtomicReference<Thread> streamThread) {
        streamThread.set(Thread.currentThread());
        runStreamWithModel(req, emitter, cancelled, false);
    }

    /**
     * 用指定（主/备用）流式模型执行对话，带三类护栏：
     * 1. 取消传播：客户端断开（cancelled 置位）后，token 回调抛异常终止生成；
     * 2. 备用模型降级：主模型在「未输出任何 token」前失败 → 用备用模型重跑一次（避免重复输出）；
     * 3. 工具轮次超限：收敛为友好提示而非报错。
     */
    private void runStreamWithModel(AgentFactory.AgentChatRequest req, SseEmitter emitter,
                                    AtomicBoolean cancelled, boolean useFallback) {
        AtomicBoolean emitted = new AtomicBoolean(false);
        AtomicBoolean fallbackUsed = new AtomicBoolean(useFallback);
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
                    return; // 已取消：不再补发结束事件
                }
                try {
                    // 摘要回传事件：JSON 序列化（换行等特殊字符被转义，保证单行 data）
                    emitter.send(SseEmitter.event()
                            .name("memory")
                            .data(Map.of("summary", summary == null ? "" : summary),
                                    MediaType.APPLICATION_JSON));
                    emitter.send(SseEmitter.event().data("[DONE]"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            };
            // 异常（TokenStream 回调路径）
            Consumer<Throwable> onError =
                    error -> handleStreamFailure(req, emitter, cancelled, emitted, fallbackUsed, useFallback, error);

            // 主模型失败且未输出 → 用备用模型重跑（入口由 useFallback 决定）
            TokenStream ts = useFallback
                    ? agentFactory.streamChatFallback(req, onToken, onComplete, onError)
                    : agentFactory.streamChat(req, onToken, onComplete, onError);
            ts.start();
        } catch (Exception e) {
            // start() 抛出的异常（请求构造/启动阶段失败，如模型不可用）
            handleStreamFailure(req, emitter, cancelled, emitted, fallbackUsed, useFallback, e);
        } finally {
            if (cancelled.get()) {
                log.info("[chatStream] 流式生成已终止（客户端取消）");
            }
        }
    }

    /** 统一处理流式失败：取消静默 / 轮次超限收敛 / 未输出则降级备用模型 / 否则报错 */
    private void handleStreamFailure(AgentFactory.AgentChatRequest req, SseEmitter emitter,
                                     AtomicBoolean cancelled, AtomicBoolean emitted,
                                     AtomicBoolean fallbackUsed, boolean useFallback, Throwable error) {
        if (cancelled.get()) {
            // 用户主动停止：静默结束，不补发错误事件
            try {
                emitter.complete();
            } catch (Exception ignore) {
                // emitter 已断开则忽略
            }
            return;
        }
        if (AgentFactory.isToolLoopExceeded(error)) {
            log.warn("[chatStream] 工具调用轮次超限，收敛回复: {}", error.getMessage());
            sendConverged(emitter, req);
            return;
        }
        if (!emitted.get() && !fallbackUsed.getAndSet(true)) {
            log.warn("[chatStream] 主模型流式失败（未开始输出），降级备用模型重试: {}", error.getMessage());
            runStreamWithModel(req, emitter, cancelled, true);
            return;
        }
        log.error("[chatStream] 流式对话出错: {}", error.getMessage());
        emitter.completeWithError(error);
    }

    /** 工具轮次超限时：发送收敛提示 + 原摘要 + [DONE]，正常结束流 */
    private void sendConverged(SseEmitter emitter, AgentFactory.AgentChatRequest req) {
        try {
            emitter.send(SseEmitter.event().data(TOOL_LOOP_LIMIT_MESSAGE));
            emitter.send(SseEmitter.event().name("memory")
                    .data(Map.of("summary", req.summary() == null ? "" : req.summary()),
                            MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
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

    // ==================== 入参校验 ====================

    private ResponseEntity<?> requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("EMPTY_MESSAGE", "参数 message 不能为空或纯空白字符"));
        }
        return null;
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
        }

        public AgentFactory.AgentChatRequest toAgentRequest() {
            List<SummarizingChatMemory.HistoryMessage> hist = history.stream()
                    .filter(h -> h != null && h.getRole() != null && h.getContent() != null)
                    .map(h -> new SummarizingChatMemory.HistoryMessage(h.getRole(), h.getContent()))
                    .toList();
            return new AgentFactory.AgentChatRequest(userId, conversationId, message, summary, hist, skills);
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

        public ChatResponse() {}
        public ChatResponse(String userId, String question, String answer, String summary) {
            this.userId = userId;
            this.question = question;
            this.answer = answer;
            this.summary = summary;
        }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
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
