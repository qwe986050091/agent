package com.example.agent.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * agent 内部接口鉴权拦截器。
 *
 * agent（8080）不直接面向公网用户，仅接受业务后端 backend（8081）的服务间调用。
 * 所有 /api/agent/** 请求（除健康检查外）必须携带请求头 {@code X-Agent-Token}，
 * 值与 agent 配置的 {@code agent.internal-token}（环境变量 AGENT_INTERNAL_TOKEN 注入）一致。
 *
 * 安全要点：
 *  - 健康检查 GET /api/agent/health 放行（供 systemd/探活/backend isHealthy 使用）
 *  - token 比较使用恒定时间比较（MessageDigest.isEqual），降低时序侧信道风险
 *  - token 未配置（空）时拒绝一切受保护请求，避免"忘配环境变量就裸奔"
 */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InternalTokenInterceptor.class);

    /** 服务间鉴权请求头名 */
    public static final String TOKEN_HEADER = "X-Agent-Token";

    private final byte[] expectedToken;

    public InternalTokenInterceptor(@Value("${agent.internal-token:}") String internalToken) {
        this.expectedToken = internalToken == null ? new byte[0]
                : internalToken.trim().getBytes(StandardCharsets.UTF_8);
        if (internalToken == null || internalToken.isBlank()) {
            log.warn("[InternalAuth] agent.internal-token 未配置！除 /health 外所有接口将拒绝访问（请通过环境变量 AGENT_INTERNAL_TOKEN 注入）");
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 健康检查放行（探活用，不泄露任何业务数据）
        if ("GET".equalsIgnoreCase(request.getMethod())
                && "/api/agent/health".equals(request.getRequestURI())) {
            return true;
        }

        String provided = request.getHeader(TOKEN_HEADER);
        if (provided != null && expectedToken.length > 0
                && MessageDigest.isEqual(expectedToken, provided.trim().getBytes(StandardCharsets.UTF_8))) {
            return true;
        }

        log.warn("[InternalAuth] 拒绝未授权访问: {} {}（来源 {}）",
                request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"agent 内部接口需要有效的 "
                + TOKEN_HEADER + " 请求头\"}");
        return false;
    }
}
