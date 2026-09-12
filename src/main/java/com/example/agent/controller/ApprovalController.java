package com.example.agent.controller;

import com.example.agent.approval.ApprovalDecision;
import com.example.agent.approval.ApprovalRegistry;
import com.example.agent.approval.ApprovalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 高危工具审批决策接口（内部接口，X-Agent-Token 由 InternalTokenInterceptor 强制校验）。
 *
 *   POST /api/agent/approvals/{approvalId}/decision
 *     body: {"userId":"...", "approved":true, "remember":false}
 *
 * 归属：仅条目所属用户可决策（不匹配统一 404，不泄露条目存在性）；重复/过期决策 409。
 */
@RestController
@RequestMapping("/api/agent/approvals")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalRegistry registry;

    public ApprovalController(ApprovalRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/{approvalId}/decision")
    public ResponseEntity<?> decide(@PathVariable String approvalId,
                                    @RequestBody(required = false) DecisionRequest body) {
        if (body == null || body.userId() == null || body.userId().isBlank() || body.approved() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "BAD_REQUEST", "message", "userId/approved 必填"));
        }
        boolean remember = Boolean.TRUE.equals(body.remember());
        ApprovalRegistry.ResolveResult result = registry.resolve(
                approvalId, body.userId(), new ApprovalDecision(body.approved(), remember));

        return switch (result.outcome()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", "APPROVAL_NOT_FOUND",
                            "message", "审批请求不存在、已过期或不属于该用户"));
            case ALREADY_DECIDED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "APPROVAL_DECIDED", "message", "该审批已被处理（决策/超时/取消）"));
            case RESOLVED -> {
                ApprovalRequest req = result.request();
                log.info("[Approval] 决策回执: id={}, traceId={}, tool={}, approved={}, remember={}",
                        approvalId, req.traceId(), req.tool(), body.approved(), remember);
                yield ResponseEntity.ok(Map.of(
                        "approvalId", approvalId,
                        "state", body.approved() ? "approved" : "denied",
                        "tool", req.tool(),
                        "conversationId", req.conversationId() == null ? 0 : req.conversationId(),
                        "remember", remember));
            }
        };
    }

    /** 决策请求体 */
    public record DecisionRequest(String userId, Boolean approved, Boolean remember) {
    }
}
