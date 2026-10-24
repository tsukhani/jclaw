import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import type { McpAppCall } from '~/types/api'

/**
 * The host side of MCP Apps (SEP-1865, 2026-01-26) for one view in the web chat. Display only:
 * the host answers ui/initialize, ping and ui/request-display-mode,
 * declares no host capability, and refuses every other request with -32601. It sends nothing before the
 * view's ui/notifications/initialized, then tool-input once, then tool-result.
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
/** The bridge's own version, raised when what it offers a view changes. */
const HOST_INFO = { name: 'JClaw', version: '1' }

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

export function createMcpAppBridge(opts: McpAppBridgeOptions): McpAppBridge {
  let initializeSeen = false
  let initialized = false

  // A round trip through JSON drops Vue's proxies, which postMessage cannot clone.
  function post(message: object) {
    opts.view()?.postMessage(JSON.parse(JSON.stringify(message)), '*')
  }

  function notify(method: string, params: unknown): boolean {
    if (initialized) post({ jsonrpc: '2.0', method, params })
    return initialized
  }

  function onRequest(id: string | number, method: string) {
    switch (method) {
      case 'ui/initialize':
        initializeSeen = true
        opts.onReady()
        post({ jsonrpc: '2.0', id, result: {
          protocolVersion: PROTOCOL_VERSION,
          hostInfo: HOST_INFO,
          hostCapabilities: {},
          hostContext: opts.hostContext(),
        } })
        return
      case 'ping':
        post({ jsonrpc: '2.0', id, result: {} })
        return
      case 'ui/request-display-mode':
        post({ jsonrpc: '2.0', id, result: { mode: 'inline' } })
        return
      default:
        post({ jsonrpc: '2.0', id, error: { code: METHOD_NOT_FOUND, message: `Method not found: ${method}` } })
    }
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
    if (typeof msg !== 'object' || msg === null || msg.jsonrpc !== '2.0' || typeof msg.method !== 'string') return
    if (typeof msg.id === 'string' || typeof msg.id === 'number') onRequest(msg.id, msg.method)
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
 * changes, and teardown. A second load of the frame is a new document the page's CSP no longer covers,
 * so the frame is dropped as unavailable.
 */
export function useMcpAppBridge(frame: Ref<HTMLIFrameElement | null>, container: Ref<HTMLElement | null>, call: () => McpAppCall) {
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

  const bridge = createMcpAppBridge({
    view: () => frame.value?.contentWindow,
    call,
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
