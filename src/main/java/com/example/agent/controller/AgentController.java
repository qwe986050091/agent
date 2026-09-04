package com.example.agent.controller;

import com.example.agent.agent.SimpleAgent;
import org.springframework.web.bind.annotation.*;

/**
 * Agent REST API
 *
 * Examples:
 *  GET  /api/agent/health
 *  GET  /api/agent/chat?userId=u1&message=今天几号
 *  POST /api/agent/chat   {"userId":"u1","message":"(12+8)*3/5 等于几"}
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final SimpleAgent simpleAgent;

    // 构造器注入（Spring 4.3+ 单构造器自动 @Autowired）
    public AgentController(SimpleAgent simpleAgent) {
        this.simpleAgent = simpleAgent;
    }

    @GetMapping("/chat")
    public ChatResponse chatGet(@RequestParam(defaultValue = "default") String userId,
                                @RequestParam String message) {
        String answer = simpleAgent.chat(userId, message);
        return new ChatResponse(userId, message, answer);
    }

    @PostMapping("/chat")
    public ChatResponse chatPost(@RequestBody ChatRequest request) {
        String uid = request.getUserId() == null ? "default" : request.getUserId();
        String answer = simpleAgent.chat(uid, request.getMessage());
        return new ChatResponse(uid, request.getMessage(), answer);
    }

    @GetMapping("/health")
    public String health() {
        return "Simple Agent is running! Powered by LangChain4j + Spring Boot.";
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
}
