package com.example.agent.controller;

import com.example.agent.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * RAG 索引接口（由业务后端 backend 调用，不直接面向前端）
 *
 * 接口一览：
 *   POST /api/agent/rag/index-document      索引一篇文档（切块 + 向量化）
 *   POST /api/agent/rag/index-conversation  索引一条对话消息（长期记忆）
 *
 * 检索不提供独立接口：检索在对话时由 AgentFactory 通过 ContentRetriever 自动完成。
 */
@RestController
@RequestMapping("/api/agent/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 索引文档：body {userId, docId, name, text} */
    @PostMapping("/index-document")
    public ResponseEntity<?> indexDocument(@RequestBody IndexDocumentRequest request) {
        if (request == null || request.getText() == null || request.getText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "text 不能为空"));
        }
        String uid = request.getUserId() == null ? "default" : request.getUserId();
        try {
            int chunks = ragService.indexDocument(uid, request.getDocId(), request.getName(), request.getText());
            return ResponseEntity.ok(new IndexResult(chunks));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("RAG_UNAVAILABLE", e.getMessage()));
        } catch (Exception e) {
            log.error("[RAG] 文档索引失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("INDEX_FAILED", "文档索引失败: " + e.getMessage()));
        }
    }

    /** 索引对话消息：body {userId, role, content} */
    @PostMapping("/index-conversation")
    public ResponseEntity<?> indexConversation(@RequestBody IndexConversationRequest request) {
        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "content 不能为空"));
        }
        String uid = request.getUserId() == null ? "default" : request.getUserId();
        try {
            ragService.indexConversation(uid, request.getRole(), request.getContent());
            return ResponseEntity.ok(new ErrorResponse("OK", "对话已索引"));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("RAG_UNAVAILABLE", e.getMessage()));
        } catch (Exception e) {
            log.error("[RAG] 对话索引失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("INDEX_FAILED", "对话索引失败: " + e.getMessage()));
        }
    }

    // ==================== DTO ====================

    public static class IndexDocumentRequest {
        private String userId;
        private String docId;
        private String name;
        private String text;
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    public static class IndexConversationRequest {
        private String userId;
        private String role;
        private String content;
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
    }

    public static class IndexResult {
        private int chunks;
        public IndexResult() {}
        public IndexResult(int chunks) { this.chunks = chunks; }
        public int getChunks() { return chunks; }
        public void setChunks(int chunks) { this.chunks = chunks; }
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