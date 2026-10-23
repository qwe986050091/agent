#!/usr/bin/env node
/**
 * OpenAI 兼容的本地 stub（P2-G 压测专用）。
 *
 * 目的：把上游 LLM 的延迟/波动隔离掉，让压测只测 agent JVM 自身的
 * 线程池、SSE 编解码、内存分配与 GC 行为；同时零成本、可重复。
 *
 * 用法：node perf/stub-openai.mjs
 * 环境变量：
 *   STUB_PORT             监听端口（默认 19090）
 *   STUB_CHUNKS           流式返回的 token 块数（默认 40）
 *   STUB_CHUNK_DELAY_MS   每个块的间隔毫秒（默认 5 → 单请求约 200ms）
 */
import http from 'node:http'

const PORT = Number(process.env.STUB_PORT || 19090)
const CHUNKS = Number(process.env.STUB_CHUNKS || 40)
const DELAY_MS = Number(process.env.STUB_CHUNK_DELAY_MS || 5)
const MODEL = process.env.STUB_MODEL || 'stub-model'

function json(res, status, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(body),
  })
  res.end(body)
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    return json(res, 200, { ok: true, chunks: CHUNKS, delayMs: DELAY_MS })
  }
  if (req.method !== 'POST' || !/\/chat\/completions(\?.*)?$/.test(req.url)) {
    return json(res, 404, { error: { message: 'not found: ' + req.url } })
  }

  let raw = ''
  req.setEncoding('utf8')
  req.on('data', (chunk) => {
    raw += chunk
    // 防御：异常大请求直接断掉（压测不会触发，正常请求 <10KB）
    if (raw.length > 1_000_000) req.destroy()
  })
  req.on('end', () => {
    let body = {}
    try {
      body = JSON.parse(raw || '{}')
    } catch {
      return json(res, 400, { error: { message: 'bad json' } })
    }
    const created = Math.floor(Date.now() / 1000)
    const id = 'stub-' + Math.random().toString(36).slice(2, 10)

    if (body.stream !== true) {
      return json(res, 200, {
        id,
        object: 'chat.completion',
        created,
        model: MODEL,
        choices: [{ index: 0, message: { role: 'assistant', content: 'OK' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 12, completion_tokens: CHUNKS, total_tokens: 12 + CHUNKS },
      })
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    })
    const base = { id, object: 'chat.completion.chunk', created, model: MODEL }
    const send = (payload) => res.write(`data: ${JSON.stringify(payload)}\n\n`)

    send({ ...base, choices: [{ index: 0, delta: { role: 'assistant' }, finish_reason: null }] })
    let sent = 0
    const timer = setInterval(() => {
      if (sent >= CHUNKS) {
        clearInterval(timer)
        send({
          ...base,
          choices: [{ index: 0, delta: {}, finish_reason: 'stop' }],
          usage: { prompt_tokens: 12, completion_tokens: CHUNKS, total_tokens: 12 + CHUNKS },
        })
        res.write('data: [DONE]\n\n')
        res.end()
        return
      }
      sent += 1
      send({ ...base, choices: [{ index: 0, delta: { content: 'x' }, finish_reason: null }] })
    }, DELAY_MS)

    res.on('close', () => clearInterval(timer))
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(
    `[stub-openai] listening on http://127.0.0.1:${PORT} (chunks=${CHUNKS}, delay=${DELAY_MS}ms)`,
  )
})
