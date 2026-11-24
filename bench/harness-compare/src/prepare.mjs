// Setup steps run.sh calls between starting the harnesses and the run.
//   render <template> <out>        fill a harness template's {{PLACEHOLDERS}} from the config
//   wait <url> [seconds] [secret]  poll until the URL answers 200 (with a bearer token read from a secret file)
//   jclaw                          first-run password, sign-in, provider and search through the proxy, the bench agent
//   openclaw-seed <state-dir>      one untimed turn to create the workspace, then set its onboarding file aside
// The Ollama key comes from BENCH_OLLAMA_KEY and is never printed.
import fs from 'node:fs'
import path from 'node:path'
import { config, proxyPort, secret } from './config.mjs'
import { ADAPTERS, jclawBase, jclawLogin } from './adapters.mjs'

const [cmd, ...args] = process.argv.slice(2)

function render(template, out) {
  const vars = {
    MODEL: config.model, CONTEXT_WINDOW: config.contextWindow, MAX_TOKENS: config.maxTokens,
    OPENCLAW_PORT: config.openclawPort,
    PROXY_URL: `http://127.0.0.1:${proxyPort(path.basename(template).startsWith('hermes') ? 'hermes' : 'openclaw')}`,
  }
  const text = fs.readFileSync(template, 'utf8').replace(/\{\{(\w+)\}\}/g, (_, k) => {
    if (!(k in vars)) throw new Error(`${template}: no value for {{${k}}}`)
    return String(vars[k])
  })
  fs.mkdirSync(path.dirname(out), { recursive: true })
  fs.writeFileSync(out, text)
}

async function wait(url, seconds = 300, tokenSecret) {
  const headers = tokenSecret ? { authorization: `Bearer ${fs.readFileSync(secret(tokenSecret), 'utf8').trim()}` } : {}
  const deadline = Date.now() + Number(seconds) * 1000
  let last = ''
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { headers, signal: AbortSignal.timeout(5000) })
      if (res.ok) return
      last = `HTTP ${res.status}`
    } catch (e) { last = e.cause?.code ?? e.message }
    await new Promise(r => setTimeout(r, 1000))
  }
  throw new Error(`${url} did not answer 200 in ${seconds} s (last: ${last})`)
}

async function jclaw() {
  const base = jclawBase()
  const key = process.env.BENCH_OLLAMA_KEY
  if (!key) throw new Error('BENCH_OLLAMA_KEY is not set')
  const status = await (await fetch(`${base}/api/auth/status`)).json()
  if (!status.passwordSet) {
    const password = fs.readFileSync(secret('jclaw-admin-password'), 'utf8').trim()
    const res = await fetch(`${base}/api/auth/setup`, { method: 'POST', headers: { 'content-type': 'application/json', origin: base }, body: JSON.stringify({ password }) })
    if (!res.ok) throw new Error(`jclaw: first-run setup -> ${res.status}`)
  }
  const cookie = await jclawLogin()
  const api = async (method, p, body) => {
    const res = await fetch(base + p, { method, headers: { cookie, origin: base, 'content-type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    const text = await res.text()
    if (!res.ok) throw new Error(`jclaw: ${method} ${p} -> ${res.status} ${text.slice(0, 200)}`)
    return text ? JSON.parse(text) : null
  }
  const proxy = `http://127.0.0.1:${proxyPort('jclaw')}`
  const keys = {
    'provider.ollama-cloud.baseUrl': `${proxy}/v1`, 'provider.ollama-cloud.apiKey': key,
    'search.ollama.apiKey': key, 'search.ollama.enabled': 'true', 'search.ollama.baseUrl': `${proxy}/api/web_search`, 'search.ollama.priority': '0',
  }
  for (const [k, value] of Object.entries(keys)) await api('POST', '/api/config', { key: k, value })
  const list = await api('GET', '/api/agents')
  let bench = (Array.isArray(list) ? list : list.agents).find(a => a.name === 'bench')
  // No thinkingMode and no fallback pair: JClaw then sends thinking off and never fails over to another model.
  bench ??= await api('POST', '/api/agents', { name: 'bench', modelProvider: 'ollama-cloud', modelId: config.model, description: 'Harness benchmark agent' })
  // Memory auto-capture makes one extra model call after every turn (the operator's call, JF-599, to leave it out).
  await api('PUT', `/api/agents/${bench.id}`, { name: 'bench', modelProvider: 'ollama-cloud', modelId: config.model, memoryAutocaptureEnabled: false })
  const tools = await api('GET', `/api/agents/${bench.id}/tools`)
  for (const t of Array.isArray(tools) ? tools : tools.tools) {
    const want = t.name === config.searchTool
    if (Boolean(t.enabled) !== want) await api('PUT', `/api/agents/${bench.id}/tools/${encodeURIComponent(t.name)}`, { enabled: want })
  }
  const after = await api('GET', `/api/agents/${bench.id}/tools`)
  const on = (Array.isArray(after) ? after : after.tools).filter(t => t.enabled).map(t => t.name)
  if (on.length !== 1 || on[0] !== config.searchTool) throw new Error(`jclaw: bench agent tools on: ${on.join(', ') || 'none'}`)
  const view = await api('GET', `/api/agents/${bench.id}`)
  if (view.memoryAutocaptureEnabled !== false) throw new Error(`jclaw: memory auto-capture reads back ${view.memoryAutocaptureEnabled}`)
  if (view.thinkingMode != null || view.fallbackProvider != null) throw new Error('jclaw: the bench agent has a thinking mode or a fallback')
  console.log(`jclaw: bench agent ${view.id} on ${view.modelProvider}/${view.modelId}, thinking ${view.thinkingMode ?? 'default (off)'}, fallback none, memory auto-capture off, tools: ${on.join(', ')}`)
}

// A new OpenClaw workspace opens with an onboarding conversation driven by BOOTSTRAP.md, which would answer the first
// question with a request for a name. Finishing onboarding removes that file, so the seed turn's workspace does too.
async function openclawSeed(stateDir) {
  const a = ADAPTERS.openclaw
  await a.init()
  const r = await a.send(await a.newConversation('seed'), 'Hello')
  if (r.error) throw new Error(`openclaw seed turn: ${r.error}`)
  const file = path.join(stateDir, 'workspace', 'BOOTSTRAP.md')
  if (fs.existsSync(file)) {
    fs.mkdirSync(path.join(config.runDir, 'set-aside'), { recursive: true })
    fs.renameSync(file, path.join(config.runDir, 'set-aside', 'openclaw-BOOTSTRAP.md'))
    console.log('openclaw: workspace created; BOOTSTRAP.md set aside')
  } else {
    console.log('openclaw: workspace created; no BOOTSTRAP.md to set aside')
  }
}

const commands = { render: () => render(...args), wait: () => wait(...args), jclaw, 'openclaw-seed': () => openclawSeed(...args) }
if (!commands[cmd]) { console.error(`unknown command: ${cmd ?? '(none)'}`); process.exit(2) }
try {
  await commands[cmd]()
} catch (e) {
  console.error(`${cmd}: ${e.message}`)
  process.exit(1)
}
