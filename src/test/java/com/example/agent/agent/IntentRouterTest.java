package com.example.agent.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IntentRouter} 单测：
 *   - match：精确命中原样返回；回复夹带解释也能识别；none / 未知 → 未命中
 *   - route：启用时用判定模型选出专家；关闭 / 无候选 / 判定失败 fail-open 回退（空）
 *
 * 用假 ChatModel（回固定文本）避免真实 LLM 调用。
 */
class IntentRouterTest {

    /** 回固定文本的假模型 */
    static class StubChatModel implements ChatModel {
        private final String reply;
        StubChatModel(String reply) { this.reply = reply; }
        @Override
        public ChatResponse chat(ChatRequest chatRequest) {
            return ChatResponse.builder().aiMessage(AiMessage.from(reply)).build();
        }
    }

    private static final List<IntentRouter.Candidate> CANDIDATES = List.of(
            new IntentRouter.Candidate("researcher", "研究与资料汇总"),
            new IntentRouter.Candidate("coder", "代码编写与调试"));

    @Test
    void exactNameMatches() {
        assertThat(IntentRouter.match("researcher", CANDIDATES)).contains("researcher");
    }

    @Test
    void nameInsideExplanationMatches() {
        assertThat(IntentRouter.match("应交给 coder 处理", CANDIDATES)).contains("coder");
    }

    @Test
    void noneAndUnknownDoNotMatch() {
        assertThat(IntentRouter.match("none", CANDIDATES)).isEmpty();
        assertThat(IntentRouter.match("闲聊", CANDIDATES)).isEmpty();
        assertThat(IntentRouter.match("", CANDIDATES)).isEmpty();
        assertThat(IntentRouter.match(null, CANDIDATES)).isEmpty();
    }

    @Test
    void routeSelectsCandidateViaDecider() {
        IntentRouter router = new IntentRouter(new StubChatModel("researcher"), true);

        Optional<String> hit = router.route("帮我查一下报销政策", CANDIDATES);

        assertThat(hit).contains("researcher");
    }

    @Test
    void routeFailsOpenWhenDisabled() {
        // 关闭时不应触发任何判定调用（模型即使会命中也不该被调用）
        IntentRouter router = new IntentRouter(new StubChatModel("coder"), false);

        assertThat(router.route("写个快排", CANDIDATES)).isEmpty();
    }

    @Test
    void routeReturnsEmptyWhenNoCandidates() {
        IntentRouter router = new IntentRouter(new StubChatModel("researcher"), true);

        assertThat(router.route("随便聊聊", List.of())).isEmpty();
    }

    @Test
    void routeFailsOpenWhenDeciderThrows() {
        ChatModel throwing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest chatRequest) {
                throw new RuntimeException("模型不可用");
            }
        };
        IntentRouter router = new IntentRouter(throwing, true);

        // 判定异常不得阻断对话：返回空 → 上层回退主 Agent
        assertThat(router.route("帮我查资料", CANDIDATES)).isEmpty();
    }
}
