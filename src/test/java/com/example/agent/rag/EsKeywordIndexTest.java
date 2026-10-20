package com.example.agent.rag;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EsKeywordIndex} 单测：用本地 HttpServer 替身验证
 *   1) 建索引幂等（HEAD 200 → 不再 PUT）；
 *   2) 查询体是 wildcard + userId 过滤；
 *   3) 响应解析出 id/片段/metadata；
 *   4) 服务不可达时 available=false（上层退化为纯向量）。
 */
class EsKeywordIndexTest {

    private HttpServer server;
    private final AtomicReference<String> lastSearchBody = new AtomicReference<>();
    private final AtomicReference<String> lastBulkBody = new AtomicReference<>();
    private volatile int putCount = 0;
    private volatile int headStatus = 404;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String resp = "{}";
        int status = 200;
        if (path.endsWith("/_search")) {
            lastSearchBody.set(body);
            resp = """
                    {"hits":{"hits":[
                      {"_id":"id-2","_source":{"embeddingId":"id-2","userId":"u1","type":"conversation",
                        "docId":"","source":"历史对话","text":"退款咨询"}},
                      {"_id":"id-1","_source":{"embeddingId":"id-1","userId":"u1","type":"document",
                        "docId":"doc-refund","source":"退款.md","text":"七天无理由退款政策"}}
                    ]}}
                    """;
        } else if (path.endsWith("/_bulk")) {
            lastBulkBody.set(body);
        } else if ("HEAD".equals(method)) {
            status = headStatus;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        } else if ("PUT".equals(method)) {
            putCount++;
        }
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void createsIndexOnlyWhenMissing() {
        EsKeywordIndex index = new EsKeywordIndex(url(), "agent-rag", 1000);
        assertThat(index.available()).isTrue();
        assertThat(putCount).isEqualTo(1);

        // 索引已存在时不再 PUT
        headStatus = 200;
        new EsKeywordIndex(url(), "agent-rag", 1000);
        assertThat(putCount).isEqualTo(1);
    }

    @Test
    void searchUsesWildcardWithUserFilterAndParsesHits() {
        EsKeywordIndex index = new EsKeywordIndex(url(), "agent-rag", 1000);

        List<KeywordIndex.KeywordHit> hits = index.search("u1", "退款 七天", 10);

        assertThat(lastSearchBody.get()).contains("\"userId\"").contains("\"u1\"")
                .contains("wildcard").contains("*退款*").contains("*七天*");
        assertThat(hits).hasSize(2);
        // 文档优先：doc-refund 排在历史对话之前（与 pg 实现同口径）
        assertThat(hits.get(0).embeddingId()).isEqualTo("id-1");
        assertThat(hits.get(0).segment().metadata().getString(RagService.META_DOC_ID))
                .isEqualTo("doc-refund");
        assertThat(hits.get(1).segment().metadata().getString(RagService.META_TYPE))
                .isEqualTo("conversation");
    }

    @Test
    void indexAllWritesBulkLinesWithSharedIds() {
        EsKeywordIndex index = new EsKeywordIndex(url(), "agent-rag", 1000);
        Metadata metadata = new Metadata()
                .put(RagService.META_USER_ID, "u1")
                .put(RagService.META_TYPE, RagService.TYPE_DOCUMENT)
                .put(RagService.META_DOC_ID, "doc-1")
                .put(RagService.META_SOURCE, "退款.md");

        index.indexAll(List.of("v-1"), List.of(TextSegment.from("七天无理由", metadata)));

        assertThat(lastBulkBody.get()).contains("\"_id\":\"v-1\"")
                .contains("\"docId\":\"doc-1\"")
                .contains("七天无理由");
    }

    @Test
    void unreachableClusterMarksUnavailable() {
        EsKeywordIndex index = new EsKeywordIndex("http://127.0.0.1:1", "agent-rag", 300);
        assertThat(index.available()).isFalse();
        assertThat(index.search("u1", "退款", 10)).isEmpty();
    }
}
