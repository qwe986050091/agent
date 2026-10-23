#!/usr/bin/env node
/**
 * JVM 压测编排器（P2-G）：自动完成
 *   启动 stub 上游 → 按 GC 配置启动 agent → 预热 → 采样 JVM 指标 → 100/500 并发压测
 *   → 停进程 → 解析 GC 日志 → 汇总 JSON。
 *
 * 用法（在 agent 仓库根目录）：
 *   node perf/run-bench.mjs --java "$env:JAVA_HOME\bin\java.exe" --jar target/simple-agent-1.0.0.jar
 *
 * 选项：
 *   --java       JDK 21 的 java.exe（默认 $JAVA_HOME/bin/java）
 *   --jar        agent 可执行 jar（默认 target/simple-agent-1.0.0.jar）
 *   --out        结果目录（默认 perf/results/<yyyyMMdd-HHmmss>）
 *   --configs    逗号分隔：g1,zgc,g1-tuned（默认全部）
 *   --levels     逗号分隔：100,500（默认 100,500）
 *
 * 说明：
 *   - 上游用 perf/stub-openai.mjs 代替真实 LLM，隔离外部延迟波动、零成本、可重复；
 *   - 每组配置/并发都重启 JVM，GC 与内存指标互不污染；
 *   - 端口：agent 18090、actuator 18091、stub 19090。
 */
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const PERF_DIR = path.dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = path.resolve(PERF_DIR, '..')

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback
}

const JAVA = arg('java', process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe') : 'java')
const JAR = path.resolve(REPO_ROOT, arg('jar', 'target/simple-agent-1.0.0.jar'))
const OUT = path.resolve(REPO_ROOT, arg('out', `perf/results/${stamp()}`))
const CONFIG_KEYS = arg('configs', 'g1,zgc,g1-tuned').split(',').map((s) => s.trim())
const LEVELS = arg('levels', '100,500').split(',').map((s) => Number(s.trim()))
const REPEATS = Number(arg('repeats', '3'))

const AGENT_PORT = 18090
const MGMT_PORT = 18091
const STUB_PORT = 19090
const BASE_ENV = {
  SERVER_PORT: String(AGENT_PORT),
  MANAGEMENT_PORT: String(MGMT_PORT),
  OPENAI_API_KEY: 'perf-stub-key',
  OPENAI_BASE_URL: `http://127.0.0.1:${STUB_PORT}`,
  OPENAI_MODEL: 'stub-model',
  OPENAI_FALLBACK_MODEL: 'stub-model',
  AGENT_INTERNAL_TOKEN: 'perf-token',
  RAG_ENABLED: 'false',
  RAG_RERANK_ENABLED: 'false',
  AGENT_TOOLS_CODE_ENABLED: 'false',
  AGENT_SAFETY_MODE: 'monitor',
}

const CONFIGS = {
  g1: {
    label: 'G1（生产参数）',
    flags: ['-Xms128m', '-Xmx768m', '-XX:MaxMetaspaceSize=192m',
      '-XX:ActiveProcessorCount=4', '-XX:+UseG1GC'],
  },
  zgc: {
    label: 'ZGC（对照）',
    flags: ['-Xms128m', '-Xmx768m', '-XX:MaxMetaspaceSize=192m',
      '-XX:ActiveProcessorCount=4', '-XX:+UseZGC'],
  },
  'g1-tuned': {
    label: 'G1 调优（Xms=Xmx + MaxGCPauseMillis=100）',
    flags: ['-Xms768m', '-Xmx768m', '-XX:MaxMetaspaceSize=192m',
      '-XX:ActiveProcessorCount=4', '-XX:+UseG1GC',
      '-XX:MaxGCPauseMillis=100', '-XX:G1HeapRegionSize=4m'],
  },
}

function stamp() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`
}

function round(v, digits = 1) {
  return v == null || !Number.isFinite(v) ? null : Number(v.toFixed(digits))
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

function httpGet(url, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { agent: false, headers: { Connection: 'close' } }, (res) => {
      let body = ''
      res.setEncoding('utf8')
      res.on('data', (c) => (body += c))
      res.on('end', () => resolve({ status: res.statusCode, body }))
    })
    req.setTimeout(timeoutMs, () => req.destroy(new Error('timeout')))
    req.on('error', reject)
  })
}

async function waitFor(url, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs
  let last = null
  while (Date.now() < deadline) {
    try {
      const res = await httpGet(url, 2000)
      if (res.status === 200) return
      last = `HTTP ${res.status}`
    } catch (e) {
      last = e.message
    }
    await sleep(400)
  }
  throw new Error(`${label} 未就绪（${timeoutMs}ms，最后状态：${last}）`)
}

function runNode(script, args, logFile) {
  return new Promise((resolve, reject) => {
    const out = fs.createWriteStream(logFile, { flags: 'a' })
    const child = spawn(process.execPath, [script, ...args], {
      cwd: REPO_ROOT,
      stdio: ['ignore', 'pipe', 'pipe'],
      env: process.env,
    })
    child.stdout.pipe(out)
    child.stderr.pipe(out)
    child.on('exit', (code) => {
      out.end()
      code === 0 ? resolve() : reject(new Error(`${path.basename(script)} exit=${code}`))
    })
  })
}

function startStub(logFile) {
  const out = fs.createWriteStream(logFile, { flags: 'a' })
  const child = spawn(process.execPath, [path.join(PERF_DIR, 'stub-openai.mjs')], {
    cwd: REPO_ROOT,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, STUB_PORT: String(STUB_PORT), STUB_CHUNKS: '40', STUB_CHUNK_DELAY_MS: '5' },
  })
  child.stdout.pipe(out)
  child.stderr.pipe(out)
  return { child, out }
}

function startAgent(configKey, level, gcLogRel, logFile) {
  const config = CONFIGS[configKey]
  const out = fs.createWriteStream(logFile, { flags: 'a' })
  const args = [
    ...config.flags,
    '-Dfile.encoding=UTF-8',
    `-Xlog:gc*:file=${gcLogRel}:time,uptime,level,tags`,
    '-jar',
    JAR,
  ]
  const child = spawn(JAVA, args, {
    cwd: REPO_ROOT,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: { ...process.env, ...BASE_ENV },
  })
  child.stdout.pipe(out)
  child.stderr.pipe(out)
  out.write(`\n===== ${configKey} level=${level} :: ${JAVA} ${args.join(' ')} =====\n`)
  return { child, out }
}

function stopChild(child, out, label) {
  return new Promise((resolve) => {
    if (!child || child.exitCode != null) {
      out?.end()
      return resolve()
    }
    const timer = setTimeout(() => {
      child.kill('SIGKILL')
    }, 8000)
    child.once('exit', () => {
      clearTimeout(timer)
      out?.end()
      resolve()
    })
    child.kill('SIGTERM')
    void label
  })
}

function parseProm(text) {
  const v = { heapBytes: null, gcCount: null, gcSumSeconds: null, gcMaxSeconds: null,
    threads: 0, sseActive: 0, cpu: 0 }
  let heap = 0
  let gcCount = 0
  let gcSum = 0
  let gcMax = 0
  for (const line of text.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue
    const sp = line.lastIndexOf(' ')
    if (sp < 0) continue
    const value = Number(line.slice(sp + 1))
    if (!Number.isFinite(value)) continue
    const key = line.slice(0, sp)
    const name = key.split('{')[0]
    if (name === 'jvm_memory_used_bytes' && key.includes('area="heap"')) heap += value
    else if (name === 'jvm_gc_pause_seconds_count') gcCount += value
    else if (name === 'jvm_gc_pause_seconds_sum') gcSum += value
    else if (name === 'jvm_gc_pause_seconds_max') gcMax = Math.max(gcMax, value)
    else if (name === 'jvm_threads_live_threads') v.threads = Math.max(v.threads, value)
    else if (name === 'agent_sse_active') v.sseActive = Math.max(v.sseActive, value)
    else if (name === 'process_cpu_usage') v.cpu = Math.max(v.cpu, value)
  }
  v.heapBytes = heap
  v.gcCount = gcCount
  v.gcSumSeconds = gcSum
  v.gcMaxSeconds = gcMax
  return v
}

function startSampler() {
  const samples = []
  let stopped = false
  const tick = async () => {
    if (stopped) return
    try {
      const res = await httpGet(`http://127.0.0.1:${MGMT_PORT}/actuator/prometheus`, 10000)
      const parsed = parseProm(res.body)
      samples.push({ t: Date.now(), ...parsed })
      if (samples.length === 1 || samples.length % 10 === 0) {
        console.log(`  [sampler] #${samples.length} status=${res.status} heap=${Math.round((parsed.heapBytes || 0) / 1024 / 1024)}MB`)
      }
    } catch (e) {
      // agent 已退出/未就绪：记录一次即可，避免刷屏
      if (samples.length === 0) console.warn(`  [sampler] 指标抓取失败：${e.message}`)
    }
    if (!stopped) setTimeout(tick, 200)
  }
  tick()
  return {
    add(parsed) {
      samples.push({ t: Date.now(), ...parsed })
    },
    stop() {
      stopped = true
      const summary = summarizeSamples(samples)
      console.log(`  [sampler] 采样结束：${summary.samples ?? 0} 个样本`)
      return summary
    },
  }
}

function summarizeSamples(samples) {
  const valid = samples.filter((s) => s.heapBytes != null)
  if (valid.length === 0) {
    return { samples: 0 }
  }
  const first = valid[0]
  const last = valid[valid.length - 1]
  return {
    samples: valid.length,
    peakHeapMb: round(Math.max(...valid.map((s) => s.heapBytes)) / 1024 / 1024),
    peakThreads: Math.max(...valid.map((s) => s.threads)),
    peakSseActive: Math.max(...valid.map((s) => s.sseActive)),
    peakProcessCpu: round(Math.max(...valid.map((s) => s.cpu)), 3),
    gcCountDelta: round((last.gcCount ?? 0) - (first.gcCount ?? 0), 0),
    gcPauseTotalMsDelta: round(((last.gcSumSeconds ?? 0) - (first.gcSumSeconds ?? 0)) * 1000),
    gcPauseMaxSeconds: round(last.gcMaxSeconds, 4),
    heapSeriesMb: valid.map((s) => round(s.heapBytes / 1024 / 1024, 1)),
  }
}

function countLines(file) {
  try {
    return fs.readFileSync(file, 'utf8').split('\n').length
  } catch {
    return 0
  }
}

function parseGcLog(file, fromLine) {
  let lines = []
  try {
    lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).slice(Math.max(0, fromLine - 1))
  } catch {
    return { pauseCount: 0, totalMs: 0, maxMs: 0, fullCount: 0, samples: [] }
  }
  const pauseLines = lines.filter((l) => /Pause/i.test(l) && /ms\s*$/.test(l))
  let total = 0
  let max = 0
  let full = 0
  for (const l of pauseLines) {
    const m = l.match(/(\d+(?:\.\d+)?)ms\s*$/)
    if (m) {
      const ms = Number(m[1])
      total += ms
      if (ms > max) max = ms
    }
    if (/Pause Full/i.test(l)) full += 1
  }
  return {
    pauseCount: pauseLines.length,
    totalMs: round(total, 3),
    maxMs: round(max, 3),
    fullCount: full,
    samples: pauseLines.slice(-3).map((l) => l.trim()),
  }
}

function javaVersion() {
  try {
    const r = spawnSync(JAVA, ['-version'], { encoding: 'utf8' })
    return `${r.stdout || ''}${r.stderr || ''}`.split(/\r?\n/).filter(Boolean).slice(0, 2).join(' | ')
  } catch {
    return 'unknown'
  }
}

async function runOne(configKey, level, repeat) {
  const tag = `${configKey}-${level}-r${repeat}`
  const gcLogRel = path.relative(REPO_ROOT, path.join(OUT, `gc-${tag}.log`)).replace(/\\/g, '/')
  const agentLog = path.join(OUT, `agent-${tag}.log`)
  const loadOut = path.join(OUT, `load-${tag}.json`)
  const warmupOut = path.join(OUT, `warmup-${tag}.json`)

  console.log(`\n== ${CONFIGS[configKey].label} @ ${level} 并发（第 ${repeat}/${REPEATS} 次）==`)
  const agent = startAgent(configKey, level, gcLogRel, agentLog)
  try {
    await waitFor(`http://127.0.0.1:${AGENT_PORT}/api/agent/health`, 60000, `${tag} agent`)
    console.log('  agent 就绪，预热 40 请求（并发 8）...')
    await runNode(path.join(PERF_DIR, 'loadtest.mjs'), [
      '--concurrency', '8', '--requests', '40', '--output', warmupOut,
      '--url', `http://127.0.0.1:${AGENT_PORT}/api/agent/chat/stream`, '--token', 'perf-token',
    ], agentLog)

    // 预热一次指标抓取：首次 scrape 在冷 JVM 上可能 3s+，不预热会错过整段压测窗口
    try {
      await httpGet(`http://127.0.0.1:${MGMT_PORT}/actuator/prometheus`, 15000)
    } catch {
      // 不阻断压测
    }

    const lineOffset = countLines(path.join(REPO_ROOT, gcLogRel))
    const sampler = startSampler()
    const requests = level >= 500 ? level : level * 3
    console.log(`  开始压测：并发 ${level}，总请求 ${requests} ...`)
    await runNode(path.join(PERF_DIR, 'loadtest.mjs'), [
      '--concurrency', String(level), '--requests', String(requests), '--output', loadOut,
      '--url', `http://127.0.0.1:${AGENT_PORT}/api/agent/chat/stream`, '--token', 'perf-token',
      '--timeout', '60000',
    ], agentLog)
    // 压测结束立即抓一次终态指标（防止负载期间 scrape 被 CPU 挤占而缺失样本）
    try {
      const res = await httpGet(`http://127.0.0.1:${MGMT_PORT}/actuator/prometheus`, 10000)
      sampler.add(parseProm(res.body))
    } catch (e) {
      console.warn(`  终态指标抓取失败：${e.message}`)
    }
    const metrics = sampler.stop()
    await sleep(600)

    const load = JSON.parse(fs.readFileSync(loadOut, 'utf8'))
    const gc = parseGcLog(path.join(REPO_ROOT, gcLogRel), lineOffset)
    return { config: configKey, label: CONFIGS[configKey].label, level, requests, load, metrics, gc }
  } finally {
    await stopChild(agent.child, agent.out, tag)
    await sleep(800)
  }
}

function median(values) {
  const list = values.filter((v) => v != null && Number.isFinite(v)).sort((a, b) => a - b)
  if (list.length === 0) return null
  const mid = Math.floor(list.length / 2)
  return list.length % 2 === 1 ? list[mid] : (list[mid - 1] + list[mid]) / 2
}

/** 多轮聚合：吞吐/延迟/堆峰值取中位数，GC 最大停顿与 SSE 峰值取最差 */
function aggregateRuns(configKey, level, runs) {
  const pick = (fn) => runs.map(fn)
  return {
    config: configKey,
    label: CONFIGS[configKey].label,
    level,
    repeats: runs.length,
    load: {
      total: round(median(pick((r) => r.load.total)), 0),
      ok: round(median(pick((r) => r.load.ok)), 1),
      rejected503: round(median(pick((r) => r.load.rejected503)), 1),
      successRate: round(median(pick((r) => r.load.successRate)), 4),
      wallMs: round(median(pick((r) => r.load.wallMs))),
      throughputRps: round(median(pick((r) => r.load.throughputRps)), 2),
      latencyMs: {
        mean: round(median(pick((r) => r.load.latencyMs.mean))),
        p50: round(median(pick((r) => r.load.latencyMs.p50))),
        p95: round(median(pick((r) => r.load.latencyMs.p95))),
        p99: round(median(pick((r) => r.load.latencyMs.p99))),
        max: round(median(pick((r) => r.load.latencyMs.max))),
      },
      ttftMs: {
        p50: round(median(pick((r) => r.load.ttftMs.p50))),
        p95: round(median(pick((r) => r.load.ttftMs.p95))),
        p99: round(median(pick((r) => r.load.ttftMs.p99))),
      },
    },
    metrics: {
      peakHeapMb: round(median(pick((r) => r.metrics.peakHeapMb))),
      peakHeapWorstMb: round(Math.max(...pick((r) => r.metrics.peakHeapMb ?? 0))),
      peakThreads: round(median(pick((r) => r.metrics.peakThreads)), 0),
      peakSseActive: Math.max(...pick((r) => r.metrics.peakSseActive ?? 0)),
      peakProcessCpu: round(median(pick((r) => r.metrics.peakProcessCpu)), 3),
      gcCountDelta: round(median(pick((r) => r.metrics.gcCountDelta)), 0),
      gcPauseTotalMsDelta: round(median(pick((r) => r.metrics.gcPauseTotalMsDelta))),
      gcPauseMaxSeconds: round(Math.max(...pick((r) => r.metrics.gcPauseMaxSeconds ?? 0)), 4),
    },
    gc: {
      pauseCount: round(median(pick((r) => r.gc.pauseCount)), 1),
      totalMs: round(median(pick((r) => r.gc.totalMs)), 3),
      maxMs: round(Math.max(...pick((r) => r.gc.maxMs ?? 0)), 3),
      fullCount: Math.max(...pick((r) => r.gc.fullCount ?? 0)),
    },
    runs,
  }
}

async function main() {
  if (!fs.existsSync(JAR)) {
    throw new Error(`jar 不存在：${JAR}（先执行 .\\mvnw.cmd -B -DskipTests package）`)
  }
  if (!fs.existsSync(JAVA)) {
    throw new Error(`java 不存在：${JAVA}（用 --java 指定 JDK 21）`)
  }
  fs.mkdirSync(OUT, { recursive: true })

  const stub = startStub(path.join(OUT, 'stub.log'))
  try {
    await waitFor(`http://127.0.0.1:${STUB_PORT}/health`, 15000, 'stub')

    const results = []
    for (const configKey of CONFIG_KEYS) {
      if (!CONFIGS[configKey]) throw new Error(`未知配置：${configKey}`)
      for (const level of LEVELS) {
        const runs = []
        for (let r = 1; r <= REPEATS; r++) {
          runs.push(await runOne(configKey, level, r))
        }
        results.push(aggregateRuns(configKey, level, runs))
      }
    }

    const summary = {
      environment: {
        os: `${os.platform()} ${os.release()}`,
        cpus: os.cpus().length,
        totalMemGb: round(os.totalmem() / 1024 / 1024 / 1024),
        java: javaVersion(),
        jar: path.relative(REPO_ROOT, JAR),
        jarSizeMb: round(fs.statSync(JAR).size / 1024 / 1024),
        stub: { chunks: 40, delayMs: 5, model: 'stub-model' },
        agentPool: { core: 8, max: 24, queue: 100, sseTimeoutMs: 300000 },
        repeats: REPEATS,
        startedAt: new Date().toISOString(),
      },
      results,
    }
    const summaryPath = path.join(OUT, 'summary.json')
    fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2), 'utf8')

    console.log('\n===== 汇总 =====')
    console.log('config     level  ok/req   503   p95ms  p99ms  rps   heapPeakMB  gcCount  gcPauseMs  gcMaxMs')
    for (const r of results) {
      console.log(
        `${r.config.padEnd(10)} ${String(r.level).padEnd(6)} ` +
          `${String(r.load.ok).padStart(5)}/${String(r.load.total).padEnd(4)} ` +
          `${String(r.load.rejected503).padStart(4)}  ` +
          `${String(r.load.latencyMs.p95).padStart(6)} ${String(r.load.latencyMs.p99).padStart(6)} ` +
          `${String(r.load.throughputRps).padStart(6)}  ` +
          `${String(r.metrics.peakHeapMb).padStart(9)}  ` +
          `${String(r.gc.pauseCount).padStart(7)}  ` +
          `${String(r.gc.totalMs).padStart(9)}  ${String(r.gc.maxMs).padStart(7)}`,
      )
    }
    console.log(`\n结果目录：${OUT}`)
  } finally {
    await stopChild(stub.child, stub.out, 'stub')
  }
}

main().catch((err) => {
  console.error(`\n压测失败：${err.message}`)
  process.exit(1)
})
