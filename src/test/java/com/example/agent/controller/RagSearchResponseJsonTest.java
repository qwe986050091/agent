package com.example.agent.controller;

import com.example.agent.rag.RagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * /api/agent/rag/search 的线上报文契约测试。
 *
 * 该端点由 Go 侧 MCP 工具 kb_search 直接消费，字段名与层级一旦漂移就会静默降级，
 * 因此这里把「请求体能否被解析」与「响应体长什么样」写死成断言：
 *   - 请求：{"userId":"...","query":"...","topK":N}
 *   - 响应：{"items":[{"text","source","docId","chunkIndex","score","channel","rerankScore","type"}]}
 */
class RagSearchResponseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesRequestBodySentByGoClient() throws Exception {
        RagController.SearchRequest request = mapper.readValue(
                "{\"userId\":\"user-42\",\"query\":\"部署流程\",\"topK\":3}",
                RagController.SearchRequest.class);

        assertEquals("user-42", request.getUserId());
        assertEquals("部署流程", request.getQuery());
        assertEquals(3, request.getTopK());
    }

    @Test
    void allowsOmittingUserIdAndTopK() throws Exception {
        RagController.SearchRequest request = mapper.readValue(
                "{\"query\":\"x\"}", RagController.SearchRequest.class);

        assertNull(request.getUserId());
        assertNull(request.getTopK());
        assertEquals("x", request.getQuery());
    }

    @Test
    void serializesSearchResultIntoKbSearchContract() throws Exception {
        RagService.RagHit hit = new RagService.RagHit(
                "正文片段", "运维手册.pdf", "doc-7", 3, 0.83d, "both", 0.91d, RagService.TYPE_DOCUMENT);

        String json = mapper.writeValueAsString(new RagController.SearchResult(List.of(hit)));

        assertEquals("{\"items\":[{\"text\":\"正文片段\",\"source\":\"运维手册.pdf\",\"docId\":\"doc-7\","
                + "\"chunkIndex\":3,\"score\":0.83,\"channel\":\"both\",\"rerankScore\":0.91,"
                + "\"type\":\"document\"}]}", json);
    }

    @Test
    void serializesEmptyItemsAsArrayNotNull() throws Exception {
        String json = mapper.writeValueAsString(new RagController.SearchResult(List.of()));
        assertEquals("{\"items\":[]}", json);
    }
}
