package com.example.agent.tools.code;

import com.example.agent.security.UntrustedContentWrapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Python 代码执行服务（P1-6.3）。
 *
 * 隔离边界（生产 Linux）：每次调用经 {@code systemd-run --collect --wait ...} 生成一次性 transient unit，
 * cgroup v2 限内存/CPU/进程数，namespace 断网 + 只读根文件系统，DynamicUser 匿名身份运行，
 * RuntimeMaxSec 到点由 systemd 杀整个 cgroup（含 fork 出的所有子进程）。
 * agent 以 root 运行时直接调 systemd-run；非 root 时经 sudoers 白名单调用参数固定的 wrapper。
 *
 * 非 Linux 开发机：runtime=auto 回退 direct（直接本地 python，<b>无任何隔离</b>，启动有醒目 WARN）；
 * runtime=systemd 在非 Linux 上 fail-fast，绝不静默降级。
 *
 * 资源闸门：Semaphore 限并发、输出字符上限（超限排空丢弃防管道阻塞）、Java waitFor 兜底超时、
 * 线程中断（用户停止/断连）时 destroyForcibly 回收进程。
 */
@Component
@ConditionalOnProperty(prefix = "agent.tools.code", name = "enabled", havingValue = "true")
public class CodeSandboxService {

    private static final Logger log = LoggerFactory.getLogger(CodeSandboxService.class);

    private enum Mode { SYSTEMD_ROOT, SYSTEMD_SUDO, DIRECT }

    private final CodeToolsProperties props;
    private final Mode mode;
    private final Path workDir;
    private final String systemdRunPath;
    private final String pythonExe;
    private final Semaphore permits;
    private final ExecutorService streamPump;
    private final AtomicLong seq = new AtomicLong();
    /** 沙箱 stdout 是间接注入载体（脚本可伪造"工具结果/系统指令"文本）：输出统一边界化（P4-17） */
    private final UntrustedContentWrapper untrustedContentWrapper;

    public CodeSandboxService(CodeToolsProperties props,
                              UntrustedContentWrapper untrustedContentWrapper) throws IOException {
        this.props = props;
        this.untrustedContentWrapper = untrustedContentWrapper;
        this.permits = new Semaphore(Math.max(1, props.getMaxConcurrency()), true);
        this.workDir = Paths.get(props.getWorkDir()).toAbsolutePath().normalize();
        Files.createDirectories(workDir);
        if (!Files.isWritable(workDir)) {
            throw new IllegalStateException("代码沙箱工作目录不可写: " + workDir);
        }

        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        String runtime = props.getRuntime() == null
                ? "auto" : props.getRuntime().trim().toLowerCase();
        String resolvedPython = props.getPythonCommand() == null ? "" : props.getPythonCommand().trim();
        this.pythonExe = resolvedPython.isEmpty() ? (linux ? "/usr/bin/python3" : "python") : resolvedPython;

        switch (runtime) {
            case "direct" -> {
                this.mode = Mode.DIRECT;
                this.systemdRunPath = null;
                log.warn("========================================================");
                log.warn("[CodeSandbox] 运行在 DIRECT 模式：Python 代码【无任何隔离】直接在本机执行！");
                log.warn("[CodeSandbox] 该模式仅限本地开发，生产环境必须使用 runtime=auto/systemd。");
                log.warn("========================================================");
            }
            case "systemd", "auto" -> {
                if (!linux) {
                    if ("systemd".equals(runtime)) {
                        throw new IllegalStateException(
                                "agent.tools.code.runtime=systemd 仅支持 Linux；本地开发请用 runtime=direct");
                    }
                    this.mode = Mode.DIRECT;
                    this.systemdRunPath = null;
                    log.warn("[CodeSandbox] 非 Linux 环境，auto 回退 DIRECT 模式（无隔离，仅供本地开发）");
                    break;
                }
                String sdr = locateSystemdRun();
                if (sdr == null) {
                    // Linux 上绝不静默降级到无隔离模式
                    throw new IllegalStateException(
                            "已启用代码执行工具但找不到 systemd-run（需 systemd >= 236）；"
                                    + "本地开发请显式配置 agent.tools.code.runtime=direct");
                }
                this.systemdRunPath = sdr;
                boolean root = "root".equals(ProcessHandle.current().info().user().orElse(""));
                this.mode = root ? Mode.SYSTEMD_ROOT : Mode.SYSTEMD_SUDO;
                if (this.mode == Mode.SYSTEMD_SUDO
                        && !Files.isExecutable(Paths.get(props.getSudoWrapper()))) {
                    log.warn("[CodeSandbox] sudo wrapper 不存在或不可执行: {}；"
                            + "非 root 服务须先部署 wrapper（deploy-test/code-sandbox-setup.sh），否则执行将失败",
                            props.getSudoWrapper());
                }
            }
            default -> throw new IllegalStateException(
                    "agent.tools.code.runtime 仅支持 auto/systemd/direct: " + runtime);
        }

        this.streamPump = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "sandbox-output-pump-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });

        log.info("[CodeSandbox] 初始化完成: mode={}, workDir={}, timeout={}s, memory={}, cpu={}%, tasks={}, concurrency={}",
                mode, workDir, props.getTimeoutSeconds(), props.getMemoryMax(),
                props.getCpuQuota(), props.getTasksMax(), Math.max(1, props.getMaxConcurrency()));
    }

    /**
     * 执行一段 Python 代码（调用方须已完成人工审批）。
     * 返回直接面向 LLM 的结果文本：成功为 stdout/stderr 合并内容，失败带退出码与分类提示。
     */
    public String execute(String code) {
        long timeoutSeconds = Math.max(1, props.getTimeoutSeconds());
        int outputLimit = Math.max(200, props.getMaxOutputChars());
        if (!permits.tryAcquire()) {
            return "代码未执行：沙箱并发已满（上限 " + Math.max(1, props.getMaxConcurrency())
                    + " 个任务），请稍后重试。";
        }
        Path script = null;
        Process process = null;
        try {
            script = writeScript(code);
            List<String> command = buildCommand(script);
            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true)   // stdout/stderr 合并：Python traceback 直接可见
                    .directory(workDir.toFile());
            long start = System.nanoTime();
            log.info("[CodeSandbox] 开始执行: mode={}, chars={}", mode, code.length());
            process = pb.start();

            final Process started = process;
            Future<StreamResult> outputFuture =
                    streamPump.submit(() -> pumpOutput(started, outputLimit));

            boolean finished;
            try {
                // systemd RuntimeMaxSec 先在 cgroup 层终止；此处只兜底 systemd-run 自身卡死
                finished = process.waitFor(timeoutSeconds + 10L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 7.2 取消传播：用户停止/断连时工作线程被 interrupt
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                outputFuture.cancel(true);
                log.warn("[CodeSandbox] 执行被中断（用户停止/断连），已强杀沙箱进程");
                return "代码执行已取消：执行过程中被中断，沙箱进程已终止。";
            }

            StreamResult out;
            try {
                out = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                out = new StreamResult("", true);
            }

            if (!finished) {
                process.destroyForcibly();
                log.warn("[CodeSandbox] 执行超过 {}s+10s 兜底时限，已强杀", timeoutSeconds);
                return "代码执行超时（上限 " + timeoutSeconds + " 秒），沙箱已强制终止整个进程组。"
                        + (out.text().isEmpty() ? "" : "\n超时前输出：\n" + out.text());
            }

            int exitCode = process.exitValue();
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[CodeSandbox] 执行结束: exit={}, cost={}ms, truncated={}",
                    exitCode, costMs, out.truncated());
            return formatResult(exitCode, out, timeoutSeconds);
        } catch (IOException e) {
            log.warn("[CodeSandbox] 启动沙箱失败: {}", e.getMessage());
            return "代码执行失败：无法启动沙箱执行器（" + e.getMessage()
                    + "）。请联系管理员检查沙箱部署。";
        } finally {
            if (script != null) {
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignore) {
                    // 临时脚本清理失败不影响主流程
                }
            }
            permits.release();
        }
    }

    // ==================== 命令构造 ====================

    private List<String> buildCommand(Path script) {
        return switch (mode) {
            case SYSTEMD_ROOT -> systemdCommand(script);
            // 非 root：sudo -n 调用 wrapper；wrapper 只接受白名单目录内单个 .py，安全参数在其内固定
            case SYSTEMD_SUDO -> List.of(
                    "sudo", "-n", props.getSudoWrapper(), script.toString());
            // 本地开发：无隔离
            case DIRECT -> List.of(pythonExe, "-I", script.toString());
        };
    }

    /** systemd-run transient unit：一次性、隔离、资源受限，参数全部在此固定，调用方无法注入 */
    private List<String> systemdCommand(Path script) {
        String scriptPath = script.toString();
        List<String> cmd = new ArrayList<>(List.of(
                systemdRunPath, "--collect", "--wait", "--pipe", "--quiet",
                "-p", "DynamicUser=yes",                 // 匿名动态 uid，执行完即销
                "-p", "PrivateNetwork=yes",              // 完全断网（无 lo 之外的任何栈）
                "-p", "MemoryMax=" + props.getMemoryMax(),
                "-p", "CPUQuota=" + props.getCpuQuota() + "%",
                "-p", "TasksMax=" + props.getTasksMax(),
                "-p", "RuntimeMaxSec=" + props.getTimeoutSeconds(),
                "-p", "TimeoutStopSec=2",                // 超时先 TERM，2s 后 KILL 整个 cgroup
                "-p", "ProtectSystem=strict",            // 整个文件系统层次只读挂载
                "-p", "ProtectHome=yes",
                "-p", "PrivateTmp=yes",
                "-p", "NoNewPrivileges=yes",
                "-p", "CapabilityBoundingSet=",          // 清空所有 capabilities
                "-p", "RestrictSUIDSGID=yes",
                "-p", "ProtectKernelTunables=yes",
                "-p", "ProtectControlGroups=yes",
                "-p", "RestrictRealtime=yes",
                "-p", "LockPersonality=yes",
                "-p", "MemoryDenyWriteExecute=yes",      // CPython/numpy 无需动态可执行内存
                // 不用 --bind-read-only：部分发行版（OpenCloudOS 9 / systemd 255 定制）systemd-run
                // 不支持该选项；ProtectSystem=strict 已使整棵文件树只读，脚本 755/644 DAC 可正常读取
                "/usr/bin/python3", "-I", scriptPath));
        return cmd;
    }

    // ==================== 内部机制 ====================

    private Path writeScript(String code) throws IOException {
        String fileName = "sb-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + ".py";
        Path script = workDir.resolve(fileName);
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-r--r--");
            Files.createFile(script, PosixFilePermissions.asFileAttribute(perms));
            Files.writeString(script, code, StandardCharsets.UTF_8);
        } catch (UnsupportedOperationException e) {
            // Windows 开发机无 POSIX 权限
            Files.writeString(script, code, StandardCharsets.UTF_8);
        }
        return script;
    }

    /**
     * 单线程排空合并输出流：达到上限后继续读但丢弃内容，
     * 防止子进程阻塞在写满的管道上导致超时判定失真。
     */
    private StreamResult pumpOutput(Process process, int limit) {
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        char[] buffer = new char[8192];
        try (InputStream is = process.getInputStream();
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            int n;
            while ((n = reader.read(buffer)) != -1) {
                if (sb.length() < limit) {
                    int allowed = Math.min(n, limit - sb.length());
                    sb.append(buffer, 0, allowed);
                    if (allowed < n) {
                        truncated = true;
                    }
                } else {
                    truncated = true;
                }
            }
        } catch (IOException e) {
            // 进程被强杀时流读取可能抛异常，保留已读内容
            truncated = true;
        }
        return new StreamResult(sb.toString(), truncated);
    }

    private String formatResult(int exitCode, StreamResult out, long timeoutSeconds) {
        String text = out.text();
        String truncTail = out.truncated()
                ? "\n...（输出超过 " + props.getMaxOutputChars() + " 字符，已截断）" : "";
        if (exitCode == 0) {
            if (text.isBlank()) {
                return "代码执行完成（无标准输出）。" + truncTail;
            }
            // 沙箱输出边界化（P4-17 §5）：stdout 可能伪造指令文本，只可引用不可执行
            return untrustedContentWrapper.wrap("code-runner", text.stripTrailing()) + truncTail;
        }
        String hint = switch (exitCode) {
            case 137 -> "（进程被 SIGKILL 终止，通常是超出 " + props.getMemoryMax() + " 内存上限）";
            case 143 -> "（进程被 SIGTERM 终止，通常是触达 " + timeoutSeconds + " 秒执行时限）";
            default -> "";
        };
        String body = text.isBlank()
                ? "（无输出）" : untrustedContentWrapper.wrap("code-runner", text.stripTrailing());
        return "代码执行失败，退出码 " + exitCode + hint + "，输出如下：\n" + body + truncTail;
    }

    private static String locateSystemdRun() {
        String[] candidates = {"/usr/bin/systemd-run", "/bin/systemd-run", "/usr/local/bin/systemd-run"};
        for (String c : candidates) {
            if (Files.isExecutable(Paths.get(c))) {
                return c;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(System.getProperty("path.separator"))) {
                Path p = Paths.get(dir, "systemd-run");
                if (Files.isExecutable(p)) {
                    return p.toString();
                }
            }
        }
        return null;
    }

    @PreDestroy
    void shutdown() {
        streamPump.shutdownNow();
    }

    private record StreamResult(String text, boolean truncated) {
    }
}
