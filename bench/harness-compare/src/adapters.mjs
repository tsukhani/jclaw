// One adapter per harness. send() returns { tSend, tFirstAny, tFirstText, tLast, text, ... } on the proxy's clock:
// tFirstAny is the first streamed frame of any kind, reasoning included, and tFirstText the first frame of answer text.
import fs from 'node:fs'
import { config, secret, now } from './config.mjs'

export async function readSse(res, onEvent) {
  const decoder = new TextDecoder()
  let buf = ''
  for await (const chunk of res.body) {
    buf += decoder.decode(chunk, { stream: true })
    let i
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, i)
      buf = buf.slice(i + 2)
      let event = 'message'
      const data = []
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
      }
      if (data.length && onEvent(event, data.join('\n')) === false) return
    }
  }
}

export const jclawBase = () => `http://127.0.0.1:${config.jclawPort}`

// The session cookie expires after about an hour, which a full run can outlast, so a 401 signs in once more.
export async function jclawLogin() {
  const base = jclawBase()
  const password = fs.readFileSync(secret('jclaw-admin-password'), 'utf8').trim()
  const res = await fetch(`${base}/api/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json', origin: base },
    body: JSON.stringify({ username: 'admin', password }),
  })
  if (!res.ok) throw new Error(`jclaw: login -> ${res.status}`)
  const cookie = res.headers.getSetCookie().map(c => c.split(';')[0]).join('; ')
  fs.writeFileSync(secret('jclaw-cookie'), cookie, { mode: 0o600 })
  return cookie
}

const jclaw = {
  async init() {
    this.base = jclawBase()
    this.cookie = fs.existsSync(secret('jclaw-cookie')) ? fs.readFileSync(secret('jclaw-cookie'), 'utf8') : await jclawLogin()
    const agents = await this.api('/api/agents')
    this.agentId = (Array.isArray(agents) ? agents : agents.agents).find(a => a.name === 'bench')?.id
    if (!this.agentId) throw new Error('jclaw: no bench agent')
  },
  async api(path, init = {}) {
    for (let attempt = 0; ; attempt++) {
      const res = await fetch(this.base + path, { ...init, headers: { cookie: this.cookie, origin: this.base, 'content-type': 'application/json', ...init.headers } })
      if (res.status === 401 && attempt === 0) { this.cookie = await jclawLogin(); continue }
      if (!res.ok) throw new Error(`jclaw: ${path} -> ${res.status}`)
      return init.raw ? res : res.json()
    }
  },
  async newConversation() { return { id: null } },
  // A JVM's process memory is mostly the heap it has committed, which can stay well above the heap in use, so that is
  // read beside it.
  async runtimeMemory() {
    const s = await this.api('/api/metrics/jvm')
    const kb = b => (b >= 0 ? Math.round(b / 1024) : null)
    return { kind: 'jvm', heapUsedKb: kb(s.heapUsed), heapCommittedKb: kb(s.heapCommitted), heapMaxKb: kb(s.heapMax), nonHeapCommittedKb: kb(s.nonHeapCommitted) }
  },
  async send(conv, message) {
    const tSend = now()
    const res = await this.api('/api/chat/stream', {
      method: 'POST', raw: true, headers: { accept: 'text/event-stream' },
      body: JSON.stringify({ agentId: this.agentId, message, ...(conv.id ? { conversationId: conv.id } : {}) }),
    })
    const r = { tSend, text: '', frames: {} }
    let completeText = null
    await readSse(res, (_event, data) => {
      let ev
      try { ev = JSON.parse(data) } catch { return }
      const t = now()
      r.frames[ev.type] = (r.frames[ev.type] ?? 0) + 1
      if (ev.type === 'init' && ev.conversationId) conv.id = ev.conversationId
      if (ev.type === 'reasoning' || ev.type === 'token') r.tFirstAny ??= t
      if (ev.type === 'token') { r.tFirstText ??= t; r.text += ev.content ?? '' }
      if (ev.type === 'complete') { r.tLast = t; completeText = typeof ev.content === 'string' ? ev.content : null; return false }
      if (ev.type === 'error' || ev.type === 'stopped') { r.tLast = t; r.error = `${ev.type}: ${String(ev.content ?? ev.message ?? '').slice(0, 200)}`; return false }
    })
    r.tLast ??= now()
    if (!r.text && completeText) r.text = completeText
    r.conversationId = conv.id
    return r
  },
}

// OpenAI-style Chat Completions over SSE, as OpenClaw's and Hermes's gateways serve it. Both keep the conversation
// server-side under a session key, so each request carries only the newest message.
function chatCompletionsAdapter({ name, url, token, model, sessionHeader }) {
  return {
    async init() { this.token = token() },
    async newConversation(tag) { return { key: `bench-${tag}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}` } },
    async send(conv, message) {
      const tSend = now()
      const res = await fetch(url(), {
        method: 'POST',
        headers: { authorization: `Bearer ${this.token}`, 'content-type': 'application/json', accept: 'text/event-stream', [sessionHeader]: conv.key },
        body: JSON.stringify({ model, stream: true, messages: [{ role: 'user', content: message }] }),
      })
      if (!res.ok) throw new Error(`${name}: ${res.status} ${(await res.text()).slice(0, 200)}`)
      const r = { tSend, text: '', frames: {} }
      await readSse(res, (event, data) => {
        const t = now()
        if (data === '[DONE]') { r.tLast = t; return false }
        let ev
        try { ev = JSON.parse(data) } catch { return }
        const key = event !== 'message' ? event : ev.object ?? 'data'
        r.frames[key] = (r.frames[key] ?? 0) + 1
        if (ev.error) { r.error = String(ev.error.message ?? ev.error).slice(0, 200); r.tLast = t; return false }
        const d = ev.choices?.[0]?.delta
        if (!d) return
        if (d.reasoning_content || d.reasoning) r.tFirstAny ??= t
        if (typeof d.content === 'string' && d.content.length) { r.tFirstAny ??= t; r.tFirstText ??= t; r.text += d.content }
      })
      r.tLast ??= now()
      r.sessionKey = conv.key
      return r
    },
  }
}

const readSecret = (name) => () => fs.readFileSync(secret(name), 'utf8').trim()

export const ADAPTERS = {
  jclaw,
  openclaw: chatCompletionsAdapter({
    name: 'openclaw', url: () => `http://127.0.0.1:${config.openclawPort}/v1/chat/completions`, model: 'openclaw',
    sessionHeader: 'x-openclaw-session-key', token: readSecret('openclaw-token'),
  }),
  hermes: chatCompletionsAdapter({
    name: 'hermes', url: () => `http://127.0.0.1:${config.hermesPort}/v1/chat/completions`, model: 'hermes-agent',
    sessionHeader: 'x-hermes-session-id', token: readSecret('hermes-api-key'),
  }),
}
