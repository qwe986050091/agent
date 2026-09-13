package com.example.agent.skill.impl;

import com.example.agent.skill.Skill;
import com.example.agent.tools.code.CodeExecutionTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Python 代码执行技能包：隔离沙箱中运行代码（断网/限时/限内存/限进程数，高危每次确认）。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.code", name = "enabled", havingValue = "true")
public class CodeRunnerSkill implements Skill {

    private final CodeExecutionTools codeExecutionTools;

    public CodeRunnerSkill(CodeExecutionTools codeExecutionTools) {
        this.codeExecutionTools = codeExecutionTools;
    }

    @Override
    public String name() {
        return "code-runner";
    }

    @Override
    public String shortDescription() {
        return "Python 代码执行：隔离沙箱运行 Python（断网/限时/限内存，高危需确认）";
    }

    @Override
    public String detailedDescription() {
        return """
                【Python 代码执行技能已激活】
                executePython 在一次性隔离沙箱中运行 Python 3：完全断网、默认 30 秒/256MB/64 进程上限，
                文件系统只读，执行结束环境即销毁。每次执行前用户需确认。
                """;
    }

    @Override
    public String systemPrompt() {
        return """
                【Python 代码执行技能】
                - 适用：精确计算、数据处理与转换、算法验证、可通过标准库完成的结构化任务；
                  无法联网获取信息（沙箱完全断网），需要最新信息时改用联网搜索技能。
                - 只提供 executePython 一个工具：提交完整可独立运行的 Python 3 代码，用 print() 输出结果；
                  不要依赖上次执行残留的文件或进程，每次执行环境都是全新的。
                - 代码须自包含、幂等；避免打印超大输出（超过约 8000 字符会截断）。
                - 每次调用弹窗向用户展示完整代码并等待确认；被拒绝时如实告知，不要换写法重复提交相同意图。
                - 执行失败时，先根据退出码与报错信息自行修正代码重试一次；仍失败则向用户说明错误与限制。
                """;
    }

    @Override
    public List<Object> tools() {
        return List.of(codeExecutionTools);
    }
}
