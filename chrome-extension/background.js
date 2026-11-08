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
      return run(tab.id, readPage, [MAX_TEXT, MAX_ELEMENTS])
    }
    case 'click': {
      const tab = await groupedTab(msg.tabId)
      return run(tab.id, (ref) => {
        const el = window.__agentRefs?.get(ref)
        if (!el || !el.isConnected) return { error: `stale or unknown ref ${ref}` }
        el.scrollIntoView({ block: 'center' })
        el.click()
        return { done: 'clicked' }
      }, [msg.ref])
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
      // captureVisibleTab only sees the focused tab of its window.
      await chrome.tabs.update(tab.id, { active: true })
      return { dataUrl: await chrome.tabs.captureVisibleTab(tab.windowId, { format: 'png' }) }
    }
    default:
      throw new Error(`unknown action ${msg.action}`)
  }
}

// Injected into the page: must be self-contained.
function readPage(maxText, maxElements) {
  window.__agentRefs = new Map()
  const sel = 'a[href],button,input,textarea,select,[role=button],[onclick]'
  const elements = []
  let n = 0
  for (const el of document.querySelectorAll(sel)) {
    const r = el.getBoundingClientRect()
    if (r.width === 0 || r.height === 0) continue
    const ref = `e${++n}`
    window.__agentRefs.set(ref, el)
    elements.push({
      ref,
      tag: el.tagName.toLowerCase(),
      type: el.getAttribute('type') ?? undefined,
      text: (el.innerText || el.value || el.getAttribute('aria-label') || el.placeholder || '').trim().slice(0, 80),
      href: el.getAttribute('href') ?? undefined,
      // A plain link: the only click the server lets an agent make without asking. A click handler on
      // a link, or a javascript: href, is not plain, so it is not flagged.
      link: el.tagName === 'A' && el.hasAttribute('href') && !/^\s*javascript:/i.test(el.getAttribute('href')),
    })
    if (n >= maxElements) break
  }
  return { title: document.title, url: location.href, text: document.body.innerText.slice(0, maxText), elements }
}

chrome.storage.onChanged.addListener((c) => { if (c.token || c.port) { ws?.close(); ws = null; connect() } })
chrome.runtime.onStartup.addListener(connect)
chrome.runtime.onInstalled.addListener(connect)
connect()
