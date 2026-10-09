package com.example.agent.controller;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 终态错误出口的错误码/可读文案提取（AgentController.emitTerminalError 的纯逻辑部分）。
 * 背景：上游（DeepSeek）报错时 LC4j 异常的 message 是整段 JSON，
 * 直接透传会让用户看到原始报文；图片类拒绝需翻译成中文可读提示。
 */
class AgentStreamErrorEventTest {

    @Test
    void code_mapsInvalidRequestAndHttpAndUnknown() {
        assertThat(AgentController.streamErrorCode(
                new InvalidRequestException("bad"))).isEqualTo("MODEL_INVALID_REQUEST");
        assertThat(AgentController.streamErrorCode(
                new HttpException(400, "bad"))).isEqualTo("MODEL_HTTP_ERROR");
        assertThat(AgentController.streamErrorCode(
                new RuntimeException("boom"))).isEqualTo("CHAT_FAILED");
    }

    @Test
    void message_extractsUpstreamJsonAndTranslatesUnsupportedImage() {
        String upstream = "{\"error\":{\"message\":\".messages[1].image[0]: You have uploaded an "
                + "unsupported image. Please make sure your image is valid and has one of the "
                + "following formats: webp, png, jpeg, and gif. (request_id: x)\","
                + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_request_error\"}}";

        String friendly = AgentController.friendlyStreamErrorMessage(new InvalidRequestException(upstream));

        assertThat(friendly).contains("模型无法处理该图片").contains("JPEG/PNG/GIF/WebP");
        assertThat(friendly).doesNotContain("request_id").doesNotContain("{\"error\"");
    }

    @Test
    void message_extractsGenericUpstreamJsonMessage() {
        String upstream = "{\"error\":{\"message\":\"rate limit hit, retry later\",\"type\":\"rate\"}}";

        String friendly = AgentController.friendlyStreamErrorMessage(new HttpException(429, upstream));

        assertThat(friendly).isEqualTo("rate limit hit, retry later");
    }

    @Test
    void message_plainTextPassesThrough() {
        assertThat(AgentController.friendlyStreamErrorMessage(new RuntimeException("连接超时")))
                .isEqualTo("连接超时");
    }

    @Test
    void message_nullOrBlankFallsBackToDefault() {
        assertThat(AgentController.friendlyStreamErrorMessage(new RuntimeException((String) null)))
                .isEqualTo("对话处理失败，请稍后重试");
        assertThat(AgentController.friendlyStreamErrorMessage(new RuntimeException("   ")))
                .isEqualTo("对话处理失败，请稍后重试");
    }

    @Test
    void message_longTextIsTruncated() {
        String longText = "x".repeat(800);
        String friendly = AgentController.friendlyStreamErrorMessage(new RuntimeException(longText));
        assertThat(friendly).hasSize(500);
    }

    @Test
    void message_malformedJsonKeepsRawText() {
        String raw = "{ broken json";
        assertThat(AgentController.friendlyStreamErrorMessage(new InvalidRequestException(raw)))
                .isEqualTo(raw);
    }
}
