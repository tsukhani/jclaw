import { afterEach, describe, it, expect, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { readBody, setResponseStatus, type H3Event } from 'h3'
import { nextTick } from 'vue'
import McpAppFrame from '~/components/chat/McpAppFrame.vue'
import { MCP_APP_INIT_TIMEOUT_MS, MCP_APP_LOAD_CAP_MS, mcpAppHostKey, type McpAppHost } from '~/composables/useMcpAppBridge'
import type { McpAppCall } from '~/types/api'

const CALL: McpAppCall = {
  server: 'serpapi',
  tool: 'search_table',
  resourceUri: 'ui://prefab/tool/583d325b0e9c/renderer.html',
  arguments: { params: { q: 'cats' } },
  result: { content: [{ type: 'text', text: '3 results' }], structuredContent: { rows: [1, 2, 3] }, isError: false },
}

let wrapper: VueWrapper | null = null

function mountFrame(host?: McpAppHost) {
  wrapper = mount(McpAppFrame, {
    props: { call: CALL },
    attachTo: document.body,
    global: { provide: host ? { [mcpAppHostKey as symbol]: host } : {} },
  })
  const iframe = wrapper.find('iframe').element as HTMLIFrameElement
  const view = iframe.contentWindow!
  const post = vi.spyOn(view, 'postMessage').mockImplementation(() => {})
  const fromView = (data: unknown, source: unknown = view) =>
    window.dispatchEvent(new MessageEvent('message', { data, source: source as Window }))
  const load = () => iframe.dispatchEvent(new Event('load'))
  /** A click in this frame: the page holds user activation and the frame has focus. */
  const click = () => {
    clicked(true)
    iframe.focus()
  }
  return { iframe, view, post, fromView, load, click, sent: () => post.mock.calls.map(([message]) => message) }
}

/** A ResizeObserver whose callback the test fires, as use-chat-scroll.test.ts stubs one. */
function stubResizeObserver() {
  const probe = { fire: () => {}, observed: [] as Element[] }
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: (entries: Array<{ target: Element }>) => void) {
      probe.fire = () => callback(probe.observed.map(target => ({ target })))
    }

    observe(target: Element) {
      probe.observed.push(target)
    }

    unobserve() {}
    disconnect() {}
  })
  return probe
}

/** An IntersectionObserver whose callback the test fires with whether the frame is in view. */
function stubIntersectionObserver() {
  const probe = { show: () => {}, disconnected: false }
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: (entries: Array<{ isIntersecting: boolean }>) => void) {
      probe.show = () => callback([{ isIntersecting: true }])
    }

    observe() {}
    disconnect() {
      probe.disconnected = true
    }
  })
  return probe
}

const initialize = { jsonrpc: '2.0', id: 1, method: 'ui/initialize', params: { protocolVersion: '2026-01-26' } }
const initialized = { jsonrpc: '2.0', method: 'ui/notifications/initialized' }

let unregister: Array<() => void> = []

/** The view-call endpoint answering with {@code handler}; returns the bodies it was sent. */
function serveViewCall(handler: (event: H3Event) => unknown) {
  const bodies: unknown[] = []
  unregister.push(registerEndpoint('/api/mcp-servers/view/call', {
    method: 'POST',
    handler: async (event) => {
      bodies.push(await readBody(event))
      return handler(event)
    },
  }))
  return bodies
}

function clicked(isActive: boolean) {
  Object.defineProperty(navigator, 'userActivation', { configurable: true, value: { isActive, hasBeenActive: isActive } })
}

const host = (conversationId: number | null = 42) =>
  ({ conversationId: () => conversationId, prefill: vi.fn((_text: string) => {}) }) satisfies McpAppHost

const toolCall = { jsonrpc: '2.0', id: 'c1', method: 'tools/call', params: { name: 'refresh', arguments: { page: 2 } } }

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  unregister.forEach(off => off())
  unregister = []
  Reflect.deleteProperty(navigator, 'userActivation')
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  document.documentElement.classList.remove('dark')
})

describe('McpAppFrame', () => {
  it('frames the page at JClaw\'s view URL with scripts but never the chat\'s origin', () => {
    const { iframe } = mountFrame()
    expect(iframe.getAttribute('sandbox')).toBe('allow-scripts')
    expect(iframe.getAttribute('src'))
      .toBe('/api/mcp-servers/view?server=serpapi&uri=ui%3A%2F%2Fprefab%2Ftool%2F583d325b0e9c%2Frenderer.html')
    expect(iframe.getAttribute('title')).toBe('View for search_table')
    expect(iframe.getAttribute('loading')).toBe('lazy')
    expect(iframe.style.height).toBe('0px')
    expect(wrapper!.find('[role="status"]').text()).toBe('Loading view…')
  })

  it('keeps the frame out of the tab order until its view is ready', async () => {
    const { iframe, fromView } = mountFrame()
    expect(iframe.getAttribute('tabindex')).toBe('-1')
    fromView(initialize)
    await nextTick()
    expect(iframe.hasAttribute('tabindex')).toBe(false)
  })

  it('shows the view once it initializes, then sizes it to what it reports up to the cap', async () => {
    const { iframe, fromView } = mountFrame()
    fromView(initialize)
    await nextTick()
    expect(iframe.style.height).toBe('150px')
    expect(wrapper!.find('[role="status"]').exists()).toBe(false)
    fromView({ jsonrpc: '2.0', method: 'ui/notifications/size-changed', params: { width: 640, height: 320.2 } })
    await nextTick()
    expect(iframe.style.height).toBe('321px')
    fromView({ jsonrpc: '2.0', method: 'ui/notifications/size-changed', params: { width: 640, height: 600 } })
    await nextTick()
    expect(iframe.style.height).toBe('600px')
    fromView({ jsonrpc: '2.0', method: 'ui/notifications/size-changed', params: { width: 640, height: 601 } })
    await nextTick()
    expect(iframe.style.height).toBe('600px')
  })

  it('answers with the theme, inline display, platform, locale and time zone', () => {
    document.documentElement.classList.add('dark')
    const { fromView, sent } = mountFrame()
    fromView(initialize)
    const context = (sent()[0] as { result: { hostContext: Record<string, unknown> } }).result.hostContext
    expect(context).toEqual(expect.objectContaining({
      theme: 'dark',
      displayMode: 'inline',
      availableDisplayModes: ['inline'],
      containerDimensions: { width: 0, maxHeight: 600 },
      locale: navigator.language,
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      platform: 'web',
    }))
  })

  it('says the view could not be loaded when it never initializes within the timeout of its page loading', async () => {
    vi.useFakeTimers()
    const { load } = mountFrame()
    vi.advanceTimersByTime(MCP_APP_LOAD_CAP_MS - MCP_APP_INIT_TIMEOUT_MS)
    load()
    vi.advanceTimersByTime(MCP_APP_INIT_TIMEOUT_MS - 1)
    await nextTick()
    expect(wrapper!.find('iframe').exists()).toBe(true)
    vi.advanceTimersByTime(1)
    await nextTick()
    expect(wrapper!.find('iframe').exists()).toBe(false)
    expect(wrapper!.find('[role="status"]').text()).toBe('The view could not be loaded.')
    expect(MCP_APP_INIT_TIMEOUT_MS).toBe(20_000)
  })

  it('waits for a page queued behind other requests, up to the cap from mount where nothing reports visibility', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('IntersectionObserver', undefined)
    mountFrame()
    vi.advanceTimersByTime(MCP_APP_LOAD_CAP_MS - 1)
    await nextTick()
    expect(wrapper!.find('[role="status"]').text()).toBe('Loading view…')
    vi.advanceTimersByTime(1)
    await nextTick()
    expect(wrapper!.find('[role="status"]').text()).toBe('The view could not be loaded.')
    expect(MCP_APP_LOAD_CAP_MS).toBe(60_000)
  })

  it('starts the cap only once a lazy frame comes into view', async () => {
    vi.useFakeTimers()
    const visibility = stubIntersectionObserver()
    mountFrame()
    vi.advanceTimersByTime(10 * MCP_APP_LOAD_CAP_MS)
    await nextTick()
    expect(wrapper!.find('[role="status"]').text()).toBe('Loading view…')
    visibility.show()
    expect(visibility.disconnected).toBe(true)
    vi.advanceTimersByTime(MCP_APP_LOAD_CAP_MS - 1)
    await nextTick()
    expect(wrapper!.find('[role="status"]').text()).toBe('Loading view…')
    vi.advanceTimersByTime(1)
    await nextTick()
    expect(wrapper!.find('[role="status"]').text()).toBe('The view could not be loaded.')
  })

  it('a message from another window neither answers nor counts as the view initializing', async () => {
    vi.useFakeTimers()
    const { fromView, sent, load } = mountFrame()
    load()
    fromView(initialize, window)
    await nextTick()
    expect(sent()).toEqual([])
    vi.advanceTimersByTime(MCP_APP_INIT_TIMEOUT_MS)
    await nextTick()
    expect(wrapper!.find('[role="status"]').text()).toBe('The view could not be loaded.')
  })

  it('a view that initializes in time is never declared unavailable', async () => {
    vi.useFakeTimers()
    const { fromView, load } = mountFrame()
    load()
    vi.advanceTimersByTime(MCP_APP_INIT_TIMEOUT_MS - 1)
    fromView(initialize)
    vi.advanceTimersByTime(MCP_APP_LOAD_CAP_MS)
    await nextTick()
    expect(wrapper!.find('iframe').exists()).toBe(true)
    expect(wrapper!.find('[role="status"]').exists()).toBe(false)
  })

  it('drops a view that navigates or reloads its frame, and no longer answers it', async () => {
    const { fromView, sent, load } = mountFrame()
    load()
    fromView(initialize)
    fromView(initialized)
    await nextTick()
    const before = sent().length
    load()
    await nextTick()
    expect(wrapper!.find('iframe').exists()).toBe(false)
    expect(wrapper!.find('[role="status"]').text()).toBe('The view could not be loaded.')
    fromView({ jsonrpc: '2.0', id: 9, method: 'ping' })
    wrapper!.unmount()
    wrapper = null
    expect(sent()).toHaveLength(before)
  })

  it('reports a change of width, even from a width of 0 at initialization', async () => {
    const resize = stubResizeObserver()
    const { fromView, sent } = mountFrame()
    fromView(initialize)
    fromView(initialized)
    const container = wrapper!.find('[data-testid="mcp-app-frame"]').element as HTMLElement
    expect(resize.observed).toEqual([container])
    Object.defineProperty(container, 'clientWidth', { configurable: true, value: 640 })
    resize.fire()
    resize.fire()
    expect(sent().slice(3)).toEqual([{
      jsonrpc: '2.0',
      method: 'ui/notifications/host-context-changed',
      params: { containerDimensions: { width: 640, maxHeight: 600 } },
    }])
  })

  it('sends a theme or width change made before the view initialized right after its tool result', async () => {
    const resize = stubResizeObserver()
    const { fromView, sent } = mountFrame()
    fromView(initialize)
    document.documentElement.classList.add('dark')
    await new Promise(resolve => setTimeout(resolve, 0))
    const container = wrapper!.find('[data-testid="mcp-app-frame"]').element as HTMLElement
    Object.defineProperty(container, 'clientWidth', { configurable: true, value: 640 })
    resize.fire()
    expect(sent()).toHaveLength(1)
    fromView(initialized)
    expect(sent().slice(1)).toEqual([
      { jsonrpc: '2.0', method: 'ui/notifications/tool-input', params: { arguments: CALL.arguments } },
      { jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: CALL.result },
      { jsonrpc: '2.0', method: 'ui/notifications/host-context-changed', params: expect.objectContaining({ theme: 'dark' }) },
      { jsonrpc: '2.0', method: 'ui/notifications/host-context-changed', params: { containerDimensions: { width: 640, maxHeight: 600 } } },
    ])
  })

  it('tells an initialized view when the theme changes', async () => {
    const { fromView, sent } = mountFrame()
    fromView(initialize)
    fromView(initialized)
    document.documentElement.classList.add('dark')
    await new Promise(resolve => setTimeout(resolve, 0))
    expect(sent().slice(3)).toEqual([{
      jsonrpc: '2.0',
      method: 'ui/notifications/host-context-changed',
      params: expect.objectContaining({ theme: 'dark' }),
    }])
  })

  it('sends ui/resource-teardown before removing an initialized view, and nothing for one that never initialized', () => {
    const first = mountFrame()
    first.fromView(initialize)
    first.fromView(initialized)
    wrapper!.unmount()
    wrapper = null
    expect(first.sent().at(-1)).toEqual(
      { jsonrpc: '2.0', id: 1, method: 'ui/resource-teardown', params: { reason: 'The chat removed this view.' } })

    const second = mountFrame()
    wrapper!.unmount()
    wrapper = null
    expect(second.sent()).toEqual([])
  })

  it('runs a view\'s tool call on the view\'s own server for the chat\'s conversation, and gives the view the result', async () => {
    const result = { content: [{ type: 'text', text: '4 results' }], structuredContent: { rows: [1, 2, 3, 4] }, isError: false }
    const bodies = serveViewCall(() => ({ result }))
    const { fromView, sent, click } = mountFrame(host(42))
    fromView(initialize)
    expect((sent()[0] as { result: { hostCapabilities: unknown } }).result.hostCapabilities).toEqual({ serverTools: {}, openLinks: {} })
    click()
    fromView(toolCall)
    await vi.waitFor(() => expect(sent()).toHaveLength(2))
    expect(bodies).toEqual([{ conversationId: 42, server: 'serpapi', tool: 'refresh', arguments: { page: 2 } }])
    expect(sent()[1]).toEqual({ jsonrpc: '2.0', id: 'c1', result })
  })

  it('gives the view JClaw\'s refusal, and its own words for a call that failed in transit', async () => {
    serveViewCall(() => ({ error: 'This server needs approval for each call, which a view cannot give.' }))
    const { fromView, sent, click } = mountFrame(host())
    click()
    fromView(toolCall)
    await vi.waitFor(() => expect(sent()).toHaveLength(1))
    expect(sent()[0]).toEqual({ jsonrpc: '2.0', id: 'c1',
      error: { code: -32000, message: 'This server needs approval for each call, which a view cannot give.' } })
    wrapper!.unmount()
    wrapper = null
    unregister.forEach(off => off())
    unregister = []

    vi.spyOn(console, 'error').mockImplementation(() => {})
    serveViewCall((event) => {
      setResponseStatus(event, 500)
      return { code: 'internal_error', message: 'upstream said: token sk-live-123 expired' }
    })
    const second = mountFrame(host())
    second.click()
    second.fromView(toolCall)
    await vi.waitFor(() => expect(second.sent()).toHaveLength(1))
    expect(second.sent()[0]).toEqual({ jsonrpc: '2.0', id: 'c1', error: { code: -32000, message: 'The tool call failed.' } })
  })

  it('sends nothing for a tool call before the conversation exists, or without a click just before', async () => {
    const bodies = serveViewCall(() => ({ result: {} }))
    const unsaved = mountFrame(host(null))
    unsaved.click()
    unsaved.fromView(toolCall)
    await vi.waitFor(() => expect(unsaved.sent()).toHaveLength(1))
    expect(unsaved.sent()[0]).toEqual({ jsonrpc: '2.0', id: 'c1', error: { code: -32000, message: 'The tool call failed.' } })
    wrapper!.unmount()
    wrapper = null

    const idle = mountFrame(host())
    idle.click()
    clicked(false)
    idle.fromView(toolCall)
    await flushMicrotasks()
    expect(idle.sent()).toEqual([{ jsonrpc: '2.0', id: 'c1', error: { code: -32000, message: 'This view can act only right after a click in it.' } }])
    expect(bodies).toEqual([])
  })

  it('hands a view\'s message to the chat to fill the composer', () => {
    const chat = host()
    const { fromView, sent, click } = mountFrame(chat)
    click()
    fromView({ jsonrpc: '2.0', id: 5, method: 'ui/message', params: { role: 'user', content: { type: 'text', text: 'Show page 2' } } })
    expect(chat.prefill.mock.calls).toEqual([['Show page 2']])
    expect(sent()).toEqual([{ jsonrpc: '2.0', id: 5, result: {} }])
  })

  it('opens a view\'s link in a new tab that cannot reach back to the chat', () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const { fromView, sent, click } = mountFrame()
    click()
    fromView({ jsonrpc: '2.0', id: 6, method: 'ui/open-link', params: { url: 'https://serpapi.com/search?q=cats' } })
    expect(open.mock.calls).toEqual([['https://serpapi.com/search?q=cats', '_blank', 'noopener,noreferrer']])
    expect(sent()).toEqual([{ jsonrpc: '2.0', id: 6, result: {} }])
  })

  it('outside the chat page offers only links: tool calls and messages stay -32601', () => {
    const bodies = serveViewCall(() => ({ result: {} }))
    const { fromView, sent, click } = mountFrame()
    fromView(initialize)
    click()
    fromView(toolCall)
    fromView({ jsonrpc: '2.0', id: 7, method: 'ui/message', params: { role: 'user', content: { type: 'text', text: 'hi' } } })
    expect((sent()[0] as { result: { hostCapabilities: unknown } }).result.hostCapabilities).toEqual({ openLinks: {} })
    expect(sent().slice(1)).toEqual([
      { jsonrpc: '2.0', id: 'c1', error: { code: -32601, message: 'Method not found: tools/call' } },
      { jsonrpc: '2.0', id: 7, error: { code: -32601, message: 'Method not found: ui/message' } },
    ])
    expect(bodies).toEqual([])
  })

  it('lets only the view that was clicked act, not another view on the page', async () => {
    const bodies = serveViewCall(() => ({ result: {} }))
    const first = mountFrame(host())
    const firstWrapper = wrapper!
    const second = mountFrame(host())
    try {
      first.click()
      second.fromView(toolCall)
      await flushMicrotasks()
      expect(second.sent()).toEqual([{ jsonrpc: '2.0', id: 'c1', error: NO_CLICK }])
      expect(bodies).toEqual([])
      first.fromView(toolCall)
      await vi.waitFor(() => expect(first.sent()).toHaveLength(1))
      expect(first.sent()[0]).toEqual({ jsonrpc: '2.0', id: 'c1', result: {} })
      expect(bodies).toHaveLength(1)
    }
    finally {
      firstWrapper.unmount()
    }
  })

  it('lets no view act on a key pressed in the composer', async () => {
    const bodies = serveViewCall(() => ({ result: {} }))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const chat = host()
    const { fromView, sent } = mountFrame(chat)
    const composer = document.createElement('textarea')
    document.body.appendChild(composer)
    try {
      composer.focus()
      clicked(true)
      fromView(toolCall)
      fromView({ jsonrpc: '2.0', id: 2, method: 'ui/message', params: { role: 'user', content: { type: 'text', text: 'hi' } } })
      fromView({ jsonrpc: '2.0', id: 3, method: 'ui/open-link', params: { url: 'https://serpapi.com' } })
      await flushMicrotasks()
      expect(sent()).toEqual(['c1', 2, 3].map(id => ({ jsonrpc: '2.0', id, error: NO_CLICK })))
      expect(bodies).toEqual([])
      expect(chat.prefill).not.toHaveBeenCalled()
      expect(open).not.toHaveBeenCalled()
    }
    finally {
      composer.remove()
    }
  })
})

const NO_CLICK = { code: -32000, message: 'This view can act only right after a click in it.' }

async function flushMicrotasks() {
  await new Promise(resolve => setTimeout(resolve, 0))
}
