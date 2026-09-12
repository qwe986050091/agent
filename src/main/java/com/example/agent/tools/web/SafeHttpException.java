package com.example.agent.tools.web;

/**
 * 安全 HTTP 调用失败（超时/连接失败/非 2xx 由调用方决定如何处理）。
 * 消息面向用户与 LLM，不携带堆栈细节。
 */
public class SafeHttpException extends RuntimeException {

    public SafeHttpException(String message) {
        super(message);
    }
}
