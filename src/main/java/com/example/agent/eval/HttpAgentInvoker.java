package com.example.agent.eval;

import com.example.agent.observability.model.RagTraceEvent;
import com.example.agent.observability.model.SafetyTraceEvent;
import com.example.agent.observability.model.ToolTraceEvent;
import com.example.agent.observability.model.UsageTraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对已部署 agent 打 HTTP 的评测调用器（{@code POST /api/agent/chat}）。
 *
 * <p>与 {@link InProcessAgentInvoker} 的差别：走真实 HTTP 边界（含服务间鉴权头 X-Agent-Token），
 * 适合「对线上环境跑评测」。轨迹同样来自 agent 返回的 tools/retrievals/safeties/usage 字段。
 */
public class HttpAgentInvoker implements AgentInvoker {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentInvoker.class);

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpAgentInvoker(String baseUrl, String token, ObjectMapper mapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String describe() {
        return "http:" + baseUrl;
    }

    @Override
    public EvalTurn invoke(EvalCase evalCase, String userId) {
        long start = System.nanoTime();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("conversationId", 0L);
            payload.put("message", evalCase.input());
            payload.put("history", List.of());
            payload.put("skills", evalCase.skills().stream().map(n -> Map.of("name", n)).toList());
            payload.put("allowedTools", evalCase.allowedTools().isEmpty()
                    ? evalCase.expectedTools() : evalCase.allowedTools());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/agent/chat"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("X-Agent-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                String body = response.body() == null ? "" : response.body();
                if (body.contains("SAFETY_BLOCKED")) {
                    return new EvalTurn(null, "", null, List.of(), List.of(), List.of(),
                            emptyUsage(elapsedMs(start)), true, null, elapsedMs(start));
                }
                return EvalTurn.failure(null,
                        "HTTP " + response.statusCode() + ": " + truncate(body), elapsedMs(start));
            }
            return parse(response.body(), elapsedMs(start));
        } catch (Exception e) {
            log.warn("[Eval] HTTP 调用失败: {}", e.getMessage());
            return EvalTurn.failure(null, e.getMessage(), elapsedMs(start));
        }
    }

    private EvalTurn parse(String body, long latencyMs) throws Exception {
        JsonNode node = mapper.readTree(body);
        return new EvalTurn(
                text(node, "traceId"),
                text(node, "answer"),
                text(node, "summary"),
                list(node, "tools", ToolTraceEvent.class),
                list(node, "retrievals", RagTraceEvent.class),
                list(node, "safeties", SafetyTraceEvent.class),
                node.hasNonNull("usage") ? mapper.convertValue(node.get("usage"), UsageTraceEvent.class)
                        : emptyUsage(latencyMs),
                false, null, latencyMs);
    }

    private <T> List<T> list(JsonNode node, String field, Class<T> type) {
        if (!node.hasNonNull(field) || !node.get(field).isArray()) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        for (JsonNode item : node.get(field)) {
            out.add(mapper.convertValue(item, type));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static UsageTraceEvent emptyUsage(long latencyMs) {
        return new UsageTraceEvent(null, 0, 0, 0, 0, false, false, latencyMs);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
