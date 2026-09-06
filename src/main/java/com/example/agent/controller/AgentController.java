package com.example.agent.controller;

import com.example.agent.agent.AgentFactory;
import com.example.agent.agent.SummarizingChatMemory;
import com.example.agent.skill.SkillDefinition;
import com.example.agent.tools.ToolRegistry;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** SSE 流式对话的工作线程池（TokenStream.start() 为阻塞调用） */
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "agent-sse-stream");
        t.setDaemon(true);
        return t;
    });

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
        // GET 便捷入口：不带历史/技能，纯无状态对话
        AgentFactory.AgentChatRequest req =
                new AgentFactory.AgentChatRequest(userId, message, null, List.of(), List.of());
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

        // 0 表示不超时（流式生成可能较久）；实际由容器兜底
        SseEmitter emitter = new SseEmitter(0L);

        final AgentFactory.AgentChatRequest req;
        try {
            req = request.toAgentRequest();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("SKILL_INVALID", e.getMessage()));
        }

        streamExecutor.execute(() -> {
            try {
                agentFactory.streamChat(
                        req,
                        // 每个 token 片段
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().data(token));
                            } catch (IOException e) {
                                throw new RuntimeException("SSE 发送失败，客户端可能已断开", e);
                            }
                        },
                        // 完整回复 + 最新摘要（工具调用循环结束后的最终状态）
                        (fullAnswer, summary) -> {
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
                        },
                        // 异常
                        error -> {
                            log.error("[chatStream] 流式对话出错: {}", error.getMessage());
                            emitter.completeWithError(error);
                        }
                ).start();
            } catch (Exception e) {
                log.error("[chatStream] 流式对话启动失败", e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
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
            return new AgentFactory.AgentChatRequest(userId, message, summary, hist, skills);
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
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
