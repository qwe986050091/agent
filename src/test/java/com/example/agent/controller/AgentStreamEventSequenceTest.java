package com.example.agent.controller;

import com.example.agent.agent.AgentFactory;
import com.example.agent.approval.ApprovalRegistry;
import com.example.agent.observability.AgentMetricsSink;
import com.example.agent.observability.TraceCollector;
import com.example.agent.observability.TraceContextHolder;
import com.example.agent.observability.model.RagTraceEvent;
import com.example.agent.observability.model.SafetyTraceEvent;
import com.example.agent.observability.model.ToolTraceEvent;
import com.example.agent.observability.model.TraceEvent;
import com.example.agent.observability.model.TraceResetEvent;
import com.example.agent.observability.model.UsageTraceEvent;
import com.example.agent.security.InjectionRuleScanner;
import com.example.agent.security.LlmInjectionClassifier;
import com.example.agent.security.SafetyProperties;
import com.example.agent.security.SensitiveValueRegistry;
import com.example.agent.skill.SkillManager;
import com.example.agent.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * agent 侧 SSE 事件序列单测（P2-12）。
 *
 * 固化的是「协议契约」而不是实现细节：
 *   1. 正常收尾必须是 usage → memory → [DONE] 的严格顺序（backend 依赖 usage 落库、
 *      前端依赖 [DONE] 收尾），且最后 complete()
 *   2. 已取消的回合不补发任何结束事件
 *   3. 终态错误只下发一条命名事件 {@code event: error}（不是粗暴截断连接），发送失败才退回 completeWithError
 *   4. trace 事件名映射（tool/rag/usage/trace-reset/safety）逐类正确
 *
 * 做法：手写 RecordingSseEmitter 覆写 send 系列方法收集片段（比 Mockito mock
 * 带重载的 send 更明确），控制器用 Mockito 假件直接构造，不启 Spring 上下文。
 */
class AgentStreamEventSequenceTest {

    private AgentController controller;
    private TraceCollector collector;

    @BeforeEach
    void setUp() {
        controller = new AgentController(
                mock(AgentFactory.class),
                mock(ToolRegistry.class),
                mock(SkillManager.class),
                mock(AgentMetricsSink.class),
                mock(ApprovalRegistry.class),
                mock(InjectionRuleScanner.class),
                mock(LlmInjectionClassifier.class),
                mock(SensitiveValueRegistry.class),
                // 内容安全默认关闭：scanOutput 在此件上短路，不影响收尾事件序列
                mock(SafetyProperties.class));
        collector = TraceContextHolder.start("trace-p2-12", "u1", 0L, event -> {
        }, null);
    }

    @AfterEach
    void tearDown() {
        TraceContextHolder.clear();
    }

    // ==================== 正常收尾 ====================

    @Test
    void turnCompletionSendsUsageThenMemoryThenDone() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        List<String> outcomes = new ArrayList<>();

        controller.sendTurnCompletion(emitter, new AtomicBoolean(false), collector,
                "完整回答文本", "本轮摘要", outcomes::add);

        String flat = emitter.flat();
        int usage = flat.indexOf("event:usage");
        int memory = flat.indexOf("event:memory");
        int done = flat.indexOf("[DONE]");
        assertThat(usage).as("usage 事件应存在").isNotNegative();
        assertThat(memory).as("memory 必须晚于 usage").isGreaterThan(usage);
        assertThat(done).as("[DONE] 必须晚于 memory").isGreaterThan(memory);

        assertThat(flat).contains("summary").contains("本轮摘要");
        assertThat(emitter.completed).isTrue();
        assertThat(emitter.failed).isNull();
        assertThat(outcomes).containsExactly("success");
    }

    @Test
    void cancelledTurnSendsNoEvents() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        List<String> outcomes = new ArrayList<>();

        controller.sendTurnCompletion(emitter, new AtomicBoolean(true), collector,
                "完整回答文本", "本轮摘要", outcomes::add);

        assertThat(emitter.chunks).isEmpty();
        assertThat(emitter.completed).isFalse();
        assertThat(emitter.failed).isNull();
        assertThat(outcomes).containsExactly("cancelled");
    }

    @Test
    void sendFailureDuringCompletionFallsBackToCompleteWithError() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        emitter.failSendWith = new IOException("客户端已断开");
        List<String> outcomes = new ArrayList<>();

        controller.sendTurnCompletion(emitter, new AtomicBoolean(false), collector,
                "完整回答文本", "本轮摘要", outcomes::add);

        assertThat(emitter.failed).isInstanceOf(IOException.class);
        assertThat(emitter.completed).isFalse();
        assertThat(outcomes).containsExactly("error");
    }

    // ==================== 工具轮次超限收敛 ====================

    @Test
    void convergedFlowSendsUsageLimitMessageMemoryAndDone() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        List<String> outcomes = new ArrayList<>();
        AgentFactory.AgentChatRequest req = new AgentFactory.AgentChatRequest(
                "u1", 0L, "问题", "旧摘要", List.of(), List.of(), List.of());

        controller.sendConverged(emitter, req, collector, outcomes::add);

        String flat = emitter.flat();
        assertThat(flat).contains("event:usage");
        assertThat(flat).contains("已达到最大工具调用轮次");
        assertThat(flat).contains("event:memory").contains("旧摘要");
        assertThat(flat).contains("[DONE]");
        assertThat(flat.indexOf("event:usage")).isLessThan(flat.indexOf("[DONE]"));
        assertThat(emitter.completed).isTrue();
        assertThat(outcomes).containsExactly("converged");
    }

    @Test
    void convergedFlowFallsBackToCompleteWithErrorOnSendFailure() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        emitter.failSendWith = new IOException("broken pipe");
        List<String> outcomes = new ArrayList<>();
        AgentFactory.AgentChatRequest req = new AgentFactory.AgentChatRequest(
                "u1", 0L, "问题", null, List.of(), List.of(), List.of());

        controller.sendConverged(emitter, req, collector, outcomes::add);

        assertThat(emitter.failed).isInstanceOf(IOException.class);
        assertThat(outcomes).containsExactly("error");
    }

    // ==================== 终态错误 ====================

    @Test
    void terminalErrorEmitsNamedErrorEventThenCompletes() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        controller.emitTerminalError(emitter, new RuntimeException("模型连接超时"));

        String flat = emitter.flat();
        assertThat(flat).contains("event:error");
        assertThat(flat).contains("CHAT_FAILED").contains("模型连接超时");
        assertThat(emitter.completed).isTrue();
        assertThat(emitter.failed).isNull();
    }

    @Test
    void terminalErrorFallsBackToCompleteWithErrorWhenSendFails() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        emitter.failSendWith = new IOException("连接已断");

        controller.emitTerminalError(emitter, new RuntimeException("boom"));

        assertThat(emitter.failed).isInstanceOf(RuntimeException.class);
        assertThat(emitter.failed).hasMessageContaining("boom");
        assertThat(emitter.completed).isFalse();
    }

    // ==================== trace 事件名映射 ====================

    @Test
    void traceEventNamesAreMappedPerType() {
        assertThat(eventNameOf(new ToolTraceEvent(1, "call-1", "getCurrentTime", "{}", "12:00", true, null, 8, null)))
                .isEqualTo("tool");
        assertThat(eventNameOf(new RagTraceEvent("报销流程", List.of()))).isEqualTo("rag");
        assertThat(eventNameOf(new UsageTraceEvent("deepseek-v4-flash", 10, 20, 30, 1, false, false, 120)))
                .isEqualTo("usage");
        assertThat(eventNameOf(new TraceResetEvent())).isEqualTo("trace-reset");
        assertThat(eventNameOf(new SafetyTraceEvent("input", 4, "none", "rule", "none", null, List.of(), "片段", 3)))
                .isEqualTo("safety");
    }

    @Test
    void traceEventIsSkippedWhenCancelled() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        controller.emitTraceEvent(emitter, new AtomicBoolean(true),
                new UsageTraceEvent("m", 0, 0, 0, 0, false, true, 5));

        assertThat(emitter.chunks).isEmpty();
    }

    /** 发一个 trace 事件并返回其被记录的事件名 */
    private String eventNameOf(TraceEvent event) {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        controller.emitTraceEvent(emitter, new AtomicBoolean(false), event);
        String flat = emitter.flat();
        int at = flat.indexOf("event:");
        assertThat(at).as("应记录到命名事件: %s", event.getClass().getSimpleName()).isNotNegative();
        int end = flat.indexOf('\n', at);
        return flat.substring(at + "event:".length(), end < 0 ? flat.length() : end).trim();
    }

    // ==================== 测试夹具 ====================

    /**
     * 记录型 SseEmitter：覆写 send 系列方法，按序收集所有片段。
     *
     * 说明：Spring 的 {@code send(SseEventBuilder)} 内部走 super.send(...)，
     * 因此必须单独覆写它，否则事件名片段（形如 "event:memory\n"）不会被记录。
     */
    static final class RecordingSseEmitter extends SseEmitter {
        final List<Object> chunks = new ArrayList<>();
        final List<MediaType> mediaTypes = new ArrayList<>();
        boolean completed;
        Throwable failed;
        /** 非 null 时，任何 send 都抛出它（模拟客户端断开） */
        IOException failSendWith;

        @Override
        public void send(Object object) throws IOException {
            record(object, null);
        }

        @Override
        public void send(Object object, MediaType mediaType) throws IOException {
            record(object, mediaType);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            for (DataWithMediaType entry : builder.build()) {
                record(entry.getData(), entry.getMediaType());
            }
        }

        private void record(Object data, MediaType mediaType) throws IOException {
            if (failSendWith != null) {
                throw failSendWith;
            }
            chunks.add(data);
            mediaTypes.add(mediaType);
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            failed = ex;
        }

        /** 所有片段按序拼接，便于做顺序断言 */
        String flat() {
            StringBuilder sb = new StringBuilder();
            for (Object c : chunks) {
                sb.append(c);
            }
            return sb.toString();
        }
    }
}