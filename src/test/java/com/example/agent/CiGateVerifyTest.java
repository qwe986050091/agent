package com.example.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 临时文件：故意让断言失败，用于验证分支保护的必需检查会拦住合并。
 * 只存在于临时分支 ci-gate-verify 上，验证完成后随分支一起删除。
 */
class CiGateVerifyTest {

    @Test
    void deliberatelyFails() {
        assertThat(1).isEqualTo(2);
    }
}
