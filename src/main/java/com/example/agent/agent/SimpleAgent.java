package com.example.agent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 核心 Agent 接口 —— LangChain4j 声明式 AiService
 *
 * 关键注解规范（LangChain4j 1.17.2 严格校验）：
 *   1. 方法注解：@SystemMessage（全局人设）、@UserMessage（用户消息模板，用 {{xxx}} 占位符）
 *   2. 参数注解：@MemoryId（会话ID，按ID隔离记忆）、@V("name")（与模板 {{name}} 绑定）
 *   3. 编译时必须加 -parameters 参数（否则反射拿不到参数真实名）
 *
 * 调用链路：Controller → simpleAgent.chat(userId, userMsg)
 *   → AiServices 动态代理组装 Prompt（SystemMessage + 历史对话 + UserMessage模板填入变量）
 *   → 调用 ChatModel → 如果模型决定用工具 → 反射执行 @Tool 方法 → 结果回填再调 LLM
 *   → 返回最终文本
 */
public interface SimpleAgent {

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
    @UserMessage("{{message}}")
    String chat(@MemoryId String userId, @V("message") String userMessage);
}
