package com.example.agent.observability;

import java.util.UUID;

/**
 * 委派节点 id 生成：短、唯一、可直接用于前端建树。
 * 仅在单个回合的 trace 内要求唯一，无需全局有序。
 */
public final class DelegationIds {

    private DelegationIds() {
    }

    public static String next() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
