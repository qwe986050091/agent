package com.example.agent.tools.code;

import com.example.agent.approval.ApprovalGate;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 代码执行工具：在隔离沙箱中运行 Python（高危，CONFIRM/high）。
 * 顺序：人工确认（向用户展示完整代码）→ {@link CodeSandboxService} 在沙箱内执行。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.code", name = "enabled", havingValue = "true")
public class CodeExecutionTools {

    private static final Logger log = LoggerFactory.getLogger(CodeExecutionTools.class);

    private final CodeSandboxService sandboxService;

    public CodeExecutionTools(CodeSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Tool("""
            在完全隔离的一次性 Linux 沙箱中执行 Python 3 代码，返回标准输出（stdout/stderr 合并）。
            沙箱限制：无任何网络访问（urllib/requests/pip 等全部不可用，不要尝试联网或安装第三方库）；
            有严格的执行时限（默认 30 秒）、内存上限（默认 256MB）与进程数上限（默认 64）；
            文件系统只读，沙箱每次执行后销毁，不要依赖跨调用的文件或进程状态。
            仅可使用 Python 标准库（生产环境可能预装少量常用计算库）；用 print() 输出最终答案，
            输出过长会被截断（约 8000 字符），不要打印超大对象。
            该工具执行真实代码，每次调用前都会弹窗请用户确认（展示完整代码）；
            被拒绝时如实告知用户，不要重复发起。
            """)
    public String executePython(String code) {
        if (code == null || code.isBlank()) {
            return "未提供可执行的 Python 代码。";
        }
        log.info("[Tool] executePython: {} chars", code.length());
        // 高危：审批预览即完整代码（审批层会截断到预览上限）
        ApprovalGate.requireConfirmation("executePython", code);
        return sandboxService.execute(code);
    }
}
