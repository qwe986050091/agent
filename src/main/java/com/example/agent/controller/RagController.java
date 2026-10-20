package com.example.agent.controller;

import com.example.agent.rag.DocumentTextExtractor;
import com.example.agent.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * RAG 索引与检索接口（由业务后端 backend 与 MCP 工具 kb_search 调用，不直接面向前端）
 *
 * 接口一览：
 *   POST /api/agent/rag/index-document      索引一篇纯文本文档（切块 + 向量化）
 *   POST /api/agent/rag/index-file          索引一个二进制文档（Tika 抽正文：PDF/Word/Excel/PPT/HTML）
 *   POST /api/agent/rag/index-conversation  索引一条对话消息（长期记忆）
 *   POST /api/agent/rag/remove-document     删除某文档的全部向量片段
 *   POST /api/agent/rag/search              按用户检索知识库（复用混合检索 + RRF + 重排，P0-A / S5）
 *
 * 对话内的知识注入仍由 AgentFactory 通过 ContentRetriever 自动完成，
 * /search 只是为外部调用方（MCP Server 的 kb_search 工具）提供同一套检索能力的显式入口。
 */
@RestController
@RequestMapping("/api/agent/rag")
public class RagController {

    private static final Logger log = LoggerFactory.getLogger(RagController.class);

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    /** 检索知识库：body {userId, query, topK}；userId 为空时按 default 用户检索 */
    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "query 不能为空"));
        }
        String uid = (request.getUserId() == null || request.getUserId().isBlank())
                ? "default" : request.getUserId();
        int topK = request.getTopK() == null ? RagService.DEFAULT_SEARCH_TOP_K : request.getTopK();
        try {
            return ResponseEntity.ok(new SearchResult(ragService.search(uid, request.getQuery(), topK)));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("RAG_UNAVAILABLE", e.getMessage()));
        } catch (Exception e) {
            log.error("[RAG] 独立检索失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("SEARCH_FAILED", "知识库检索失败: " + e.getMessage()));
        }
    }

    /**
     * 关键词检索探针（P1-E 对比实验）：只跑关键词这一路，返回当前实现（pg_trgm / es）、
     * 是否可用、耗时与命中，用于两套实现同口径的召回/延迟对比。
     */
    @PostMapping("/keyword-probe")
    public ResponseEntity<?> keywordProbe(@RequestBody SearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "query 不能为空"));
        }
        String uid = (request.getUserId() == null || request.getUserId().isBlank())
                ? "default" : request.getUserId();
        int topK = request.getTopK() == null ? RagService.DEFAULT_SEARCH_TOP_K : request.getTopK();
        return ResponseEntity.ok(ragService.keywordProbe(uid, request.getQuery(), topK));
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

    /**
     * 索引二进制文档（multipart）：file=文件字节，表单字段 userId/docId/name。
     * 支持 PDF / Word / Excel / PPT / HTML（Tika 自动探测）；扫描件无文本层按 422 失败。
     */
    @PostMapping(value = "/index-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> indexFile(@RequestParam("file") MultipartFile file,
                                       @RequestParam(value = "userId", required = false) String userId,
                                       @RequestParam(value = "docId", required = false) String docId,
                                       @RequestParam(value = "name", required = false) String name) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "file 不能为空"));
        }
        if (docId == null || docId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "docId 不能为空"));
        }
        String uid = userId == null ? "default" : userId;
        String sourceName = name == null || name.isBlank()
                ? (file.getOriginalFilename() == null ? "未命名文档" : file.getOriginalFilename())
                : name;
        String fileName = file.getOriginalFilename() == null ? sourceName : file.getOriginalFilename();
        try {
            int chunks = ragService.indexFile(uid, docId, sourceName, fileName, file.getBytes());
            return ResponseEntity.ok(new IndexResult(chunks));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("RAG_UNAVAILABLE", e.getMessage()));
        } catch (DocumentTextExtractor.DocumentParseException e) {
            // 不受支持/加密/损坏/空文本/正文超限/解析超时：内容问题，用户可处理后重试
            log.info("[RAG] 文件《{}》解析失败: {}", fileName, e.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ErrorResponse("UNPROCESSABLE_DOCUMENT", e.getMessage()));
        } catch (DocumentTextExtractor.ParseBusyException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("PARSE_BUSY", e.getMessage()));
        } catch (IOException e) {
            log.warn("[RAG] 上传文件读取失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse("READ_FAILED", "文件读取失败: " + e.getMessage()));
        } catch (Exception e) {
            log.error("[RAG] 文件索引失败", e);
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

    /** 删除文档向量：body {docId} */
    @PostMapping("/remove-document")
    public ResponseEntity<?> removeDocument(@RequestBody RemoveDocumentRequest request) {
        if (request == null || request.getDocId() == null || request.getDocId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("BAD_REQUEST", "docId 不能为空"));
        }
        try {
            ragService.removeDocument(request.getDocId());
            return ResponseEntity.ok(new ErrorResponse("OK", "文档向量已删除"));
        } catch (Exception e) {
            log.error("[RAG] 文档向量删除失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("REMOVE_FAILED", "文档向量删除失败: " + e.getMessage()));
        }
    }

    /** 上传文件超过 multipart 大小上限：返回 413 与可读提示（参数解析阶段抛出，控制器内可捕获） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        log.info("[RAG] 上传文件超过大小上限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件超过 20MB 大小上限"));
    }

    // ==================== DTO ====================

    public static class SearchRequest {
        private String userId;
        private String query;
        private Integer topK;
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public Integer getTopK() { return topK; }
        public void setTopK(Integer topK) { this.topK = topK; }
    }

    /** 检索响应：{items:[...]}，字段与 MCP 工具 kb_search 的解析约定一致 */
    public static class SearchResult {
        private final List<RagService.RagHit> items;
        public SearchResult(List<RagService.RagHit> items) { this.items = items; }
        public List<RagService.RagHit> getItems() { return items; }
    }

    public static class RemoveDocumentRequest {
        private String docId;
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
    }

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