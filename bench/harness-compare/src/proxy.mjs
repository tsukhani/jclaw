// Timing proxy. Each harness reaches Ollama Cloud, for the model and for web search, through its own local port.
// For every request it logs one JSON line of timings, sizes and token counts. It never logs a header, a query
// string or any message text: from a request body it keeps only the model, the thinking settings and the offered
// tool names, and from a response only counts and the names of the tools the model called. So neither the key
// nor a prompt nor an answer reaches the log. A status port reports in-flight requests per harness, so the driver
// can wait out a harness's background calls before the next turn.
import http from 'node:http'
import https from 'node:https'
import fs from 'node:fs'
import path from 'node:path'
import { config, HARNESSES, proxyPort, now, isMain } from './config.mjs'

const UPSTREAM = new URL(config.upstream)
const LOG_DIR = process.env.LOG_DIR ?? path.join(config.runDir, 'proxy')
const HOP = new Set(['connection', 'keep-alive', 'proxy-connection', 'transfer-encoding', 'upgrade', 'te', 'trailer', 'host'])
const SCAN_CAP = 8 * 1024 * 1024
// Hermes sends reasoning_effort only once a probe of an ollama.com host says the model reasons, so through a
// loopback proxy its main requests carry none and the model thinks. The proxy adds the value Hermes's own
// agent.reasoning_effort asks for, as Hermes would send it to ollama.com; its title calls already carry it.
const INJECT_REASONING = { hermes: process.env.HERMES_REASONING_EFFORT ?? 'none' }
// OpenClaw tries /api/experimental/web_search first on a host that is not ollama.com; ollama.com is asked for
// /api/web_search, the path OpenClaw uses against it directly.
const REWRITE = { '/api/experimental/web_search': '/api/web_search' }
const client = UPSTREAM.protocol === 'https:' ? https : http
const agent = new client.Agent({ keepAlive: true })
let seq = 0
const state = Object.fromEntries(HARNESSES.map(h => [h, { inflight: 0, lastActivity: 0 }]))

const COUNTS = {
  promptEval: /"prompt_eval_count"\s*:\s*(\d+)/g,
  eval: /"eval_count"\s*:\s*(\d+)/g,
  promptTokens: /"prompt_tokens"\s*:\s*(\d+)/g,
  completionTokens: /"completion_tokens"\s*:\s*(\d+)/g,
  cachedTokens: /"cached_tokens"\s*:\s*(\d+)/g,
  reasoningTokens: /"reasoning_tokens"\s*:\s*(\d+)/g,
}
export function counts(tail) {
  const out = {}
  for (const [name, re] of Object.entries(COUNTS)) {
    let last = null
    for (const m of tail.matchAll(re)) last = Number(m[1])
    if (last !== null) out[name] = last
  }
  return out
}

// A streamed OpenAI tool call names its function once, in its first delta; Ollama's native stream names each call
// once in its message. Either way one name is one call.
const CALLED = /"function"\s*:\s*\{[^{}]*?"name"\s*:\s*"([\w.:-]+)"/g
export function calledTools(text) {
  return [...text.matchAll(CALLED)].map(m => m[1])
}

// Non-empty reasoning in a response: OpenAI-style `reasoning` / `reasoning_content` deltas or Ollama's native
// `thinking`. A quote inside answer text is escaped, so the lookbehind keeps these keys from matching it.
const REASONING = /(?<!\\)"(?:reasoning|reasoning_content|thinking)"\s*:\s*"(?!")/g
export function reasoningChunks(text) {
  return (text.match(REASONING) ?? []).length
}

export function requestShape(buf) {
  if (buf.length === 0) return {}
  let body
  try { body = JSON.parse(buf.toString('utf8')) } catch { return { bodyJson: false } }
  if (!body || typeof body !== 'object') return {}
  const shape = {}
  if (typeof body.model === 'string') shape.model = body.model
  if ('stream' in body) shape.stream = body.stream
  if (Array.isArray(body.messages)) {
    shape.messages = body.messages.length
    shape.roles = body.messages.map(m => (m && typeof m.role === 'string' ? m.role[0] : '?')).join('')
  }
  if (Array.isArray(body.tools)) shape.tools = body.tools.map(t => t?.function?.name ?? t?.name ?? '?')
  for (const k of ['think', 'reasoning_effort', 'reasoning', 'thinking']) if (k in body) shape[k] = body[k]
  for (const k of ['max_tokens', 'max_completion_tokens', 'temperature']) if (k in body) shape[k] = body[k]
  if (typeof body.query === 'string') shape.queryChars = body.query.length
  if ('max_results' in body) shape.max_results = body.max_results
  return shape
}

export function start() {
  fs.mkdirSync(LOG_DIR, { recursive: true })
  for (const harness of HARNESSES) {
    const log = fs.createWriteStream(path.join(LOG_DIR, `proxy-${harness}.jsonl`), { flags: 'a' })
    const st = state[harness]
    http.createServer((req, res) => {
      st.inflight++
      st.lastActivity = now()
      const row = { id: ++seq, harness, method: req.method, path: new URL(req.url, 'http://x').pathname, tRecv: now() }
      let done = false
      const finish = () => {
        if (done) return
        done = true
        st.inflight--
        st.lastActivity = now()
        log.write(JSON.stringify(row) + '\n')
      }
      let upPath = req.url
      if (REWRITE[row.path]) { row.rewrittenFrom = row.path; row.path = REWRITE[row.path]; upPath = row.path }
      const inject = INJECT_REASONING[harness] && req.method === 'POST' && row.path.endsWith('/chat/completions')
      const headers = {}
      for (const [k, v] of Object.entries(req.headers)) if (!HOP.has(k)) headers[k] = v
      headers['accept-encoding'] = 'identity'
      const reqChunks = []
      let reqBytes = 0
      const up = client.request({
        protocol: UPSTREAM.protocol, hostname: UPSTREAM.hostname, port: UPSTREAM.port || undefined,
        method: req.method, path: upPath, headers: { ...headers, host: UPSTREAM.host }, agent,
      }, (upRes) => {
        row.status = upRes.statusCode
        const out = {}
        for (const [k, v] of Object.entries(upRes.headers)) if (!HOP.has(k)) out[k] = v
        res.writeHead(upRes.statusCode, out)
        let respBytes = 0
        let tail = ''
        let scan = ''
        upRes.on('data', (chunk) => {
          if (row.tFirstByte === undefined) row.tFirstByte = now()
          respBytes += chunk.length
          const s = chunk.toString('utf8')
          tail = (tail + s).slice(-65536)
          if (scan.length < SCAN_CAP) scan += s
          res.write(chunk)
        })
        upRes.on('end', () => {
          row.tLastByte = now()
          res.end()
          Object.assign(row, { reqBytes, respBytes }, counts(tail))
          if (!/web_search|web_fetch/.test(row.path)) {
            const called = calledTools(scan)
            if (called.length) row.called = called
            const r = reasoningChunks(scan)
            if (r) row.reasoningChunks = r
          }
          scan = ''
          finish()
        })
        upRes.on('error', (e) => { row.error = e.code ?? 'upstream stream error'; finish() })
      })
      up.on('error', (e) => {
        row.error = e.code ?? 'upstream error'
        if (!res.headersSent) res.writeHead(502)
        res.end()
        finish()
      })
      req.on('data', (chunk) => { reqBytes += chunk.length; reqChunks.push(chunk); if (!inject) up.write(chunk) })
      req.on('end', () => {
        row.tReqEnd = now()
        let buf = Buffer.concat(reqChunks)
        if (inject) {
          try {
            const body = JSON.parse(buf.toString('utf8'))
            if (body && typeof body === 'object' && !('reasoning_effort' in body)) {
              body.reasoning_effort = INJECT_REASONING[harness]
              row.injected = 'reasoning_effort'
              buf = Buffer.from(JSON.stringify(body))
            }
          } catch { /* not JSON: forwarded as it came */ }
          up.setHeader('content-length', buf.length)
          up.end(buf)
        } else {
          up.end()
        }
        Object.assign(row, requestShape(buf))
      })
      req.on('aborted', () => { row.clientAborted = true })
    }).listen(proxyPort(harness), '127.0.0.1', () => process.stdout.write(`${harness} on 127.0.0.1:${proxyPort(harness)} -> ${UPSTREAM.origin}\n`))
  }
  http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ now: now(), ...state }))
  }).listen(config.portBase, '127.0.0.1', () => process.stdout.write(`status on 127.0.0.1:${config.portBase}\n`))
}

if (isMain(import.meta.url)) start()
