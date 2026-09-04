package com.example.agent.agent;

import dev.langchain4j.service.*;

/**
 * 核心 Agent 接口 —— LangChain4j 的「声明式魔法」
 *
 * 你只需要写接口 + 注解，LangChain4j 会在启动时：
 * 1. 自动扫描 Spring 容器中所有 @Tool 注解的方法并注册为工具
 * 2. 自动注入 ChatLanguageModel（配置来自 application.yml）
 * 3. 自动配置 ChatMemory（按 @MemoryId 隔离不同用户的对话历史）
 * 4. 自动生成代理实现类，帮你完成：Prompt 组装 → 调用 LLM → 解析工具调用 → 执行工具 → 再调用 LLM 的 ReAct 循环
 *
 * 用法：直接 @Autowired 注入 SimpleAgent 调用 chat() 即可
 */
public interface SimpleAgent {

    /**
     * 与 Agent 对话（带用户级记忆）
     *
     * @SystemMessage 系统提示词：定义 Agent 的人设和行为规则
     * @MemoryId      用于隔离多用户对话记忆，不同 userId 有不同的历史
     * @UserMessage   用户输入（等同于你直接把 user text 传给模型）
     */
    @SystemMessage("""
            你是一个聪明、友好的 AI 助手，叫「小 Agent」。
            你可以：
            1. 回答用户的一般性问题
            2. 使用【计算器工具】完成数学运算（任何加减乘除、幂运算请一定要调用工具，不要自己猜）
            3. 使用【日期时间工具】获取准确的当前时间/日期（不要自己编造日期时间）
            
            规则：
            - 对于数学计算类问题，必须调用计算器工具，不能心算或猜测
            - 对于日期/时间类问题，必须调用日期工具，不能编造
            - 用中文回答，语气自然、简洁、有帮助
            - 如果无法回答或工具调用失败，直接如实告知
            """)
    String chat(@MemoryId String userId, @UserMessage String userMessage);
}
