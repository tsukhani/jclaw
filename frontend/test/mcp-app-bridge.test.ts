import { afterEach, describe, it, expect, vi } from 'vitest'
import { createMcpAppBridge, mcpAppViewUrl, type McpAppBridgeOptions, type McpAppHostContext, type McpAppToolReply } from '~/composables/useMcpAppBridge'
import type { McpAppCall } from '~/types/api'

const CALL: McpAppCall = {
  server: 'serpapi',
  tool: 'search_table',
  resourceUri: 'ui://prefab/tool/583d325b0e9c/renderer.html',
  arguments: { params: { q: 'cats' } },
  result: { content: [{ type: 'text', text: '3 results' }], structuredContent: { rows: [1, 2, 3] }, isError: false },
}
const CONTEXT: McpAppHostContext = { theme: 'dark', displayMode: 'inline', platform: 'web' }

const frames: HTMLIFrameElement[] = []

function setup(actions: Pick<McpAppBridgeOptions, 'callTool' | 'prefill' | 'openLink'> = {}) {
  const frame = document.createElement('iframe')
  document.body.appendChild(frame)
  frames.push(frame)
  const view = frame.contentWindow!
  const postMessage = vi.spyOn(view, 'postMessage').mockImplementation(() => {})
  const onReady = vi.fn()
  const onSize = vi.fn()
  const bridge = createMcpAppBridge({ view: () => view, call: () => CALL, hostContext: () => CONTEXT, onReady, onSize, ...actions })
  const from = (data: unknown, source: unknown = view) => bridge.handle({ data, source } as MessageEvent)
  const sent = () => postMessage.mock.calls.map(([message, target]) => ({ message, target }))
  /** A click in this view's frame: the page holds user activation and the frame has focus. */
  const click = () => {
    clicked(true)
    frame.focus()
  }
  return { bridge, from, sent, onReady, onSize, click }
}

/** A bridge given all three actions, with the tool call answering {@code reply}. */
function setupActions(reply: Promise<McpAppToolReply> = Promise.resolve({ result: { content: [], isError: false } })) {
  const callTool = vi.fn((_name: string, _args: Record<string, unknown>) => reply)
  const prefill = vi.fn()
  const openLink = vi.fn()
  return { ...setup({ callTool, prefill, openLink }), callTool, prefill, openLink }
}

/** Whether the page holds transient user activation, which a click in the view's frame gives it. */
function clicked(isActive: boolean) {
  Object.defineProperty(navigator, 'userActivation', { configurable: true, value: { isActive, hasBeenActive: isActive } })
}

afterEach(() => {
  Reflect.deleteProperty(navigator, 'userActivation')
  frames.splice(0).forEach(frame => frame.remove())
})

const NO_CLICK = { code: -32000, message: 'This view can act only right after a click in it.' }

const initialize = { jsonrpc: '2.0', id: 1, method: 'ui/initialize', params: { protocolVersion: '2026-01-26', appInfo: { name: 'Prefab', version: '0.20.2' }, appCapabilities: {} } }
const initialized = { jsonrpc: '2.0', method: 'ui/notifications/initialized' }

describe('MCP Apps host bridge', () => {
  it('answers ui/initialize with no host capability and the host context, to any origin the opaque frame has', () => {
    const { from, sent, onReady } = setup()
    from(initialize)
    expect(onReady).toHaveBeenCalledTimes(1)
    expect(sent()).toEqual([{
      target: '*',
      message: { jsonrpc: '2.0', id: 1, result: {
        protocolVersion: '2026-01-26',
        hostInfo: { name: 'JClaw', version: '2' },
        hostCapabilities: {},
        hostContext: CONTEXT,
      } },
    }])
  })

  it('ignores a message from any other window', () => {
    const { from, sent, onReady, onSize } = setup()
    const stranger = { postMessage: vi.fn() }
    from(initialize, stranger)
    from(initialized, stranger)
    from({ jsonrpc: '2.0', method: 'ui/notifications/size-changed', params: { height: 300 } }, stranger)
    from(initialize, null)
    expect(sent()).toEqual([])
    expect(onReady).not.toHaveBeenCalled()
    expect(onSize).not.toHaveBeenCalled()
  })

  it('sends nothing before initialized, then the tool input exactly once, then the tool result', () => {
    const { bridge, from, sent } = setup()
    bridge.contextChanged({ theme: 'light' })
    bridge.teardown()
    from(initialize)
    expect(sent().map(s => s.message)).toEqual([expect.objectContaining({ id: 1 })])
    from(initialized)
    from(initialized)
    expect(sent().slice(1).map(s => s.message)).toEqual([
      { jsonrpc: '2.0', method: 'ui/notifications/tool-input', params: { arguments: { params: { q: 'cats' } } } },
      { jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: CALL.result },
    ])
  })

  it('ignores initialized from a view that never sent ui/initialize', () => {
    const { bridge, from, sent } = setup()
    from(initialized)
    expect(sent()).toEqual([])
    expect(bridge.contextChanged({ theme: 'light' })).toBe(false)
    from(initialize)
    from(initialized)
    expect(sent().slice(1).map(s => s.message)).toEqual([
      { jsonrpc: '2.0', method: 'ui/notifications/tool-input', params: { arguments: { params: { q: 'cats' } } } },
      { jsonrpc: '2.0', method: 'ui/notifications/tool-result', params: CALL.result },
    ])
    expect(bridge.contextChanged({ theme: 'light' })).toBe(true)
  })

  it('refuses every request it does not offer with -32601 and runs nothing', () => {
    const { from, sent, onReady } = setup()
    const refused = ['tools/call', 'resources/read', 'ui/message', 'ui/open-link', 'ui/update-model-context', 'ui/download-file', 'sampling/createMessage']
    refused.forEach((method, i) => from({ jsonrpc: '2.0', id: `r${i}`, method, params: {} }))
    expect(sent().map(s => s.message)).toEqual(refused.map((method, i) => (
      { jsonrpc: '2.0', id: `r${i}`, error: { code: -32601, message: `Method not found: ${method}` } })))
    expect(onReady).not.toHaveBeenCalled()
  })

  it('answers ping, and a display-mode request always with inline', () => {
    const { from, sent } = setup()
    from({ jsonrpc: '2.0', id: 7, method: 'ping' })
    from({ jsonrpc: '2.0', id: 8, method: 'ui/request-display-mode', params: { mode: 'fullscreen' } })
    expect(sent().map(s => s.message)).toEqual([
      { jsonrpc: '2.0', id: 7, result: {} },
      { jsonrpc: '2.0', id: 8, result: { mode: 'inline' } },
    ])
  })

  it('passes a reported height on and ignores one that is not a size', () => {
    const { from, onSize } = setup()
    for (const height of [0, 312.5, -1, Number.NaN, '300', null]) {
      from({ jsonrpc: '2.0', method: 'ui/notifications/size-changed', params: { width: 640, height } })
    }
    from({ jsonrpc: '2.0', method: 'ui/notifications/size-changed' })
    expect(onSize.mock.calls).toEqual([[0], [312.5]])
  })

  it('ignores what is not a JSON-RPC 2.0 request or notification', () => {
    const { from, sent, onReady } = setup()
    from('ui/initialize')
    from({ ...initialize, jsonrpc: '1.0' })
    from({ jsonrpc: '2.0', id: 1, result: {} })
    from(null)
    expect(sent()).toEqual([])
    expect(onReady).not.toHaveBeenCalled()
  })

  it('tells an initialized view of a context change and of its teardown', () => {
    const { bridge, from, sent } = setup()
    from(initialize)
    from(initialized)
    bridge.contextChanged({ theme: 'light' })
    bridge.teardown()
    expect(sent().slice(3).map(s => s.message)).toEqual([
      { jsonrpc: '2.0', method: 'ui/notifications/host-context-changed', params: { theme: 'light' } },
      { jsonrpc: '2.0', id: 1, method: 'ui/resource-teardown', params: { reason: 'The chat removed this view.' } },
    ])
  })

  it('reads the page through JClaw, naming the server and the page', () => {
    expect(mcpAppViewUrl({ ...CALL, server: 'serp api' }))
      .toBe('/api/mcp-servers/view?server=serp%20api&uri=ui%3A%2F%2Fprefab%2Ftool%2F583d325b0e9c%2Frenderer.html')
  })

  it('declares exactly serverTools and openLinks when it can call tools, fill the composer and open links', () => {
    const { from, sent } = setupActions()
    from(initialize)
    expect((sent()[0]!.message as { result: { hostCapabilities: unknown } }).result.hostCapabilities)
      .toEqual({ serverTools: {}, openLinks: {} })
  })

  it('runs a tool call right after a click through the host and gives the view the tool\'s result', async () => {
    const result = { content: [{ type: 'text', text: '4 results' }], structuredContent: { rows: [1, 2, 3, 4] }, isError: false }
    const { from, sent, callTool, click } = setupActions(Promise.resolve({ result }))
    click()
    from({ jsonrpc: '2.0', id: 'c1', method: 'tools/call', params: { name: 'refresh', arguments: { page: 2 } } })
    await vi.waitFor(() => expect(sent()).toHaveLength(1))
    from({ jsonrpc: '2.0', id: 'c2', method: 'tools/call', params: { name: 'search' } })
    await vi.waitFor(() => expect(sent()).toHaveLength(2))
    expect(callTool.mock.calls).toEqual([['refresh', { page: 2 }], ['search', {}]])
    expect(sent().map(s => s.message)).toEqual([
      { jsonrpc: '2.0', id: 'c1', result },
      { jsonrpc: '2.0', id: 'c2', result },
    ])
  })

  it('passes JClaw\'s refusal of a tool call on as -32000, and a call that fails as its own message', async () => {
    const refused = setupActions(Promise.resolve({ error: 'This tool cannot be called from its view.' }))
    const failed = setupActions(Promise.reject(new Error('socket hang up')))
    const malformed = setupActions(Promise.resolve({} as McpAppToolReply))
    for (const bridge of [refused, failed, malformed]) {
      bridge.click()
      bridge.from({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'refresh' } })
    }
    await vi.waitFor(() => expect([refused, failed, malformed].map(b => b.sent().length)).toEqual([1, 1, 1]))
    expect(refused.sent()[0]!.message).toEqual(
      { jsonrpc: '2.0', id: 3, error: { code: -32000, message: 'This tool cannot be called from its view.' } })
    expect(failed.sent()[0]!.message).toEqual({ jsonrpc: '2.0', id: 3, error: { code: -32000, message: 'The tool call failed.' } })
    expect(malformed.sent()[0]!.message).toEqual({ jsonrpc: '2.0', id: 3, error: { code: -32000, message: 'The tool call failed.' } })
  })

  it('refuses a tool call that names no tool or whose arguments are not an object, and calls nothing', () => {
    const { from, sent, callTool, click } = setupActions()
    click()
    const bad = [undefined, {}, { name: 7 }, { name: 'refresh', arguments: [1] }, { name: 'refresh', arguments: 'page=2' }, { name: 'refresh', arguments: null }]
    bad.forEach((params, i) => from({ jsonrpc: '2.0', id: i, method: 'tools/call', params }))
    expect(sent().map(s => s.message)).toEqual(bad.map((_, i) => (
      { jsonrpc: '2.0', id: i, error: { code: -32000, message: 'Invalid tool call format' } })))
    expect(callTool).not.toHaveBeenCalled()
  })

  it('does none of the three actions without a click just before, and says why', async () => {
    const { from, sent, callTool, prefill, openLink, click } = setupActions()
    click()
    clicked(false)
    from({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'refresh' } })
    from({ jsonrpc: '2.0', id: 2, method: 'ui/message', params: { role: 'user', content: { type: 'text', text: 'hi' } } })
    from({ jsonrpc: '2.0', id: 3, method: 'ui/open-link', params: { url: 'https://serpapi.com' } })
    Reflect.deleteProperty(navigator, 'userActivation')
    from({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'refresh' } })
    await Promise.resolve()
    expect(sent().map(s => s.message)).toEqual([1, 2, 3, 4].map(id => ({ jsonrpc: '2.0', id, error: NO_CLICK })))
    expect(callTool).not.toHaveBeenCalled()
    expect(prefill).not.toHaveBeenCalled()
    expect(openLink).not.toHaveBeenCalled()
  })

  it('fills the composer with a message right after a click, as one text block or the SDK\'s list of them', () => {
    const { from, sent, prefill, click } = setupActions()
    click()
    from({ jsonrpc: '2.0', id: 1, method: 'ui/message', params: { role: 'user', content: { type: 'text', text: 'Show page 2' } } })
    from({ jsonrpc: '2.0', id: 2, method: 'ui/message', params: { role: 'user',
      content: [{ type: 'text', text: 'Compare these:' }, { type: 'text', text: 'Cats and dogs' }] } })
    expect(prefill.mock.calls).toEqual([['Show page 2'], ['Compare these:\n\nCats and dogs']])
    expect(sent().map(s => s.message)).toEqual([{ jsonrpc: '2.0', id: 1, result: {} }, { jsonrpc: '2.0', id: 2, result: {} }])
  })

  it('refuses a message that is not the user\'s text, and fills nothing', () => {
    const { from, sent, prefill, click } = setupActions()
    click()
    const bad = [
      undefined,
      { content: { type: 'text', text: 'hi' } },
      { role: 'assistant', content: { type: 'text', text: 'hi' } },
      { role: 'user', content: { type: 'image', data: 'AA==', mimeType: 'image/png' } },
      { role: 'user', content: [{ type: 'text', text: 'hi' }, { type: 'image', data: 'AA==' }] },
      { role: 'user', content: [] },
      { role: 'user', content: { type: 'text' } },
      { role: 'user', content: { type: 'text', text: '  \n ' } },
      { role: 'user', content: 'hi' },
    ]
    bad.forEach((params, i) => from({ jsonrpc: '2.0', id: i, method: 'ui/message', params }))
    expect(sent().map(s => s.message)).toEqual(bad.map((_, i) => (
      { jsonrpc: '2.0', id: i, error: { code: -32000, message: 'Invalid message format' } })))
    expect(prefill).not.toHaveBeenCalled()
  })

  it('opens an http or https link right after a click, and refuses any other', () => {
    const { from, sent, openLink, click } = setupActions()
    click()
    from({ jsonrpc: '2.0', id: 1, method: 'ui/open-link', params: { url: 'https://serpapi.com/search?q=cats' } })
    from({ jsonrpc: '2.0', id: 2, method: 'ui/open-link', params: { url: 'http://example.com' } })
    const bad = ['javascript:alert(1)', 'data:text/html,<p>x</p>', 'ftp://example.com/f', '/api/status', 'not a url', 7, undefined]
    bad.forEach((url, i) => from({ jsonrpc: '2.0', id: `b${i}`, method: 'ui/open-link', params: { url } }))
    expect(openLink.mock.calls).toEqual([['https://serpapi.com/search?q=cats'], ['http://example.com/']])
    expect(sent().map(s => s.message)).toEqual([
      { jsonrpc: '2.0', id: 1, result: {} },
      { jsonrpc: '2.0', id: 2, result: {} },
      ...bad.map((_, i) => ({ jsonrpc: '2.0', id: `b${i}`, error: { code: -32000, message: 'Invalid URL' } })),
    ])
  })

  it('still refuses every other request with -32601 when it offers the three actions', () => {
    const { from, sent, click } = setupActions()
    click()
    const refused = ['resources/read', 'ui/update-model-context', 'notifications/message', 'ui/download-file', 'sampling/createMessage']
    refused.forEach((method, i) => from({ jsonrpc: '2.0', id: i, method, params: {} }))
    expect(sent().map(s => s.message)).toEqual(refused.map((method, i) => (
      { jsonrpc: '2.0', id: i, error: { code: -32601, message: `Method not found: ${method}` } })))
  })

  it('ignores an action from any other window, even right after a click', async () => {
    const { from, sent, callTool, prefill, openLink, click } = setupActions()
    click()
    const stranger = { postMessage: vi.fn() }
    from({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { name: 'refresh' } }, stranger)
    from({ jsonrpc: '2.0', id: 2, method: 'ui/message', params: { role: 'user', content: { type: 'text', text: 'hi' } } }, stranger)
    from({ jsonrpc: '2.0', id: 3, method: 'ui/open-link', params: { url: 'https://serpapi.com' } }, window)
    await Promise.resolve()
    expect(sent()).toEqual([])
    expect(stranger.postMessage).not.toHaveBeenCalled()
    expect([callTool, prefill, openLink].map(f => f.mock.calls.length)).toEqual([0, 0, 0])
  })

  it('refuses a tool call while the view\'s last one is still running, and still answers that one', async () => {
    let finish: (reply: McpAppToolReply) => void = () => {}
    const running = new Promise<McpAppToolReply>((resolve) => {
      finish = resolve
    })
    const { from, sent, callTool, click } = setupActions(running)
    click()
    from({ jsonrpc: '2.0', id: 'c1', method: 'tools/call', params: { name: 'refresh' } })
    from({ jsonrpc: '2.0', id: 'c2', method: 'tools/call', params: { name: 'refresh' } })
    expect(sent().map(s => s.message)).toEqual([
      { jsonrpc: '2.0', id: 'c2', error: { code: -32000, message: 'This view is still running its last tool call.' } },
    ])
    finish({ result: { content: [], isError: false } })
    await vi.waitFor(() => expect(sent()).toHaveLength(2))
    expect(sent()[1]!.message).toEqual({ jsonrpc: '2.0', id: 'c1', result: { content: [], isError: false } })
    from({ jsonrpc: '2.0', id: 'c3', method: 'tools/call', params: { name: 'refresh' } })
    await vi.waitFor(() => expect(sent()).toHaveLength(3))
    expect(sent()[2]!.message).toEqual({ jsonrpc: '2.0', id: 'c3', result: { content: [], isError: false } })
    expect(callTool).toHaveBeenCalledTimes(2)
  })

  it('takes the next tool call once a failed one has ended', async () => {
    const { from, sent, callTool, click } = setupActions(Promise.reject(new Error('socket hang up')))
    click()
    from({ jsonrpc: '2.0', id: 'c1', method: 'tools/call', params: { name: 'refresh' } })
    await vi.waitFor(() => expect(sent()).toHaveLength(1))
    from({ jsonrpc: '2.0', id: 'c2', method: 'tools/call', params: { name: 'refresh' } })
    await vi.waitFor(() => expect(sent()).toHaveLength(2))
    expect(sent().map(s => s.message)).toEqual(['c1', 'c2'].map(id => (
      { jsonrpc: '2.0', id, error: { code: -32000, message: 'The tool call failed.' } })))
    expect(callTool).toHaveBeenCalledTimes(2)
  })
})
