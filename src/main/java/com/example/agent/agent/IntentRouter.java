package com.example.agent.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 意图路由（Router）：判断一轮对话该交给哪个「专项专家（子代理）」处理，不命中则交回主 Agent（Supervisor）。
 *
 * 与委派（Agent-as-Tool）的区别：
 *   - 委派：主 Agent 自己决定是否调 delegate 工具，可多轮组合多个专家；
 *   - 路由：本轮只跑命中的那一个专家（直接把它的身份提示 + 私有工具挂到主 Agent 上），不进入 Supervisor。
 *
 * 命中后由 {@link AgentFactory} 用专家身份构建主 Agent —— 复用同一条流式/记忆/RAG/审批/trace 链路，
 * 不另建运行时。默认关闭（agent.router.enabled=false），开启后每轮多一次轻量判定调用。
 * 任何异常 fail-open 回退主 Agent，绝不阻断对话。
 */
@Component
public class IntentRouter {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private final ChatModel chatModel;
    private final boolean enabled;

    public IntentRouter(ChatModel chatModel,
                        @Value("${agent.router.enabled:false}") boolean enabled) {
        this.chatModel = chatModel;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 候选专家：name（唯一标识）+ 短描述（供判定模型理解职责边界） */
    public record Candidate(String name, String description) {
    }

    /** 路由判定 AiService（入参即用户这句原话） */
    interface RouteDecider {
        String decide(@UserMessage String message);
    }

    /**
     * 运行路由判定。
     *
     * @return 命中的专家 name；未命中 / 未启用 / 判定异常均返回 empty（调用方回退主 Agent）
     */
    public Optional<String> route(String message, List<Candidate> candidates) {
        if (!enabled || candidates == null || candidates.isEmpty() || message == null || message.isBlank()) {
            return Optional.empty();
        }
        try {
            String system = systemMessage(candidates);
            RouteDecider decider = AiServices.builder(RouteDecider.class)
                    .chatModel(chatModel)
                    .systemMessageProvider(memoryId -> system)
                    .build();
            String raw = decider.decide(message);
            Optional<String> hit = match(raw, candidates);
            log.info("[Router] 候选 {} 个，判定={} → {}", candidates.size(), raw, hit.orElse("主 Agent"));
            return hit;
        } catch (Exception e) {
            // 路由失败不阻断对话：fail-open 交给主 Agent（Supervisor）
            log.warn("[Router] 意图路由失败，回退主 Agent: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String systemMessage(List<Candidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你在做意图路由：判断用户的这句话应当交给下面哪一个「专项专家」处理。
                只输出被选中专家的 name（原样照抄），若不属于任何专家的职责范围，只输出 none。
                不要解释、不要输出其他任何内容。

                可选专家：
                """);
        for (Candidate c : candidates) {
            sb.append("- ").append(c.name()).append("：")
                    .append(c.description() == null ? "" : c.description()).append('\n');
        }
        return sb.toString();
    }

    /** 从判定模型回复里解析命中的专家名（精确匹配优先，其次包含匹配）；none/未命中返回 empty */
    static Optional<String> match(String raw, List<Candidate> candidates) {
        if (raw == null || raw.isBlank() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        String text = raw.strip();
        String lower = text.toLowerCase(Locale.ROOT);
        if ("none".equals(lower) || lower.startsWith("none ")) {
            return Optional.empty();
        }
        for (Candidate c : candidates) {
            if (c.name() != null && c.name().equalsIgnoreCase(text)) {
                return Optional.of(c.name());
            }
        }
        for (Candidate c : candidates) {
            if (c.name() != null && !c.name().isBlank()
                    && lower.contains(c.name().toLowerCase(Locale.ROOT))) {
                return Optional.of(c.name());
            }
        }
        return Optional.empty();
    }
}
