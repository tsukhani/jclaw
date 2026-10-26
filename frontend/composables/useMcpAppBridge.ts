import { computed, onBeforeUnmount, onMounted, ref, type InjectionKey, type Ref } from 'vue'
import { useApiMutation } from '~/composables/useApiMutation'
import type { McpAppCall } from '~/types/api'

/**
 * The host side of MCP Apps (SEP-1865, 2026-01-26) for one view in the web chat. The host answers
 * ui/initialize, ping and ui/request-display-mode, and the three actions it is given: tools/call on the
 * view's own server, ui/message and ui/open-link, each only right after a click in the view. It refuses
 * every other request with -32601. It sends nothing before the view's ui/notifications/initialized, then
 * tool-input once, then tool-result.
 */

/** A view that sends no ui/initialize this long after its page loads could not be loaded; Prefab's renderer is 1.4 MB. */
export const MCP_APP_INIT_TIMEOUT_MS = 20_000
/**
 * The longest a view may take to load and initialize, from when its frame first comes into view: a page
 * request can queue behind the browser's per-host connection limit, and a lazy frame out of view never starts.
 */
export const MCP_APP_LOAD_CAP_MS = 60_000
/** The tallest a frame grows; a taller view scrolls inside it. */
export const MCP_APP_MAX_HEIGHT = 600
/** The frame's height from ui/initialize until the view first reports its size. */
export const MCP_APP_INITIAL_HEIGHT = 150

const PROTOCOL_VERSION = '2026-01-26'
const METHOD_NOT_FOUND = -32601
/** The code MCP Apps gives a host's refusal of a view's request. */
const REFUSED = -32000
const MCP_APP_NO_CLICK = 'This view can act only right after a click in it.'
const MCP_APP_CALL_FAILED = 'The tool call failed.'
const MCP_APP_BUSY = 'This view is still running its last tool call.'
/** The bridge's own version, raised when what it offers a view changes. */
const HOST_INFO = { name: 'JClaw', version: '2' }

export type McpAppState = 'loading' | 'ready' | 'unavailable'

export interface McpAppHostContext {
  theme?: 'light' | 'dark'
  styles?: { variables: Record<string, string> }
  displayMode?: 'inline'
  availableDisplayModes?: Array<'inline'>
  containerDimensions?: { width: number, maxHeight: number }
  locale?: string
  timeZone?: string
  platform?: 'web'
}

/** A view's tool call as JClaw answers it: the tool's result for the view, or JClaw's own refusal. */
export type McpAppToolReply = { result: Record<string, unknown> } | { error: string }

/** Provided by the chat page: the conversation whose agent a view's tool calls run as, and the composer a view's message fills. */
export interface McpAppHost {
  conversationId: () => number | null
  /** Puts the text in the composer, after a draft already typed; never sends it. */
  prefill: (text: string) => void
}

export const mcpAppHostKey: InjectionKey<McpAppHost> = Symbol('mcpAppHost')

export interface McpAppBridgeOptions {
  /** The frame's window: the only source a message is accepted from, and where replies go. */
  view: () => Window | null | undefined
  call: () => McpAppCall
  hostContext: () => McpAppHostContext
  /** The view sent ui/initialize. */
  onReady: () => void
  /** The view is initialized and has its tool input and result: a change held back meanwhile can go now. */
  onInitialized?: () => void
  onSize: (height: number) => void
  /** Runs a tool on the view's own server. Without it, tools/call is not offered. */
  callTool?: (name: string, args: Record<string, unknown>) => Promise<McpAppToolReply>
  /** Without it, ui/message is not offered. */
  prefill?: (text: string) => void
  /** Opens an http(s) URL. Without it, ui/open-link is not offered. */
  openLink?: (url: string) => void
}

export interface McpAppBridge {
  handle: (event: MessageEvent) => void
  /** Whether the change was posted: before the view is initialized it is held by the caller, not sent. */
  contextChanged: (changed: McpAppHostContext) => boolean
  teardown: () => void
}

interface JsonRpcIn {
  jsonrpc?: unknown
  id?: unknown
  method?: unknown
  params?: unknown
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isTextBlock(value: unknown): value is { type: 'text', text: string } {
  return isObject(value) && value.type === 'text' && typeof value.text === 'string'
}

// The 2026-01-26 spec sends one text block; the ext-apps SDK sends an array of blocks.
function messageText(params: unknown): string | null {
  if (!isObject(params) || params.role !== 'user') return null
  const blocks = Array.isArray(params.content) ? params.content : [params.content]
  if (!blocks.length || !blocks.every(isTextBlock)) return null
  const text = blocks.map(block => block.text).join('\n\n')
  return text.trim() ? text : null
}

function linkUrl(params: unknown): string | null {
  if (!isObject(params) || typeof params.url !== 'string') return null
  try {
    const url = new URL(params.url)
    return url.protocol === 'http:' || url.protocol === 'https:' ? url.href : null
  }
  catch {
    return null
  }
}

// A click in a view's frame activates the page for a few seconds and focuses that frame; a key or click elsewhere leaves it unfocused.
function clickedIn(view: Window | null | undefined): boolean {
  const focused = document.activeElement
  return navigator.userActivation?.isActive === true
    && !!view && focused instanceof HTMLIFrameElement && focused.contentWindow === view
}

export function createMcpAppBridge(opts: McpAppBridgeOptions): McpAppBridge {
  let initializeSeen = false
  let initialized = false
  let toolCallRunning = false

  // A round trip through JSON drops Vue's proxies, which postMessage cannot clone.
  function post(message: object) {
    opts.view()?.postMessage(JSON.parse(JSON.stringify(message)), '*')
  }

  function notify(method: string, params: unknown): boolean {
    if (initialized) post({ jsonrpc: '2.0', method, params })
    return initialized
  }

  function refuse(id: string | number, message: string) {
    post({ jsonrpc: '2.0', id, error: { code: REFUSED, message } })
  }

  function answerToolCall(id: string | number, params: unknown, callTool: NonNullable<McpAppBridgeOptions['callTool']>) {
    if (!isObject(params) || typeof params.name !== 'string' || !(params.arguments === undefined || isObject(params.arguments))) {
      refuse(id, 'Invalid tool call format')
      return
    }
    // One activation would otherwise let a view queue calls for as long as it lasts.
    if (toolCallRunning) {
      refuse(id, MCP_APP_BUSY)
      return
    }
    toolCallRunning = true
    callTool(params.name, params.arguments ?? {}).finally(() => {
      toolCallRunning = false
    }).then(
      (reply: unknown) => {
        if (isObject(reply) && isObject(reply.result)) post({ jsonrpc: '2.0', id, result: reply.result })
        else refuse(id, isObject(reply) && typeof reply.error === 'string' ? reply.error : MCP_APP_CALL_FAILED)
      },
      () => refuse(id, MCP_APP_CALL_FAILED),
    )
  }

  function answerMessage(id: string | number, params: unknown, prefill: (text: string) => void) {
    const text = messageText(params)
    if (text === null) {
      refuse(id, 'Invalid message format')
      return
    }
    prefill(text)
    post({ jsonrpc: '2.0', id, result: {} })
  }

  function answerOpenLink(id: string | number, params: unknown, openLink: (url: string) => void) {
    const url = linkUrl(params)
    if (url === null) {
      refuse(id, 'Invalid URL')
      return
    }
    openLink(url)
    post({ jsonrpc: '2.0', id, result: {} })
  }

  /** The action a view asked for, when the host was given one for that method. */
  function action(method: string, id: string | number, params: unknown): (() => void) | null {
    const { callTool, prefill, openLink } = opts
    if (method === 'tools/call' && callTool) return () => answerToolCall(id, params, callTool)
    if (method === 'ui/message' && prefill) return () => answerMessage(id, params, prefill)
    if (method === 'ui/open-link' && openLink) return () => answerOpenLink(id, params, openLink)
    return null
  }

  function onRequest(id: string | number, method: string, params: unknown) {
    switch (method) {
      case 'ui/initialize':
        initializeSeen = true
        opts.onReady()
        post({ jsonrpc: '2.0', id, result: {
          protocolVersion: PROTOCOL_VERSION,
          hostInfo: HOST_INFO,
          hostCapabilities: {
            ...(opts.callTool ? { serverTools: {} } : {}),
            ...(opts.openLink ? { openLinks: {} } : {}),
          },
          hostContext: opts.hostContext(),
        } })
        return
      case 'ping':
        post({ jsonrpc: '2.0', id, result: {} })
        return
      case 'ui/request-display-mode':
        post({ jsonrpc: '2.0', id, result: { mode: 'inline' } })
        return
    }
    const act = action(method, id, params)
    if (!act) post({ jsonrpc: '2.0', id, error: { code: METHOD_NOT_FOUND, message: `Method not found: ${method}` } })
    else if (!clickedIn(opts.view())) refuse(id, MCP_APP_NO_CLICK)
    else act()
  }

  function onNotification(method: string, params: unknown) {
    if (method === 'ui/notifications/initialized') {
      if (initialized || !initializeSeen) return
      initialized = true
      const call = opts.call()
      notify('ui/notifications/tool-input', { arguments: call.arguments })
      notify('ui/notifications/tool-result', call.result)
      opts.onInitialized?.()
    }
    else if (method === 'ui/notifications/size-changed') {
      const height = (params as { height?: unknown } | null)?.height
      if (typeof height === 'number' && Number.isFinite(height) && height >= 0) opts.onSize(height)
    }
  }

  function handle(event: MessageEvent) {
    const view = opts.view()
    if (!view || event.source !== view) return
    const msg = event.data as JsonRpcIn | null
    if (typeof msg !== 'object' || msg?.jsonrpc !== '2.0' || typeof msg.method !== 'string') return
    if (typeof msg.id === 'string' || typeof msg.id === 'number') onRequest(msg.id, msg.method, msg.params)
    else onNotification(msg.method, msg.params)
  }

  return {
    handle,
    contextChanged: changed => notify('ui/notifications/host-context-changed', changed),
    teardown: () => {
      if (initialized) post({ jsonrpc: '2.0', id: 1, method: 'ui/resource-teardown', params: { reason: 'The chat removed this view.' } })
    },
  }
}

/** The page's URL: JClaw reads it from the view's own MCP server and serves it under a sandboxing CSP. */
export function mcpAppViewUrl(call: McpAppCall): string {
  return `/api/mcp-servers/view?server=${encodeURIComponent(call.server)}&uri=${encodeURIComponent(call.resourceUri)}`
}

function currentTheme(): 'light' | 'dark' {
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

// The view draws in step with the chat from the colours its container computes to.
function styleVariables(el: HTMLElement | null): Record<string, string> {
  if (!el) return {}
  const style = getComputedStyle(el)
  const variables: Record<string, string> = {
    '--color-background-primary': style.backgroundColor,
    '--color-text-primary': style.color,
    '--color-border-primary': style.borderTopColor,
    '--font-sans': style.fontFamily,
  }
  return Object.fromEntries(Object.entries(variables).filter(([, value]) => !!value))
}

/**
 * Wires {@link createMcpAppBridge} to one frame: the message listener, the load timeouts, theme and width
 * changes, the view's actions, and teardown. A second load of the frame is a new document the page's CSP
 * no longer covers, so the frame is dropped as unavailable. Without a {@link McpAppHost} a view can only open links.
 */
export function useMcpAppBridge(
  frame: Ref<HTMLIFrameElement | null>,
  container: Ref<HTMLElement | null>,
  call: () => McpAppCall,
  host: McpAppHost | null = null,
) {
  const { mutate } = useApiMutation()
  const state = ref<McpAppState>('loading')
  const height = ref(0)
  const src = computed(() => mcpAppViewUrl(call()))
  let initTimer: ReturnType<typeof setTimeout> | undefined
  let capTimer: ReturnType<typeof setTimeout> | undefined
  let loads = 0
  let visibility: IntersectionObserver | null = null
  let themeObserver: MutationObserver | null = null
  let resizeObserver: ResizeObserver | null = null
  let sentTheme: '' | 'light' | 'dark' = ''
  let sentWidth = 0

  function width(): number {
    return Math.round(container.value?.clientWidth ?? 0)
  }

  // The request names only the tool: the server is always the view's own.
  async function callTool(name: string, args: Record<string, unknown>): Promise<McpAppToolReply> {
    const conversationId = host?.conversationId()
    if (conversationId == null) return { error: MCP_APP_CALL_FAILED }
    const reply = await mutate<McpAppToolReply>('/api/mcp-servers/view/call', {
      method: 'POST',
      body: { conversationId, server: call().server, tool: name, arguments: args },
    })
    return reply ?? { error: MCP_APP_CALL_FAILED }
  }

  const bridge = createMcpAppBridge({
    view: () => frame.value?.contentWindow,
    call,
    callTool: host ? callTool : undefined,
    prefill: host?.prefill,
    openLink: (url) => {
      window.open(url, '_blank', 'noopener,noreferrer')
    },
    hostContext: () => {
      sentTheme = currentTheme()
      sentWidth = width()
      return {
        theme: currentTheme(),
        styles: { variables: styleVariables(container.value) },
        displayMode: 'inline',
        availableDisplayModes: ['inline'],
        containerDimensions: { width: sentWidth, maxHeight: MCP_APP_MAX_HEIGHT },
        locale: navigator.language,
        timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        platform: 'web',
      }
    },
    onInitialized: () => {
      sendTheme()
      sendWidth()
    },
    onReady: () => {
      clearTimeout(initTimer)
      clearTimeout(capTimer)
      if (state.value !== 'loading') return
      state.value = 'ready'
      height.value = MCP_APP_INITIAL_HEIGHT
    },
    onSize: (h) => {
      if (state.value === 'ready') height.value = Math.min(Math.ceil(h), MCP_APP_MAX_HEIGHT)
    },
  })

  // A change is marked sent only once posted, so one that lands before the view is initialized goes after it.
  function sendTheme() {
    if (!sentTheme || currentTheme() === sentTheme) return
    if (bridge.contextChanged({ theme: currentTheme(), styles: { variables: styleVariables(container.value) } })) {
      sentTheme = currentTheme()
    }
  }

  function sendWidth() {
    const now = width()
    if (now === sentWidth) return
    if (bridge.contextChanged({ containerDimensions: { width: now, maxHeight: MCP_APP_MAX_HEIGHT } })) sentWidth = now
  }

  function unavailable() {
    clearTimeout(initTimer)
    clearTimeout(capTimer)
    state.value = 'unavailable'
  }

  function timeOut() {
    if (state.value === 'loading') unavailable()
  }

  function onLoad() {
    loads += 1
    if (loads === 1) initTimer = setTimeout(timeOut, MCP_APP_INIT_TIMEOUT_MS)
    else unavailable()
  }

  // A frame removed as unavailable may still be delivering its last document's messages.
  function handle(event: MessageEvent) {
    if (state.value !== 'unavailable') bridge.handle(event)
  }

  function startCap() {
    capTimer = setTimeout(timeOut, MCP_APP_LOAD_CAP_MS)
  }

  onMounted(() => {
    window.addEventListener('message', handle)
    if (typeof IntersectionObserver === 'undefined' || !container.value) {
      startCap()
    }
    else {
      visibility = new IntersectionObserver((entries) => {
        if (!entries.some(entry => entry.isIntersecting)) return
        visibility?.disconnect()
        visibility = null
        startCap()
      })
      visibility.observe(container.value)
    }
    themeObserver = new MutationObserver(sendTheme)
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
    if (typeof ResizeObserver !== 'undefined' && container.value) {
      resizeObserver = new ResizeObserver(sendWidth)
      resizeObserver.observe(container.value)
    }
  })

  onBeforeUnmount(() => {
    if (state.value !== 'unavailable') bridge.teardown()
    window.removeEventListener('message', handle)
    clearTimeout(initTimer)
    clearTimeout(capTimer)
    visibility?.disconnect()
    themeObserver?.disconnect()
    resizeObserver?.disconnect()
  })

  return { state, height, src, onLoad }
}
