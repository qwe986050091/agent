package com.example.agent.approval;

/**
 * 高危工具未获批准时抛出：用户拒绝 / 等待超时 / 通道不支持确认 / 流被取消。
 *
 * 抛出后由 LangChain4j 工具执行链转为错误工具结果回灌 LLM（实测行为），
 * LLM 据此向用户说明动作未执行；不允许吞掉后继续执行工具。
 */
public class ApprovalDeniedException extends RuntimeException {

    public ApprovalDeniedException(String message) {
        super(message);
    }
}
