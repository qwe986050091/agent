package com.example.agent.tools;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置 Tool 注册表：按 Tool 名称获取 Tool 实例。
 *
 * 为什么需要这个？
 *   用户上传 JSON Skill 时，通过 toolNames（如 ["getWeather","getCurrentTime"]）引用内置 Tool。
 *   ToolRegistry 负责把名称映射到实际的 Tool 对象实例。
 *
 * 命名规则：Tool 名称 = 类名首字母小写（如 CalculatorTools → calculatorTools）
 *           或者用 @Tool 注解里指定的名称（如果有的话）
 */
@Component
public class ToolRegistry {

    private final Map<String, Object> tools = new HashMap<>();

    public ToolRegistry(CalculatorTools calculatorTools, DateTimeTools dateTimeTools) {
        // 注册内置 Tool，key 为类名首字母小写
        register("calculatorTools", calculatorTools);
        register("dateTimeTools", dateTimeTools);
    }

    /** 注册一个 Tool */
    public void register(String name, Object tool) {
        tools.put(name, tool);
    }

    /** 按名称获取 Tool */
    public Object getTool(String name) {
        return tools.get(name);
    }

    /** 获取所有已注册的 Tool 名称 */
    public java.util.Set<String> getAllToolNames() {
        return tools.keySet();
    }
}
