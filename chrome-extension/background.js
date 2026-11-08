const GROUP_TITLE = 'JClaw'
const GROUP_COLOR = 'orange'
const KEEPALIVE_MS = 20_000
const MAX_TEXT = 20_000
const MAX_ELEMENTS = 200
const BLOCKED = /^(chrome|chrome-extension|devtools|view-source|about):/i

let ws = null
let retry = 1000
let keepalive = null

async function config() {
  const { token = '', port = 9000 } = await chrome.storage.local.get(['token', 'port'])
  return { token, port }
}

async function connect() {
  const { token, port } = await config()
  if (!token || ws) return
  const sock = new WebSocket(`ws://127.0.0.1:${port}/api/chrome-bridge/socket`)
  ws = sock
  // The token goes in the first frame, never the URL, so it stays out of access logs.
  sock.onopen = () => sock.send(JSON.stringify({ type: 'hello', token }))
  sock.onmessage = async (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.type === 'pong') return
    if (msg.type === 'ready') {
      retry = 1000
      chrome.storage.local.set({ status: 'connected' })
      keepalive = setInterval(() => sock.send(JSON.stringify({ type: 'ping' })), KEEPALIVE_MS)
      return
    }
    if (msg.type === 'error') {
      chrome.storage.local.set({ status: 'refused: ' + msg.message })
      return
    }
    try {
      sock.send(JSON.stringify({ id: msg.id, ok: true, result: await handle(msg) }))
    } catch (e) {
      sock.send(JSON.stringify({ id: msg.id, ok: false, error: String(e.message ?? e) }))
    }
  }
  sock.onclose = () => {
    clearInterval(keepalive)
    ws = null
    chrome.storage.local.set({ status: 'disconnected' })
    setTimeout(connect, retry)
    retry = Math.min(retry * 2, 15_000)
  }
  sock.onerror = () => sock.close()
}

// The JClaw tab group is the only boundary. Found by title on every call rather than cached, so a
// service worker restart or a group the operator created by hand behaves the same.
async function groupIds() {
  return (await chrome.tabGroups.query({ title: GROUP_TITLE })).map((g) => g.id)
}

async function groupedTab(tabId) {
  if (!Number.isInteger(tabId)) throw new Error('tabId is required')
  let tab
  try { tab = await chrome.tabs.get(tabId) } catch { throw new Error(`no tab ${tabId}`) }
  if (!(await groupIds()).includes(tab.groupId)) {
    throw new Error(`tab ${tabId} is not in the ${GROUP_TITLE} tab group`)
  }
  if (BLOCKED.test(tab.url ?? '')) throw new Error(`refusing to touch ${tab.url}`)
  return tab
}

async function openGroupedTab(url) {
  const [existing] = await chrome.tabGroups.query({ title: GROUP_TITLE })
  const tab = await chrome.tabs.create({ url: 'about:blank', active: false, ...(existing && { windowId: existing.windowId }) })
  const groupId = await chrome.tabs.group({ tabIds: [tab.id], ...(existing && { groupId: existing.id }) })
  if (!existing) await chrome.tabGroups.update(groupId, { title: GROUP_TITLE, color: GROUP_COLOR })
  await chrome.tabs.update(tab.id, { url })
  await waitForLoad(tab.id)
  return tab
}

function waitForLoad(tabId) {
  return new Promise((resolve) => {
    const done = () => { chrome.tabs.onUpdated.removeListener(on); clearTimeout(t); resolve() }
    const on = (id, info) => { if (id === tabId && info.status === 'complete') done() }
    const t = setTimeout(done, 15_000)
    chrome.tabs.onUpdated.addListener(on)
  })
}

// Real browser input and screenshots need the debugger API: a scripted DOM event is untrusted, so a
// synthetic Enter does not submit a form. It shows Chrome's "started debugging this browser" bar on an
// attached tab. Every use goes through groupedTab first, which is the only thing keeping this to the group.
const CDP_VERSION = '1.3'
const MAX_BUFFER = 200
const attached = new Map()

async function ensureAttached(tabId) {
  if (attached.has(tabId)) return attached.get(tabId)
  try {
    await chrome.debugger.attach({ tabId }, CDP_VERSION)
  } catch (e) {
    // A restarted service worker forgets what it attached; Chrome still has the tab attached.
    if (!/already attached/i.test(String(e.message ?? e))) throw e
  }
  const state = { console: [], network: [], dragData: null }
  attached.set(tabId, state)
  for (const domain of ['Runtime', 'Network', 'Page']) await chrome.debugger.sendCommand({ tabId }, `${domain}.enable`)
  // The agent's tabs open in the background, and Chrome does not deliver real key events to a page that does
  // not think it has focus.
  await chrome.debugger.sendCommand({ tabId }, 'Emulation.setFocusEmulationEnabled', { enabled: true }).catch(() => {})
  return state
}

async function cdp(tabId, method, params = {}) {
  try {
    await ensureAttached(tabId)
    return await chrome.debugger.sendCommand({ tabId }, method, params)
  } catch (e) {
    if (!/Cannot access a chrome-extension/i.test(String(e.message ?? e))) throw e
    // Another extension's frame was mid-injection into the page; a clean re-attach clears it.
    attached.delete(tabId)
    await chrome.debugger.detach({ tabId }).catch(() => {})
    await ensureAttached(tabId)
    return chrome.debugger.sendCommand({ tabId }, method, params)
  }
}

function remember(list, entry) {
  list.push(entry)
  if (list.length > MAX_BUFFER) list.shift()
}

chrome.debugger.onEvent.addListener((source, method, params) => {
  const state = attached.get(source.tabId)
  if (!state) return
  if (method === 'Input.dragIntercepted') {
    state.dragData = params.data
  } else if (method === 'Runtime.consoleAPICalled') {
    remember(state.console, `${params.type}: ${params.args.map((a) => a.value ?? a.description ?? a.type).join(' ')}`.slice(0, 500))
  } else if (method === 'Runtime.exceptionThrown') {
    remember(state.console, `exception: ${params.exceptionDetails.text} ${params.exceptionDetails.exception?.description ?? ''}`.slice(0, 500))
  } else if (method === 'Network.requestWillBeSent') {
    // The address without its query: a query string is where a token would be, and headers and bodies are never read.
    const u = new URL(params.request.url)
    remember(state.network, { id: params.requestId, method: params.request.method, url: u.origin + u.pathname })
  } else if (method === 'Network.responseReceived') {
    const row = state.network.findLast((r) => r.id === params.requestId)
    if (row) row.status = params.response.status
  }
})
chrome.debugger.onDetach.addListener((source) => attached.delete(source.tabId))
chrome.tabs.onRemoved.addListener((tabId) => attached.delete(tabId))

const MODIFIERS = { alt: 1, ctrl: 2, meta: 4, shift: 8 }
const KEYS = {
  Enter: 13, Tab: 9, Escape: 27, Backspace: 8, Delete: 46, ' ': 32, Home: 36, End: 35,
  PageUp: 33, PageDown: 34, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40,
}

async function pointOf(tabId, msg) {
  if (Number.isFinite(msg.x) && Number.isFinite(msg.y)) return { x: msg.x, y: msg.y }
  if (!msg.ref) throw new Error('give a ref or x and y')
  const r = await run(tabId, (ref) => {
    const el = window.__agentRefs?.get(ref)
    if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
    el.scrollIntoView({ block: 'center', inline: 'center' })
    const b = el.getBoundingClientRect()
    return { x: b.left + b.width / 2, y: b.top + b.height / 2, zero: b.width === 0 || b.height === 0 }
  }, [msg.ref])
  return r
}

async function mouse(tabId, type, x, y, extra = {}) {
  await cdp(tabId, 'Input.dispatchMouseEvent', { type, x, y, button: 'none', ...extra })
}

async function pressAt(tabId, x, y, button = 'left', count = 1) {
  await mouse(tabId, 'mouseMoved', x, y)
  await mouse(tabId, 'mousePressed', x, y, { button, clickCount: count })
  await mouse(tabId, 'mouseReleased', x, y, { button, clickCount: count })
}

// A click can start a navigation that has not begun when the script returns. Without waiting, the agent's
// next read still sees the page it just left.
async function settleAfterClick(tabId, before) {
  const deadline = Date.now() + 700
  while (Date.now() < deadline) {
    const t = await chrome.tabs.get(tabId)
    if (t.status === 'loading' || t.url !== before) {
      const limit = Date.now() + 15_000
      while (Date.now() < limit && (await chrome.tabs.get(tabId)).status === 'loading') {
        await new Promise((r) => setTimeout(r, 100))
      }
      return
    }
    await new Promise((r) => setTimeout(r, 100))
  }
}

async function run(tabId, func, args = []) {
  const [r] = await chrome.scripting.executeScript({ target: { tabId }, func, args })
  // A function that throws inside the page yields an empty result, not a rejection.
  if (!r || r.result === undefined || r.result === null) throw new Error('page script produced no result')
  if (r.result.error) throw new Error(r.result.error)
  return r.result
}

async function handle(msg) {
  switch (msg.action) {
    case 'tabs_list': {
      const ids = await groupIds()
      const tabs = (await Promise.all(ids.map((groupId) => chrome.tabs.query({ groupId })))).flat()
      return tabs.map((t) => ({ id: t.id, title: t.title, url: t.url, active: t.active }))
    }
    case 'navigate': {
      if (typeof msg.url !== 'string' || BLOCKED.test(msg.url) || !/^https?:/i.test(msg.url)) throw new Error('only http(s) URLs are allowed')
      if (msg.tabId === undefined) {
        const opened = await openGroupedTab(msg.url)
        return { tabId: opened.id, url: (await chrome.tabs.get(opened.id)).url }
      }
      const tab = await groupedTab(msg.tabId)
      await chrome.tabs.update(tab.id, { url: msg.url })
      await waitForLoad(tab.id)
      return { tabId: tab.id, url: (await chrome.tabs.get(tab.id)).url }
    }
    case 'tab_info': {
      const tab = await groupedTab(msg.tabId)
      return { tabId: tab.id, url: tab.url, title: tab.title }
    }
    case 'close': {
      const tab = await groupedTab(msg.tabId)
      await chrome.tabs.remove(tab.id)
      return { closed: tab.id }
    }
    case 'read': {
      const tab = await groupedTab(msg.tabId)
      return run(tab.id, readPage, [MAX_TEXT, MAX_ELEMENTS, Math.max(0, Number(msg.offset) || 0)])
    }
    case 'click': {
      const tab = await groupedTab(msg.tabId)
      // A real click, so an input takes focus and a handler sees a trusted event; an element with no size
      // cannot be pointed at, so it keeps the scripted click.
      const p = await pointOf(tab.id, msg)
      if (p.zero) {
        const clicked = await run(tab.id, (ref) => {
          const el = window.__agentRefs?.get(ref)
          if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
          el.click()
          return { done: 'clicked' }
        }, [msg.ref])
        await settleAfterClick(tab.id, tab.url)
        return clicked
      }
      await pressAt(tab.id, p.x, p.y)
      await settleAfterClick(tab.id, tab.url)
      return { done: 'clicked' }
    }
    case 'type': {
      const tab = await groupedTab(msg.tabId)
      return run(tab.id, (ref, text) => {
        const el = window.__agentRefs?.get(ref)
        if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
        el.focus()
        const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text)
        el.dispatchEvent(new Event('input', { bubbles: true }))
        el.dispatchEvent(new Event('change', { bubbles: true }))
        return { done: 'typed' }
      }, [msg.ref, msg.text])
    }
    case 'screenshot': {
      const tab = await groupedTab(msg.tabId)
      // One image pixel per page (CSS) pixel, so a point read off the image is the point click and hover take.
      // Without the clip, a 2x display gives an image twice the size and every coordinate twice too far.
      const m = await cdp(tab.id, 'Page.getLayoutMetrics')
      // The clip's scale is multiplied by the display's pixel ratio, so ask for its inverse.
      const dpr = (await cdp(tab.id, 'Runtime.evaluate', { expression: 'window.devicePixelRatio', returnByValue: true })).result.value || 1
      const full = msg.fullPage === true
      const v = m.cssVisualViewport
      const base = full
        ? { x: 0, y: 0, width: Math.min(m.cssContentSize.width, 4000), height: Math.min(m.cssContentSize.height, 16000) }
        : { x: v.pageX, y: v.pageY, width: v.clientWidth, height: v.clientHeight }
      // A frame of a recording is smaller still: capped to maxWidth, in WebP or PNG as the recorder asks.
      const shrink = Number(msg.maxWidth) > 0 ? Math.min(1, Number(msg.maxWidth) / base.width) : 1
      const format = msg.format === 'webp' ? 'webp' : 'png'
      const shot = await cdp(tab.id, 'Page.captureScreenshot', {
        format, captureBeyondViewport: full, clip: { ...base, scale: shrink / dpr },
        ...(format === 'webp' && { quality: Math.min(100, Math.max(1, Number(msg.quality) || 80)) }),
      })
      return { data: shot.data, url: (await chrome.tabs.get(tab.id)).url }
    }
    case 'scroll': {
      const tab = await groupedTab(msg.tabId)
      return run(tab.id, (ref, dx, dy) => {
        if (ref) {
          const el = window.__agentRefs?.get(ref)
          if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
          el.scrollIntoView({ block: 'center' })
        } else {
          window.scrollBy(dx, dy)
        }
        return { scrollY: Math.round(window.scrollY), maxScrollY: Math.round(document.documentElement.scrollHeight - window.innerHeight) }
      }, [msg.ref ?? null, Number(msg.deltaX) || 0, Number(msg.deltaY) || 0])
    }
    case 'hover': {
      const tab = await groupedTab(msg.tabId)
      const p = await pointOf(tab.id, msg)
      if (p.error) throw new Error(p.error)
      await mouse(tab.id, 'mouseMoved', p.x, p.y)
      return { done: 'hovered' }
    }
    case 'click_at': {
      const tab = await groupedTab(msg.tabId)
      const before = tab.url
      await pressAt(tab.id, msg.x, msg.y, msg.button ?? 'left', msg.count ?? 1)
      await settleAfterClick(tab.id, before)
      return { done: 'clicked' }
    }
    case 'drag': {
      const tab = await groupedTab(msg.tabId)
      const state = await ensureAttached(tab.id)
      state.dragData = null
      // Without interception, pressing on an image or link starts a native drag session that blocks Chrome's
      // input channel ("Detached while handling command"). Intercepted, the page's drag is finished by hand.
      await cdp(tab.id, 'Input.setInterceptDrags', { enabled: true })
      try {
        await mouse(tab.id, 'mouseMoved', msg.x, msg.y)
        await mouse(tab.id, 'mousePressed', msg.x, msg.y, { button: 'left', clickCount: 1 })
        for (let i = 1; i <= 8; i++) {
          await mouse(tab.id, 'mouseMoved', msg.x + ((msg.toX - msg.x) * i) / 8, msg.y + ((msg.toY - msg.y) * i) / 8, { button: 'left' })
        }
        if (state.dragData) {
          for (const type of ['dragEnter', 'dragOver', 'drop']) {
            await cdp(tab.id, 'Input.dispatchDragEvent', { type, x: msg.toX, y: msg.toY, data: state.dragData })
          }
        }
        await mouse(tab.id, 'mouseReleased', msg.toX, msg.toY, { button: 'left', clickCount: 1 })
      } catch (e) {
        // Dropping a link on the page makes Chrome navigate to it, which detaches the debugger mid-command.
        if (/Detached while handling command/i.test(String(e.message ?? e))) return { done: 'dragged (the page navigated)' }
        throw e
      } finally {
        await cdp(tab.id, 'Input.setInterceptDrags', { enabled: false }).catch(() => {})
      }
      return { done: 'dragged' }
    }
    case 'key': {
      const tab = await groupedTab(msg.tabId)
      const key = String(msg.key)
      const code = KEYS[key] ?? (key.length === 1 ? key.toUpperCase().charCodeAt(0) : undefined)
      if (code === undefined) throw new Error(`unknown key ${key}`)
      const modifiers = (msg.modifiers ?? []).reduce((m, name) => m | (MODIFIERS[String(name).toLowerCase()] ?? 0), 0)
      const text = key.length === 1 && !(modifiers & (MODIFIERS.ctrl | MODIFIERS.meta)) ? key : key === 'Enter' ? '\r' : undefined
      const base = { key, code: key.length === 1 ? `Key${key.toUpperCase()}` : key, windowsVirtualKeyCode: code, modifiers }
      const before = tab.url
      await cdp(tab.id, 'Input.dispatchKeyEvent', { ...base, type: text ? 'keyDown' : 'rawKeyDown', ...(text && { text }) })
      await cdp(tab.id, 'Input.dispatchKeyEvent', { ...base, type: 'keyUp' })
      await settleAfterClick(tab.id, before)
      return { done: 'pressed' }
    }
    case 'form_input': {
      const tab = await groupedTab(msg.tabId)
      return run(tab.id, (ref, value) => {
        const el = window.__agentRefs?.get(ref)
        if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
        const fire = () => {
          el.dispatchEvent(new Event('input', { bubbles: true }))
          el.dispatchEvent(new Event('change', { bubbles: true }))
        }
        if (el instanceof HTMLSelectElement) {
          const want = String(value).toLowerCase()
          const option = [...el.options].find((o) => o.value.toLowerCase() === want || o.text.trim().toLowerCase() === want)
          if (!option) return { error: `no option ${value}; options are ${[...el.options].map((o) => o.text.trim()).join(', ')}` }
          el.value = option.value
          fire()
          return { done: `selected ${option.text.trim()}` }
        }
        if (el instanceof HTMLInputElement && (el.type === 'checkbox' || el.type === 'radio')) {
          el.checked = value === true || String(value).toLowerCase() === 'true'
          fire()
          return { done: el.checked ? 'checked' : 'unchecked' }
        }
        if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
          const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
          Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, String(value))
          fire()
          return { done: 'set' }
        }
        return { error: 'that element takes no value' }
      }, [msg.ref, msg.value])
    }
    case 'find': {
      const tab = await groupedTab(msg.tabId)
      return run(tab.id, (query, limit) => {
        const words = String(query).toLowerCase().split(/\s+/).filter(Boolean)
        if (!window.__agentRefs) window.__agentRefs = new Map()
        const hits = []
        let n = window.__agentRefs.size
        for (const el of document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[onclick],h1,h2,h3,label')) {
          const b = el.getBoundingClientRect()
          if (b.width === 0 || b.height === 0) continue
          const text = (el.innerText || el.value || el.getAttribute('aria-label') || el.placeholder || '').trim().slice(0, 80)
          const hay = `${text} ${el.getAttribute('aria-label') ?? ''} ${el.placeholder ?? ''} ${el.name ?? ''} ${el.id ?? ''}`.toLowerCase()
          const score = words.filter((w) => hay.includes(w)).length
          if (score === 0) continue
          hits.push({ score, el, text })
        }
        hits.sort((a, b) => b.score - a.score)
        return {
          matches: hits.slice(0, limit).map(({ el, text }) => {
            const ref = `f${++n}`
            window.__agentRefs.set(ref, el)
            return {
              ref, tag: el.tagName.toLowerCase(), text,
              href: el.tagName === 'A' ? el.href.slice(0, 300) : undefined,
              link: el.tagName === 'A' && el.hasAttribute('href') && !/^\s*javascript:/i.test(el.getAttribute('href')),
            }
          }),
        }
      }, [msg.query, 10])
    }
    case 'javascript': {
      const tab = await groupedTab(msg.tabId)
      const r = await cdp(tab.id, 'Runtime.evaluate', {
        expression: String(msg.expression), returnByValue: true, awaitPromise: true, timeout: 5000, userGesture: false,
      })
      if (r.exceptionDetails) {
        throw new Error(`script error: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`.slice(0, 500))
      }
      const v = r.result
      const value = v.type === 'undefined' ? 'undefined' : typeof v.value === 'string' ? v.value : JSON.stringify(v.value ?? v.description ?? null)
      return { value, type: v.type }
    }
    case 'upload': {
      const tab = await groupedTab(msg.tabId)
      const nonce = crypto.randomUUID()
      const tagged = await run(tab.id, (ref, mark) => {
        const el = window.__agentRefs?.get(ref)
        if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
        if (!(el instanceof HTMLInputElement) || el.type !== 'file') return { error: 'that element is not a file input' }
        el.setAttribute('data-jclaw-upload', mark)
        return { done: 'tagged' }
      }, [msg.ref, nonce])
      try {
        const doc = await cdp(tab.id, 'DOM.getDocument', { depth: 0 })
        const found = await cdp(tab.id, 'DOM.querySelector', { nodeId: doc.root.nodeId, selector: `[data-jclaw-upload="${nonce}"]` })
        if (!found.nodeId) throw new Error('could not reach the file input')
        await cdp(tab.id, 'DOM.setFileInputFiles', { nodeId: found.nodeId, files: [String(msg.path)] })
      } finally {
        await run(tab.id, (mark) => {
          document.querySelector(`[data-jclaw-upload="${mark}"]`)?.removeAttribute('data-jclaw-upload')
          return { done: 'untagged' }
        }, [nonce])
      }
      return { done: tagged.done === 'tagged' ? 'uploaded' : 'failed' }
    }
    case 'resize': {
      const tab = await groupedTab(msg.tabId)
      await chrome.windows.update(tab.windowId, { state: 'normal', width: msg.width, height: msg.height })
      return { done: 'resized' }
    }
    case 'console': {
      const tab = await groupedTab(msg.tabId)
      const state = await ensureAttached(tab.id)
      const lines = [...state.console]
      if (msg.clear) state.console.length = 0
      return { lines }
    }
    case 'network': {
      const tab = await groupedTab(msg.tabId)
      const state = await ensureAttached(tab.id)
      const rows = state.network.map(({ method, url, status }) => ({ method, url, status }))
      if (msg.clear) state.network.length = 0
      return { rows }
    }
    default:
      throw new Error(`unknown action ${msg.action}`)
  }
}

// Injected into the page: must be self-contained.
function readPage(maxText, maxElements, offset) {
  window.__agentRefs = new Map()
  const sel = 'a[href],button,input,textarea,select,[role=button],[onclick]'
  const elements = []
  let n = 0
  let more = false
  for (const el of document.querySelectorAll(sel)) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    if (n >= maxElements) {
      more = true
      break
    }
    const ref = `e${++n}`
    window.__agentRefs.set(ref, el)
    elements.push({
      ref,
      tag: el.tagName.toLowerCase(),
      type: el.getAttribute('type') ?? undefined,
      text: (el.innerText || el.value || el.getAttribute('aria-label') || el.placeholder || '').trim().slice(0, 80),
      // The absolute address, so the agent can see where a link goes before it clicks.
      href: el.tagName === 'A' ? el.href.slice(0, 300) : undefined,
      // A plain link: the only click the server lets an agent make without asking. A click handler on
      // a link, or a javascript: href, is not plain, so it is not flagged.
      link: el.tagName === 'A' && el.hasAttribute('href') && !/^\s*javascript:/i.test(el.getAttribute('href')),
    })
  }
  const full = document.body.innerText
  return {
    title: document.title,
    url: location.href,
    text: full.slice(offset, offset + maxText),
    textOffset: offset,
    textLength: full.length,
    elements,
    elementsTruncated: more,
  }
}

chrome.storage.onChanged.addListener((c) => { if (c.token || c.port) { ws?.close(); ws = null; connect() } })
chrome.runtime.onStartup.addListener(connect)
chrome.runtime.onInstalled.addListener(connect)
connect()
