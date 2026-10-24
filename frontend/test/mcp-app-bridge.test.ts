import { describe, it, expect, vi } from 'vitest'
import { createMcpAppBridge, mcpAppViewUrl, type McpAppHostContext } from '~/composables/useMcpAppBridge'
import type { McpAppCall } from '~/types/api'

const CALL: McpAppCall = {
  server: 'serpapi',
  tool: 'search_table',
  resourceUri: 'ui://prefab/tool/583d325b0e9c/renderer.html',
  arguments: { params: { q: 'cats' } },
  result: { content: [{ type: 'text', text: '3 results' }], structuredContent: { rows: [1, 2, 3] }, isError: false },
}
const CONTEXT: McpAppHostContext = { theme: 'dark', displayMode: 'inline', platform: 'web' }

function setup() {
  const view = { postMessage: vi.fn() } as unknown as Window
  const onReady = vi.fn()
  const onSize = vi.fn()
  const bridge = createMcpAppBridge({ view: () => view, call: () => CALL, hostContext: () => CONTEXT, onReady, onSize })
  const from = (data: unknown, source: unknown = view) => bridge.handle({ data, source } as MessageEvent)
  const sent = () => (view.postMessage as ReturnType<typeof vi.fn>).mock.calls.map(([message, target]) => ({ message, target }))
  return { bridge, from, sent, onReady, onSize }
}

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
        hostInfo: { name: 'JClaw', version: '1' },
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
})
