package com.example.agent.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Elasticsearch 关键词索引实现（P1-E）。
 *
 * 与 {@link KeywordSearcher}（pg_trgm + ILIKE）保持**同一套切词与返回口径**，
 * 便于做「pg_trgm ↔ ES」的召回率与延迟对比实验：
 *   - 建索引时用同一个 embeddingId（与向量库共享 id，RRF 可精确去重）；
 *   - 查询时把切出的词转成 wildcard（{@code *term*}），等价于 ILIKE '%term%'；
 *   - 结果排序在客户端按「文档优先、片段更短优先」重排，与 pg 实现保持一致。
 *
 * 无第三方客户端依赖：直接走 JDK HttpClient + Jackson，避免为一个对比实现引整个 ES 客户端。
 * 任何网络/解析异常都返回空列表，让上层退化为纯向量召回。
 */
public class EsKeywordIndex implements KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(EsKeywordIndex.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String url;
    private final String index;
    private final HttpClient client;
    private final Duration timeout;
    private volatile boolean available;

    public EsKeywordIndex(String url, String index, int timeoutMs) {
        this.url = trimTrailingSlash(url);
        this.index = index;
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.available = ensureIndex();
    }

    @Override
    public String name() {
        return "es";
    }

    @Override
    public boolean available() {
        return available;
    }

    /**
     * 建索引（幂等）：text 用 wildcard 类型支持 *substring* 查询，无需分词插件；
     * userId/type/docId 用 keyword 做精确过滤与聚合。
     */
    private boolean ensureIndex() {
        try {
            String mapping = """
                    {"settings":{"number_of_shards":1,"number_of_replicas":0},
                     "mappings":{"properties":{
                       "embeddingId":{"type":"keyword"},
                       "userId":{"type":"keyword"},
                       "type":{"type":"keyword"},
                       "docId":{"type":"keyword"},
                       "source":{"type":"keyword"},
                       "text":{"type":"wildcard"}
                     }}}
                    """;
            HttpResponse<String> head = send("HEAD", "/" + index, null);
            if (head.statusCode() == 200) {
                log.info("[RAG] 关键词实现=es，索引 {} 已存在（{}）", index, url);
                return true;
            }
            HttpResponse<String> created = send("PUT", "/" + index, mapping);
            boolean ok = created.statusCode() == 200;
            if (ok) {
                log.info("[RAG] 关键词实现=es，已创建索引 {}（{}）", index, url);
            } else {
                log.warn("[RAG] ES 建索引失败（{}）：{}，关键词路将退化为纯向量",
                        created.statusCode(), created.body());
            }
            return ok;
        } catch (Exception e) {
            log.warn("[RAG] ES 不可达（{}）：{}，关键词路将退化为纯向量", url, e.getMessage());
            return false;
        }
    }

    /** 批量写入（bulk API）；失败只记日志，不影响向量主链路 */
    @Override
    public void indexAll(List<String> ids, List<TextSegment> segments) {
        if (!available || ids == null || segments == null || ids.isEmpty()) {
            return;
        }
        try {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < ids.size() && i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                Metadata md = segment.metadata();
                ObjectNode doc = MAPPER.createObjectNode();
                doc.put("embeddingId", ids.get(i));
                doc.put("userId", md == null ? "" : str(md, RagService.META_USER_ID));
                doc.put("type", md == null ? "" : str(md, RagService.META_TYPE));
                doc.put("docId", md == null ? "" : str(md, RagService.META_DOC_ID));
                doc.put("source", md == null ? "" : str(md, RagService.META_SOURCE));
                doc.put("text", segment.text() == null ? "" : segment.text());
                body.append("{\"index\":{\"_index\":\"").append(index)
                        .append("\",\"_id\":\"").append(ids.get(i)).append("\"}}\n")
                        .append(MAPPER.writeValueAsString(doc)).append('\n');
            }
            HttpResponse<String> resp = send("POST", "/_bulk", body.toString());
            if (resp.statusCode() >= 300) {
                log.warn("[RAG] ES bulk 写入失败（{}）: {}", resp.statusCode(), abbreviate(resp.body()));
            }
        } catch (Exception e) {
            log.warn("[RAG] ES 写入异常（不影响向量索引）: {}", e.getMessage());
        }
    }

    @Override
    public void removeByDocId(String docId) {
        if (!available || docId == null || docId.isBlank()) {
            return;
        }
        try {
            ObjectNode query = MAPPER.createObjectNode();
            query.set("query", termQuery("docId", docId));
            send("POST", "/" + index + "/_delete_by_query?conflicts=proceed",
                    MAPPER.writeValueAsString(query));
        } catch (Exception e) {
            log.warn("[RAG] ES 删除文档片段失败（不影响向量删除）: {}", e.getMessage());
        }
    }

    @Override
    public List<KeywordHit> search(String userId, String query, int maxResults) {
        List<String> terms = KeywordTerms.build(query);
        if (!available || terms.isEmpty()) {
            return List.of();
        }
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("size", Math.max(1, maxResults));
            ObjectNode bool = MAPPER.createObjectNode();
            ArrayNode filter = bool.putArray("filter");
            filter.add(termQuery("userId", userId));
            ArrayNode should = bool.putArray("should");
            for (String term : terms) {
                ObjectNode wildcard = MAPPER.createObjectNode();
                ObjectNode inner = wildcard.putObject("wildcard");
                ObjectNode text = inner.putObject("text");
                text.put("value", "*" + escapeWildcard(term) + "*");
                should.add(wildcard);
            }
            bool.put("minimum_should_match", 1);
            ObjectNode wrapper = root.putObject("query");
            wrapper.set("bool", bool);

            HttpResponse<String> resp = send("POST", "/" + index + "/_search",
                    MAPPER.writeValueAsString(root));
            if (resp.statusCode() >= 300) {
                log.warn("[RAG] ES 检索失败（{}）: {}", resp.statusCode(), abbreviate(resp.body()));
                return List.of();
            }
            JsonNode hits = MAPPER.readTree(resp.body()).path("hits").path("hits");
            List<KeywordHit> result = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode src = hit.path("_source");
                Metadata metadata = new Metadata()
                        .put(RagService.META_USER_ID, src.path("userId").asText())
                        .put(RagService.META_TYPE, src.path("type").asText())
                        .put(RagService.META_DOC_ID, src.path("docId").asText())
                        .put(RagService.META_SOURCE, src.path("source").asText());
                result.add(new KeywordHit(hit.path("_id").asText(),
                        TextSegment.from(src.path("text").asText(), metadata)));
            }
            // 与 pg 实现同口径：文档优先、同类型内短片段优先
            result.sort((a, b) -> {
                boolean ad = RagService.TYPE_DOCUMENT.equals(a.segment().metadata()
                        .getString(RagService.META_TYPE));
                boolean bd = RagService.TYPE_DOCUMENT.equals(b.segment().metadata()
                        .getString(RagService.META_TYPE));
                if (ad != bd) {
                    return ad ? -1 : 1;
                }
                return Integer.compare(a.segment().text().length(), b.segment().text().length());
            });
            return result;
        } catch (Exception e) {
            log.warn("[RAG] ES 检索异常，本轮跳过关键词路: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== HTTP / JSON 工具 ====================

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url + path))
                .timeout(timeout)
                .header("Content-Type", "application/json");
        if ("HEAD".equals(method)) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else if ("GET".equals(method)) {
            builder.GET();
        } else if ("PUT".equals(method)) {
            builder.PUT(body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static ObjectNode termQuery(String field, String value) {
        ObjectNode query = MAPPER.createObjectNode();
        ObjectNode term = query.putObject("term");
        term.put(field, value == null ? "" : value);
        return query;
    }

    /** wildcard 转义：* ? 需转义为普通字符（与 ILIKE 的 % _ 语义对齐） */
    private static String escapeWildcard(String term) {
        return term.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private static String str(Metadata metadata, String key) {
        Object value = metadata.toMap().get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String trimTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 500 ? body : body.substring(0, 500) + "...";
    }
}
