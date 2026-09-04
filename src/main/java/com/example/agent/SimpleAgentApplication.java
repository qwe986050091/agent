package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Simple Agent 启动类
 */
@SpringBootApplication
public class SimpleAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimpleAgentApplication.class, args);
        System.out.println("""
                
                ==========================================================
                  🤖 Simple Agent 启动成功（LangChain4j + Spring Boot）
                  API 文档:
                    健康检查: GET  http://localhost:8080/api/agent/health
                    对话接口: GET  http://localhost:8080/api/agent/chat?userId=u1&message=你好
                              POST http://localhost:8080/api/agent/chat
                ==========================================================
                """);
    }
}
