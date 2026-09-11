package com.example.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 入站 traceId 过滤器（agent 侧）。
 *
 * - 采用 backend 透传的 {@code X-Trace-Id}（合法 {@code [A-Za-z0-9-]{8,64}}），
 *   缺失/非法时兜底生成 UUID，保证全链路每跳都有 id；
 * - 写 MDC 供日志 pattern 输出，并作为请求属性供 Controller 交接给 SSE 工作线程；
 * - 响应头回显，便于调用方核对；请求结束清理 MDC（工作线程的清理由 Controller 负责）。
 */
@Component
@Order(1)
public class TraceMdcFilter extends OncePerRequestFilter {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String TRACE_REQUEST_ATTR = "traceId";

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = normalize(request.getHeader(TRACE_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        MDC.put(TraceContextHolder.MDC_TRACE_ID, traceId);
        request.setAttribute(TRACE_REQUEST_ATTR, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContextHolder.MDC_TRACE_ID);
        }
    }

    /** 合法（非空、字符集与长度合规）返回原值，否则 null */
    static String normalize(String traceId) {
        if (traceId == null) {
            return null;
        }
        String trimmed = traceId.trim();
        return TRACE_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }
}
