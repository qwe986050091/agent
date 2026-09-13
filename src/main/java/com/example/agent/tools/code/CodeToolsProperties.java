package com.example.agent.tools.code;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 代码执行沙箱配置（agent.tools.code.*）。
 *
 * 默认 enabled=false：功能完全休眠，不创建任何 Bean（与 MCP 空配置同样的谨慎策略）。
 * 生产启用方式：systemd unit 注入 AGENT_TOOLS_CODE_ENABLED=true（无需改 jar 内 yml），
 * 并预先部署 sudo wrapper（见 deploy-test/code-sandbox-setup.sh）。
 */
@Component
@ConfigurationProperties(prefix = "agent.tools.code")
public class CodeToolsProperties {

    /** 代码执行总开关（关闭后工具/技能 Bean 均不注册） */
    private boolean enabled = false;

    /** 执行模式：auto（Linux 强制 systemd 沙箱，非 Linux 回退 direct）| systemd（仅沙箱）| direct（无隔离，仅本地开发） */
    private String runtime = "auto";

    /** 单次执行最长时间（秒）：systemd RuntimeMaxSec 硬杀整个 cgroup，Java 侧再加 10s 兜底 */
    private long timeoutSeconds = 30;

    /** cgroup 内存上限（systemd 字节数语法，如 256M）；超出由内核 OOM killer 终止 */
    private String memoryMax = "256M";

    /** CPU 配额百分比（100 = 1 核） */
    private int cpuQuota = 100;

    /** 进程/线程数上限（防 fork 炸弹） */
    private int tasksMax = 64;

    /** 同时执行的沙箱任务数（信号量限流，防多会话并发烧资源） */
    private int maxConcurrency = 1;

    /** 返回给 LLM 的合并输出字符上限（超出继续排空但丢弃内容） */
    private int maxOutputChars = 8000;

    /** 临时脚本工作目录；sudo wrapper 模式必须与 wrapper 内白名单目录一致 */
    private String workDir = "data/sandbox";

    /** 非 root 运行时经 sudo 调用的 wrapper 绝对路径（root:root 0755，参数固定不可注入） */
    private String sudoWrapper = "/opt/agent-sandbox/run-sandbox";

    /** direct 模式 Python 解释器；留空 = Linux 用 /usr/bin/python3，其他平台用 python */
    private String pythonCommand = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getMemoryMax() { return memoryMax; }
    public void setMemoryMax(String memoryMax) { this.memoryMax = memoryMax; }
    public int getCpuQuota() { return cpuQuota; }
    public void setCpuQuota(int cpuQuota) { this.cpuQuota = cpuQuota; }
    public int getTasksMax() { return tasksMax; }
    public void setTasksMax(int tasksMax) { this.tasksMax = tasksMax; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getMaxOutputChars() { return maxOutputChars; }
    public void setMaxOutputChars(int maxOutputChars) { this.maxOutputChars = maxOutputChars; }
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public String getSudoWrapper() { return sudoWrapper; }
    public void setSudoWrapper(String sudoWrapper) { this.sudoWrapper = sudoWrapper; }
    public String getPythonCommand() { return pythonCommand; }
    public void setPythonCommand(String pythonCommand) { this.pythonCommand = pythonCommand; }
}
