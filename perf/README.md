# P2-G JVM 压测与调优工具

面向 agent 服务的可复现压测工具：隔离上游 LLM 波动，只测 agent JVM 的
线程池、SSE 编解码、内存分配与 GC 行为，同时用于 G1 / ZGC 对比。

完整报告：[`d:\code\agent_frontend\.trae\documents\reports\p2-g-jvm-loadtest-report-2026-10-23.html`](../../agent_frontend/.trae/documents/reports/p2-g-jvm-loadtest-report-2026-10-23.html)

## 文件

| 文件 | 作用 |
|---|---|
| `perf/stub-openai.mjs` | OpenAI 兼容 stub：默认 40 个 token 块 × 5ms ≈ 200ms/请求，零成本、零外部依赖 |
| `perf/loadtest.mjs` | SSE 压测驱动：并发 N 路，统计成功率、p50/p95/p99、TTFT、503 |
| `perf/run-bench.mjs` | 编排器：启动 stub/agent → 预热 → 指标采样 → 100/500 并发 → GC 日志解析 → 汇总 |
| `perf/results/p2g-final/summary.json` | 2026-10-23 实测聚合结果（3 次中位数） |

## 前置要求

- JDK **21**（与生产一致；本机验证用 Temurin 21.0.12.1）
- Node.js 18+
- 已构建 agent jar：`.\mvnw.cmd -B -DskipTests package`

## 运行

```powershell
cd d:\code\agent

# JDK 21 路径按本机实际位置替换
$env:JAVA_HOME = 'C:\path\to\jdk-21'

node perf/run-bench.mjs --java "$env:JAVA_HOME\bin\java.exe" --repeats 3
```

默认组合：

- GC 配置：`g1`（生产参数 `-Xms128m -Xmx768m -XX:+UseG1GC`）、`zgc`（同堆 + `-XX:+UseZGC`）、
  `g1-tuned`（`-Xms768m` + `-XX:MaxGCPauseMillis=100` + `-XX:G1HeapRegionSize=4m`）
- 并发：100（总请求 300）/ 500（总请求 500），每个组合重启 JVM 跑 3 次取中位数
- 固定 `-XX:ActiveProcessorCount=4`，模拟生产 4 核

常用参数：

```powershell
node perf/run-bench.mjs --configs g1,zgc --levels 100,500 --repeats 3 `
  --java "$env:JAVA_HOME\bin\java.exe" `
  --jar target/simple-agent-1.0.0.jar `
  --out perf/results/my-run
```

## 测试环境隔离

- `OPENAI_BASE_URL` 指向本地 stub，**不调用真实 LLM**；
- `RAG_ENABLED=false`（压测路径排除 embedding/向量检索；生产 RAG 开销另计）；
- `AGENT_TOOLS_CODE_ENABLED=false`，MCP 懒加载不连接；
- agent 端口 18090、actuator 18091、stub 19090，避免与生产/本地服务冲突。

## 指标说明

- **成功率 / 503**：agent SSE 线程池（core 8 / max 24 / queue 100，AbortPolicy）满载时快速拒绝；
- **p95 / p99**：成功请求端到端耗时（发起 → `[DONE]`）；
- **TTFT p95**：发起 → 第一个 SSE data 事件；
- **RPS**：成功请求数 / 压测墙钟；
- **堆峰值 / 线程峰值 / SSE 在途峰值**：压测窗口内 `/actuator/prometheus` 每 200ms 采样；
- **GC 停顿**：`-Xlog:gc*` 解析（包含 warmup 后的窗口），并交叉核对 Micrometer `jvm_gc_pause_seconds_*`。

## 实测摘要（2026-10-23，3 次中位数）

| 配置 | 并发 | 成功率 | p95 ms | p99 ms | RPS | 堆峰值 MB | GC 次数 | GC 总停顿 ms | 最大停顿 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| G1（生产） | 100 | 100% | 735.5 | 754.8 | 149.3 | 119 | 10 | 73.6 | 16.2 |
| G1（生产） | 500 | 50.8% | 1360.4 | 1372.2 | 182.9 | 200 | 9 | 75.4 | 18.6 |
| ZGC | 100 | 100% | 706.3 | 729.6 | 151.5 | 332 | 12 | 0.3 | 0.06 |
| ZGC | 500 | 47.0% | 1125.3 | 1130.3 | 204.1 | 356 | 22 | 0.45 | 0.05 |
| G1 调优 | 100 | 100% | 715.2 | 734.4 | 151.6 | 381 | 1 | 26.1 | 26.1 |
| G1 调优 | 500 | 48.0% | 1223.3 | 1225.2 | 182.5 | 338 | 2 | 52.6 | 34.3 |

结论：**继续用 G1 生产参数**；瓶颈是 SSE 线程池容量（500 并发约一半 503），不是 GC；
ZGC 停顿近零但堆占用约为 G1 的 1.8–2.8 倍、CPU 更高，4c4g / 768m 下不建议切换。

## JaCoCo 覆盖率门禁

两个 Java 仓均已接入 `jacoco-maven-plugin 0.8.15`：

```powershell
# 覆盖率采集 + 报告 + 门禁（check 绑定在 verify）
.\mvnw.cmd -B clean verify
```

- agent：行覆盖率 **49.53%**，阈值 **47%**（`target/site/jacoco/index.html`）
- backend：行覆盖率 **34.63%**，阈值 **32%**（`target/site/jacoco/index.html`）
- CI 已从 `clean test` 改为 `clean verify`，低于阈值即 fail

> 注意：surefire 的 `argLine` 已改为 `@{argLine} ...`，由 JaCoCo `prepare-agent` 注入；
> 删掉会让覆盖率静默归零。
