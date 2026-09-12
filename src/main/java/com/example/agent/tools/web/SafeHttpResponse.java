package com.example.agent.tools.web;

/**
 * 安全 HTTP 调用结果。
 *
 * @param status       最终响应状态码（跟随重定向后的最后一跳）
 * @param contentType  Content-Type 头（可能为 null）
 * @param finalUrl     最终落点 URL（重定向后）
 * @param body         响应体文本（按上限截断读取）
 * @param truncated    响应体是否因超过下载上限被截断
 */
public record SafeHttpResponse(int status,
                               String contentType,
                               String finalUrl,
                               String body,
                               boolean truncated) {
}
