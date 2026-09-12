package com.example.agent.tools.web;

/**
 * URL 未通过 SSRF 安全校验时抛出（内网/保留地址、非法协议、DNS 解析失败等）。
 * 消息面向用户，不得包含内网 IP 等敏感细节。
 */
public class UnsafeUrlException extends RuntimeException {

    public UnsafeUrlException(String message) {
        super(message);
    }
}
