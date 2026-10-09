package com.example.agent.agent;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;

/**
 * 多模态用户消息组装（P1-8）。
 *
 * DeepSeek 视觉约束（api-docs.deepseek.com/guides/vision）：
 * - 图片仅允许出现在 user 消息（content 为块数组，标准 OpenAI 兼容 image_url）；
 * - 支持 JPEG/PNG/GIF/WebP；单图 base64 ≤ 32MiB；
 * - 当前主力模型 deepseek-flash（请求名 deepseek-v4-flash）原生支持，无需双模型路由。
 *
 * LC4j 侧：携带图片的消息不能走 AiServices 的 {@code @UserMessage("{{message}}")}
 * 字符串模板，方法参数改为直接接收 {@link dev.langchain4j.data.message.UserMessage}。
 */
public final class MultimodalUserMessages {

    private MultimodalUserMessages() {}

    /** 一张图片：MIME（image/jpeg 等）+ 原始 base64（不含 data: 前缀） */
    public record ImageInput(String mimeType, String base64Data) {}

    /**
     * 组装一条用户消息：有图 = TextContent（若有文字）+ 若干 ImageContent；
     * 无图 = 纯文本 UserMessage（与历史行为完全一致）。
     */
    public static dev.langchain4j.data.message.UserMessage from(String text,
                                                                java.util.List<ImageInput> images) {
        java.util.List<Content> contents = new java.util.ArrayList<>();
        if (text != null && !text.isBlank()) {
            contents.add(dev.langchain4j.data.message.TextContent.from(text));
        }
        if (images != null) {
            for (ImageInput img : images) {
                if (img == null || img.base64Data() == null || img.base64Data().isBlank()) {
                    continue;
                }
                // 显式 AUTO：LC4j 默认 LOW（缩到 512×512），截图/单据小字会糊；
                // DeepSeek 侧 auto 当前等价 original（入模前仍会按最长边约 1300 等比缩放）
                contents.add(ImageContent.from(Image.builder()
                        .mimeType(img.mimeType())
                        .base64Data(img.base64Data())
                        .build(),
                        dev.langchain4j.data.message.ImageContent.DetailLevel.AUTO));
            }
        }
        if (contents.isEmpty()) {
            // 理论不可达（入口要求文字/图片至少其一），兜底给空文本避免 LC4j 构造异常
            contents.add(dev.langchain4j.data.message.TextContent.from(""));
        }
        return dev.langchain4j.data.message.UserMessage.from(contents);
    }

    /** 纯文本便捷构造 */
    public static dev.langchain4j.data.message.UserMessage textOnly(String text) {
        return from(text, java.util.List.of());
    }
}
