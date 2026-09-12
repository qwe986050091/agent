package com.example.agent.approval;

import java.util.concurrent.CompletableFuture;

/**
 * 一条待审批条目：审批请求元数据 + 阻塞工具线程等待的决策 Future。
 */
class ApprovalEntry {

    private final ApprovalRequest request;
    private final CompletableFuture<ApprovalDecision> future = new CompletableFuture<>();

    ApprovalEntry(ApprovalRequest request) {
        this.request = request;
    }

    ApprovalRequest request() {
        return request;
    }

    CompletableFuture<ApprovalDecision> future() {
        return future;
    }

    boolean isDone() {
        return future.isDone();
    }
}
