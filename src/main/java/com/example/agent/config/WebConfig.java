package com.example.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * agent Web 配置：注册内部服务间鉴权拦截器，保护全部 /api/agent/** 接口。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final InternalTokenInterceptor internalTokenInterceptor;

    public WebConfig(InternalTokenInterceptor internalTokenInterceptor) {
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalTokenInterceptor)
                .addPathPatterns("/api/agent/**");
    }
}
