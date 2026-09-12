package com.example.agent.tools;

import com.example.agent.tools.web.HttpRequestTools;
import com.example.agent.tools.web.WebFetchTools;
import com.example.agent.tools.web.WebSearchTools;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置 Tool 注册表：按 Tool 名称获取 Tool 实例。
 *
 * 为什么需要这个？
 *   用户上传 JSON Skill 时，通过 toolNames（如 ["webSearchTools"]）引用内置 Tool。
 *   ToolRegistry 负责把名称映射到实际的 Tool 对象实例。
 *
 * 命名规则：key 为 Spring Bean 风格名（类名首字母小写，如 CalculatorTools → calculatorTools）；
 * 条件化工具（web/http，可经 agent.tools.* 配置关闭）通过 ObjectProvider 可选注入，
 * 关闭时不注册、不影响启动。
 */
@Component
public class ToolRegistry {

    private final Map<String, Object> tools = new HashMap<>();

    public ToolRegistry(CalculatorTools calculatorTools,
                        DateTimeTools dateTimeTools,
                        ObjectProvider<WebSearchTools> webSearchTools,
                        ObjectProvider<WebFetchTools> webFetchTools,
                        ObjectProvider<HttpRequestTools> httpRequestTools) {
        // 注册内置 Tool，key 为类名首字母小写
        register("calculatorTools", calculatorTools);
        register("dateTimeTools", dateTimeTools);
        webSearchTools.ifAvailable(t -> register("webSearchTools", t));
        webFetchTools.ifAvailable(t -> register("webFetchTools", t));
        httpRequestTools.ifAvailable(t -> register("httpRequestTools", t));
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
