#!/usr/bin/env node
/**
 * agent SSE 压测驱动（P2-G）。
 *
 * 对 /api/agent/chat/stream 发起 N 路并发请求，逐请求记录：
 *   - HTTP 状态（200 正常 / 503 线程池拒绝 / 其它）
 *   - 端到端耗时（发起 → [DONE] 流结束）
 *   - TTFT（发起 → 第一个 data 事件）
 * 输出 JSON 结果文件，并打印一行摘要。
 *
 * 用法：
 *   node perf/loadtest.mjs --concurrency 100 --requests 300 --output perf/results/xxx.json
 * 可选项：
 *   --url        默认 http://127.0.0.1:18090/api/agent/chat/stream
 *   --token      默认 perf-token（对应 agent.internal-token）
 *   --timeout    单请求超时 ms（默认 60000）
 *   --message    请求文本（默认「只回复OK」）
 */
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback
}

const URL_RAW = arg('url', 'http://127.0.0.1:18090/api/agent/chat/stream')
const TOKEN = arg('token', 'perf-token')
const CONCURRENCY = Number(arg('concurrency', '100'))
const REQUESTS = Number(arg('requests', String(CONCURRENCY)))
const TIMEOUT_MS = Number(arg('timeout', '60000'))
const MESSAGE = arg('message', '只回复OK')
const OUTPUT = arg('output', null)

const target = new URL(URL_RAW)
const agent = new http.Agent({ keepAlive: false, maxSockets: Infinity })

function percentile(sorted, p) {
  if (sorted.length === 0) return null
  const idx = Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))
  return sorted[idx]
}

function round(v, digits = 1) {
  return v == null ? null : Number(v.toFixed(digits))
}

function one(index) {
  return new Promise((resolve) => {
    const started = process.hrtime.bigint()
    let firstEventAt = null
    let settled = false
    const elapsedMs = () => Number(process.hrtime.bigint() - started) / 1e6
    const done = (result) => {
      if (settled) return
      settled = true
      resolve({ index, ...result })
    }

    const req = http.request(
      {
        hostname: target.hostname,
        port: target.port,
        path: target.pathname,
        method: 'POST',
        agent,
        headers: {
          'Content-Type': 'application/json',
          'X-Agent-Token': TOKEN,
        },
      },
      (res) => {
        res.setEncoding('utf8')
        res.on('data', (chunk) => {
          if (firstEventAt == null && chunk.includes('data:')) {
            firstEventAt = elapsedMs()
          }
        })
        res.on('end', () => {
          const latencyMs = elapsedMs()
          done({
            status: res.statusCode,
            latencyMs,
            ttftMs: firstEventAt,
            ok: res.statusCode === 200,
          })
        })
        res.on('error', (err) => {
          done({ status: 0, latencyMs: elapsedMs(), ttftMs: firstEventAt, error: err.code || err.message })
        })
      },
    )
    req.setTimeout(TIMEOUT_MS, () => req.destroy(new Error('timeout')))
    req.on('error', (err) => {
      done({ status: 0, latencyMs: elapsedMs(), ttftMs: firstEventAt, error: err.code || err.message })
    })
    req.end(
      JSON.stringify({
        userId: `perf-user-${index % 50}`,
        conversationId: 0,
        message: MESSAGE,
      }),
    )
  })
}

async function main() {
  const wallStart = process.hrtime.bigint()
  const results = new Array(REQUESTS)
  let next = 0
  const workers = Array.from({ length: Math.min(CONCURRENCY, REQUESTS) }, async () => {
    for (;;) {
      const i = next
      next += 1
      if (i >= REQUESTS) return
      results[i] = await one(i)
    }
  })
  await Promise.all(workers)
  const wallMs = Number(process.hrtime.bigint() - wallStart) / 1e6

  const ok = results.filter((r) => r.status === 200)
  const rejected = results.filter((r) => r.status === 503)
  const other = results.filter((r) => r.status !== 200 && r.status !== 503)
  const errors = results.filter((r) => r.status === 0)
  const latencies = ok.map((r) => r.latencyMs).sort((a, b) => a - b)
  const ttfts = ok.map((r) => r.ttftMs).filter((v) => v != null).sort((a, b) => a - b)
  const mean = latencies.length ? latencies.reduce((a, b) => a + b, 0) / latencies.length : null

  const summary = {
    params: { url: URL_RAW, concurrency: CONCURRENCY, requests: REQUESTS, timeoutMs: TIMEOUT_MS },
    total: results.length,
    ok: ok.length,
    rejected503: rejected.length,
    otherStatus: other.length,
    networkErrors: errors.length,
    successRate: round(ok.length / results.length, 4),
    wallMs: round(wallMs),
    throughputRps: round(ok.length / (wallMs / 1000), 2),
    latencyMs: {
      mean: round(mean),
      p50: round(percentile(latencies, 50)),
      p95: round(percentile(latencies, 95)),
      p99: round(percentile(latencies, 99)),
      max: round(latencies.length ? latencies[latencies.length - 1] : null),
    },
    ttftMs: {
      p50: round(percentile(ttfts, 50)),
      p95: round(percentile(ttfts, 95)),
      p99: round(percentile(ttfts, 99)),
    },
    errorSamples: [...errors, ...other].slice(0, 5).map((r) => ({
      status: r.status,
      error: r.error,
      latencyMs: round(r.latencyMs),
    })),
  }

  if (OUTPUT) {
    fs.mkdirSync(path.dirname(path.resolve(OUTPUT)), { recursive: true })
    fs.writeFileSync(path.resolve(OUTPUT), JSON.stringify(summary, null, 2), 'utf8')
  }
  console.log(
    `[loadtest] concurrency=${CONCURRENCY} requests=${REQUESTS} ok=${summary.ok} ` +
      `503=${summary.rejected503} rps=${summary.throughputRps} ` +
      `p95=${summary.latencyMs.p95}ms p99=${summary.latencyMs.p99}ms`,
  )
  console.log(JSON.stringify(summary))
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})
