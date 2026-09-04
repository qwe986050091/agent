package com.example.agent.controller;

import com.example.agent.agent.SimpleAgent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Agent REST API
 *
 * 调用前必须传递非空 message，否则会返回 400 BadRequest。
 * （LangChain4j 的 prepareUserMessage 在空消息时会抛 IllegalConfigurationException：
 *   "does not have a user message defined" —— 本质就是"用户消息为空"）
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final SimpleAgent simpleAgent;

    public AgentController(SimpleAgent simpleAgent) {
        this.simpleAgent = simpleAgent;
    }

    @GetMapping("/chat")
    public ResponseEntity<?> chatGet(@RequestParam(defaultValue = "default") String userId,
                                     @RequestParam String message) {
        ResponseEntity<?> bad = requireMessage(message);
        if (bad != null) return bad;
        String answer = simpleAgent.chat(userId, message);
        return ResponseEntity.ok(new ChatResponse(userId, message, answer));
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chatPost(@RequestBody ChatRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("BAD_REQUEST", "请求体为空"));
        }
        String uid = request.getUserId() == null ? "default" : request.getUserId();
        String msg = request.getMessage();
        ResponseEntity<?> bad = requireMessage(msg);
        if (bad != null) return bad;
        String answer = simpleAgent.chat(uid, msg);
        return ResponseEntity.ok(new ChatResponse(uid, msg, answer));
    }

    @GetMapping("/health")
    public String health() {
        return "Simple Agent is running! Powered by LangChain4j + Spring Boot.";
    }

    // ===== 入参校验：message 不能为 null / 空串 / 全空白 =====
    private ResponseEntity<?> requireMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("EMPTY_MESSAGE", "参数 message 不能为空或纯空白字符"));
        }
        return null;
    }

    // ===== 手写 DTO，避免依赖 Lombok 注解处理器 =====

    public static class ChatRequest {
        private String userId = "default";
        private String message;

        public ChatRequest() {}

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class ChatResponse {
        private String userId;
        private String question;
        private String answer;

        public ChatResponse() {}

        public ChatResponse(String userId, String question, String answer) {
            this.userId = userId;
            this.question = question;
            this.answer = answer;
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getAnswer() { return answer; }
        public void setAnswer(String answer) { this.answer = answer; }
    }

    /** 通用错误响应体 */
    public static class ErrorResponse {
        private String code;
        private String message;

        public ErrorResponse() {}

        public ErrorResponse(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }
}
