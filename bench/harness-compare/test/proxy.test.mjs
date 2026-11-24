// The proxy against a local stub upstream: what it logs, what it changes on the way, and what it costs.
// run.sh runs this before every benchmark, on ports of its own, so it never meets a live proxy.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'

const BASE = 18290
const STUB = 18299
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-proxy-'))
const seen = []
let stub
let proxy

const SSE_TOOLS = [
  'data: {"choices":[{"delta":{"content":"RESP-MARKER and \\"reasoning\\": \\"quoted\\""}}]}',
  'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_search","arguments":""}}]}}]}',
  'data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"web_search","arguments":"{}"}}]}}]}',
  'data: {"usage":{"prompt_tokens":1234,"completion_tokens":56,"prompt_tokens_details":{"cached_tokens":1000}}}',
  'data: [DONE]',
].join('\n\n') + '\n\n'
const SSE_REASONING = 'data: {"choices":[{"delta":{"reasoning":"thinking it over"}}]}\n\ndata: {"choices":[{"delta":{"content":"ok"}}]}\n\ndata: [DONE]\n\n'

before(async () => {
  stub = http.createServer((req, res) => {
    let body = ''
    req.on('data', d => { body += d })
    req.on('end', () => {
      seen.push({ path: req.url, length: req.headers['content-length'], body })
      const reasoning = body.includes('WANT-REASONING')
      res.writeHead(200, { 'content-type': 'text/event-stream' })
      setTimeout(() => res.end(reasoning ? SSE_REASONING : SSE_TOOLS), 50)
    })
  }).listen(STUB, '127.0.0.1')
  proxy = spawn(process.execPath, [new URL('../src/proxy.mjs', import.meta.url).pathname], {
    env: { ...process.env, UPSTREAM: `http://127.0.0.1:${STUB}`, LOG_DIR: dir, PORT_BASE: String(BASE) },
    stdio: ['ignore', 'pipe', 'inherit'],
  })
  await new Promise(resolve => proxy.stdout.on('data', d => { if (String(d).includes('status')) resolve() }))
})
after(() => { proxy.kill(); stub.close() })

function post(port, p, body) {
  return new Promise((resolve, reject) => {
    const t0 = performance.now()
    const req = http.request({ host: '127.0.0.1', port, method: 'POST', path: p, headers: { authorization: 'Bearer AUTH-MARKER-0000', 'content-type': 'application/json' } }, res => {
      let first
      res.on('data', () => { first ??= performance.now() - t0 })
      res.on('end', () => resolve({ first, total: performance.now() - t0 }))
    })
    req.on('error', reject)
    req.end(body)
  })
}
const rows = h => fs.readFileSync(path.join(dir, `proxy-${h}.jsonl`), 'utf8').split('\n').filter(Boolean).map(JSON.parse)
const settle = () => new Promise(r => setTimeout(r, 100))
const chat = (extra = {}, prompt = 'BODY-MARKER') => JSON.stringify({
  model: 'deepseek-v4.1-flash', stream: true, messages: [{ role: 'system', content: 'BODY-MARKER' }, { role: 'user', content: prompt }],
  tools: [{ type: 'function', function: { name: 'web_search', description: 'TOOLDESC-MARKER' } }], ...extra,
})

test('logs the request shape and counts but no header, query, prompt, answer or tool text', async () => {
  await post(BASE + 1, '/v1/chat/completions?k=QUERY-MARKER', chat({ reasoning_effort: 'none' }))
  await settle()
  const log = fs.readFileSync(path.join(dir, 'proxy-jclaw.jsonl'), 'utf8')
  for (const marker of ['AUTH-MARKER', 'Bearer', 'QUERY-MARKER', 'BODY-MARKER', 'RESP-MARKER', 'TOOLDESC-MARKER']) assert.equal(log.includes(marker), false, marker)
  const r = rows('jclaw').at(-1)
  assert.deepEqual(
    { path: r.path, model: r.model, roles: r.roles, tools: r.tools, reasoning_effort: r.reasoning_effort, called: r.called, promptTokens: r.promptTokens, completionTokens: r.completionTokens, cachedTokens: r.cachedTokens, reasoningChunks: r.reasoningChunks },
    { path: '/v1/chat/completions', model: 'deepseek-v4.1-flash', roles: 'su', tools: ['web_search'], reasoning_effort: 'none', called: ['web_search', 'web_search'], promptTokens: 1234, completionTokens: 56, cachedTokens: 1000, reasoningChunks: undefined },
  )
})

test('counts streamed reasoning, but not the word inside an answer', async () => {
  await post(BASE + 1, '/v1/chat/completions', chat({}, 'WANT-REASONING'))
  await settle()
  assert.equal(rows('jclaw').at(-1).reasoningChunks, 1)
})

test("adds Hermes's reasoning_effort only where Hermes sent none, and leaves other harnesses' bodies alone", async () => {
  seen.length = 0
  await post(BASE + 3, '/v1/chat/completions', chat())
  await post(BASE + 3, '/v1/chat/completions', chat({ reasoning_effort: 'low' }))
  await post(BASE + 1, '/v1/chat/completions', chat())
  const efforts = seen.map(s => JSON.parse(s.body).reasoning_effort ?? '(absent)')
  assert.deepEqual(efforts, ['none', 'low', '(absent)'])
  assert.equal(Number(seen[0].length), Buffer.byteLength(seen[0].body))
  await settle()
  assert.deepEqual(rows('hermes').slice(-2).map(r => r.injected ?? null), ['reasoning_effort', null])
})

test("sends OpenClaw's experimental search path to /api/web_search", async () => {
  seen.length = 0
  await post(BASE + 2, '/api/experimental/web_search', JSON.stringify({ query: 'q', max_results: 5 }))
  await settle()
  assert.equal(seen[0].path, '/api/web_search')
  const r = rows('openclaw').at(-1)
  assert.deepEqual({ path: r.path, rewrittenFrom: r.rewrittenFrom, queryChars: r.queryChars }, { path: '/api/web_search', rewrittenFrom: '/api/experimental/web_search', queryChars: 1 })
})

test('reports a request in flight on the status port until it ends', async () => {
  const status = () => fetch(`http://127.0.0.1:${BASE}/`).then(r => r.json())
  const pending = post(BASE + 1, '/v1/chat/completions', chat())
  await new Promise(r => setTimeout(r, 20))
  assert.equal((await status()).jclaw.inflight, 1)
  await pending
  assert.equal((await status()).jclaw.inflight, 0)
})

test('adds under 3 ms to the first byte and the whole response', async () => {
  const med = a => a.slice().sort((x, y) => x - y)[Math.floor(a.length / 2)]
  const direct = []
  const proxied = []
  for (let i = 0; i < 30; i++) { direct.push(await post(STUB, '/v1/chat/completions', chat())); proxied.push(await post(BASE + 1, '/v1/chat/completions', chat())) }
  assert.ok(med(proxied.map(r => r.first)) - med(direct.map(r => r.first)) < 3)
  assert.ok(med(proxied.map(r => r.total)) - med(direct.map(r => r.total)) < 3)
})
