package com.example.agent.eval;

/**
 * 被测系统（Agent）的调用抽象：把「一条用例 → 一回合 trace 观测」这件事与「怎么调用」解耦。
 *
 * <p>两种实现：
 * <ul>
 *   <li>{@link InProcessAgentInvoker}：同进程直接调 {@code AgentFactory} 并就近读取
 *       {@code TraceCollector}（评测首选，轨迹最全、无需起服务）；</li>
 *   <li>{@link HttpAgentInvoker}：对已部署的 agent 打 HTTP（{@code POST /api/agent/chat}），
 *       解析其返回的 trace 数据——用于对线上环境跑评测。</li>
 * </ul>
 */
public interface AgentInvoker {

    /** 调用一次：以指定 userId 执行该用例，返回一回合观测。实现不应抛异常，失败时返回 {@link EvalTurn#failure}。 */
    EvalTurn invoke(EvalCase evalCase, String userId);

    /** 人类可读的调用方式标签（写入报告，如 "in-process" / "http"），便于区分不同运行。 */
    String describe();

    /** 运行期间使用的模型名（写入报告；未知返回空串） */
    default String modelName() {
        return "";
    }
}
