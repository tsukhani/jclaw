import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import Chat from '~/pages/chat.vue'
import { serveConversation } from './conversation-endpoint'

// The SSE bus is mocked so a test delivers the server's events to the handlers the page registers.
const bus = vi.hoisted(() => ({
  handlers: [] as Array<{ type: string, handler: (data: unknown, type: string) => void }>,
  openHandlers: [] as Array<() => void>,
}))

vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => {
    const on = (type: string, handler: (data: unknown, type: string) => void) => {
      bus.handlers.push({ type, handler })
    }
    return {
      on,
      off: () => {},
      onEvent: on,
      onOpen: (handler: () => void) => {
        bus.openHandlers.push(handler)
      },
    }
  },
}))

function emitBus(type: string, data: unknown) {
  for (const entry of bus.handlers) {
    if (entry.type === type) entry.handler(data, type)
  }
}

enableAutoUnmount(afterEach)
// A page's last reads can land after the file's environment closes, which vitest reports as a teardown error.
afterAll(() => {
  for (const level of ['error', 'warn', 'log'] as const) vi.spyOn(console, level).mockImplementation(() => {})
})

interface Row { id: number, role: string, content: string, createdAt: string }

function row(id: number, role: string, content: string): Row {
  return { id, role, content, createdAt: `2026-11-06T10:00:0${id}Z` }
}

/** The busy flag GET /api/conversations/{id}/queue answers per conversation, and how often each was read. */
let busy: Record<number, boolean>
let queueReads: Record<number, number>

function serveQueue(convoId: number, answer?: () => Promise<{ busy: boolean, queueSize: number }>) {
  registerEndpoint(`/api/conversations/${convoId}/queue`, () => {
    queueReads[convoId] = (queueReads[convoId] ?? 0) + 1
    return answer ? answer() : { busy: busy[convoId] === true, queueSize: 0 }
  })
}

beforeEach(() => {
  bus.handlers.length = 0
  bus.openHandlers.length = 0
  busy = {}
  queueReads = {}
  clearNuxtData()
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
      enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]' },
    ],
  }))
  registerEndpoint('/api/conversations', () => [])
  registerEndpoint('/api/subagent-runs', () => [])
  registerEndpoint('/api/mcp-servers/elicitations', () => [])
})

afterEach(() => {
  vi.restoreAllMocks()
})

type ChatVm = { loadConversation: (id: number) => Promise<void>, backgroundBusy: boolean }

function vmOf(component: VueWrapper) {
  return component.vm as unknown as ChatVm
}

async function openConversation(convoId: number) {
  const component = await mountSuspended(Chat)
  await flushPromises()
  await vmOf(component).loadConversation(convoId)
  await flushPromises()
  return component
}

function backgroundLine(component: VueWrapper) {
  return component.find('[data-testid="background-turn-progress"]')
}

function announcer(component: VueWrapper) {
  return component.find('[data-testid="chat-progress-announcer"]')
}

describe('Chat page — a turn the page is not streaming', () => {
  it('shows Working… while such a turn holds the open conversation', async () => {
    serveConversation(5, [row(1, 'user', 'find the report'), row(2, 'assistant', 'Starting on it.')])
    busy[5] = true
    serveQueue(5)

    const component = await openConversation(5)

    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))
    const line = backgroundLine(component)
    expect(announcer(component).text()).toBe('Working…')
    expect(line.find('[role="status"]').exists()).toBe(false)
    expect(line.text()).toBe('Working…')
    expect(line.element.firstElementChild).toBe(line.find('img').element)
    expect(line.find('img').attributes('alt')).toBe('')
    expect(component.find('[data-testid="stream-progress"]').exists()).toBe(false)
  })

  it('shows nothing while the open conversation is idle, or when its queue cannot be read', async () => {
    serveConversation(5, [row(1, 'user', 'hello')])
    serveQueue(5)
    const idle = await openConversation(5)
    expect(queueReads[5]).toBe(1)
    expect(backgroundLine(idle).exists()).toBe(false)
    expect(announcer(idle).text()).toBe('')
    idle.unmount()

    serveConversation(6, [row(1, 'user', 'hello')])
    registerEndpoint('/api/conversations/6/queue', (event) => {
      setResponseStatus(event, 500)
      return { error: 'queue unavailable' }
    })
    const unreadable = await openConversation(6)
    expect(backgroundLine(unreadable).exists()).toBe(false)
  })

  it('renders a round the turn commits and, once the conversation goes idle, its reply', async () => {
    const rows = [row(1, 'user', 'find the report')]
    serveConversation(5, rows)
    busy[5] = true
    serveQueue(5)
    const component = await openConversation(5)
    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))

    rows.push(row(2, 'assistant', 'Halfway through the report.'))
    emitBus('conversation.updated', { conversationId: 5 })
    await vi.waitFor(() => expect(component.text()).toContain('Halfway through the report.'))
    expect(backgroundLine(component).exists()).toBe(true)

    rows.push(row(3, 'assistant', 'The report is ready.'))
    busy[5] = false
    emitBus('conversation.busy', { conversationId: 5, busy: false })

    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(false))
    expect(announcer(component).text()).toBe('')
    await vi.waitFor(() => expect(component.text()).toContain('The report is ready.'))
    expect(component.text().split('The report is ready.')).toHaveLength(2)
  })

  it('renders the reply of a turn that ended before any read saw it busy', async () => {
    const rows = [row(1, 'user', 'hello')]
    serveConversation(5, rows)
    serveQueue(5)
    const component = await openConversation(5)
    expect(backgroundLine(component).exists()).toBe(false)

    // A fast turn, or one whose only reply is an error row written outside the announcing sink.
    rows.push(row(2, 'assistant', 'No provider is configured for this agent.'))
    emitBus('conversation.busy', { conversationId: 5, busy: true })
    emitBus('conversation.busy', { conversationId: 5, busy: false })

    await vi.waitFor(() => expect(component.text()).toContain('No provider is configured for this agent.'))
    expect(component.text().split('No provider is configured for this agent.')).toHaveLength(2)
    expect(backgroundLine(component).exists()).toBe(false)
  })

  it('renders the rows committed while the event stream was down once it reconnects', async () => {
    const rows = [row(1, 'user', 'hello')]
    serveConversation(5, rows)
    serveQueue(5)
    const component = await openConversation(5)

    rows.push(row(2, 'assistant', 'Written during the outage.'))
    for (const handler of bus.openHandlers) handler()

    await vi.waitFor(() => expect(component.text()).toContain('Written during the outage.'))
    expect(queueReads[5]).toBe(2)
    expect(backgroundLine(component).exists()).toBe(false)
  })

  it('keeps Working… showing across a reconnect while the turn still runs', async () => {
    serveConversation(5, [row(1, 'user', 'hello')])
    busy[5] = true
    let held = false
    let release!: () => void
    const reconnectRead = new Promise<void>((resolve) => {
      release = resolve
    })
    serveQueue(5, async () => {
      if (held) await reconnectRead
      return { busy: busy[5] === true, queueSize: 0 }
    })
    const component = await openConversation(5)
    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))

    held = true
    for (const handler of bus.openHandlers) handler()
    await vi.waitFor(() => expect(queueReads[5]).toBe(2))
    expect(backgroundLine(component).exists()).toBe(true)

    release()
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(backgroundLine(component).exists()).toBe(true)
  })

  it('reads the queue again on a busy change, since the event alone may arrive out of order', async () => {
    serveConversation(5, [row(1, 'user', 'hello')])
    serveQueue(5)
    const component = await openConversation(5)

    busy[5] = true
    emitBus('conversation.busy', { conversationId: 5, busy: false })

    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))
    expect(queueReads[5]).toBe(2)
  })

  it('ignores a busy change in another conversation', async () => {
    serveConversation(5, [row(1, 'user', 'hello')])
    serveQueue(5)
    serveQueue(6)
    busy[6] = true
    const component = await openConversation(5)

    emitBus('conversation.busy', { conversationId: 6, busy: true })
    await flushPromises()

    expect(queueReads[5]).toBe(1)
    expect(queueReads[6]).toBeUndefined()
    expect(backgroundLine(component).exists()).toBe(false)
  })

  it('reads the queue again when the event stream reconnects', async () => {
    serveConversation(5, [row(1, 'user', 'hello')])
    serveQueue(5)
    const component = await openConversation(5)
    expect(backgroundLine(component).exists()).toBe(false)

    busy[5] = true
    for (const handler of bus.openHandlers) handler()

    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))
  })

  it('drops a queue read that lands after the operator switched conversations', async () => {
    serveConversation(5, [row(1, 'user', 'first conversation')])
    serveConversation(6, [row(1, 'user', 'second conversation')])
    let answer!: (status: { busy: boolean, queueSize: number }) => void
    serveQueue(5, () => new Promise((resolve) => {
      answer = resolve
    }))
    serveQueue(6)
    const component = await openConversation(5)
    await vi.waitFor(() => expect(answer).toBeTypeOf('function'))

    await vmOf(component).loadConversation(6)
    await flushPromises()
    answer({ busy: true, queueSize: 0 })
    for (let i = 0; i < 5; i++) await flushPromises()

    expect(component.text()).toContain('second conversation')
    expect(queueReads[6]).toBe(1)
    expect(vmOf(component).backgroundBusy).toBe(false)
    expect(backgroundLine(component).exists()).toBe(false)
  })
})

describe('Chat page — the tab streaming its own turn', () => {
  /** A stream the test feeds: frames now, the rest when `finish` runs. */
  function streamingTurn(convoId: number) {
    let controller!: ReadableStreamDefaultController<Uint8Array>
    const encoder = new TextEncoder()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(new ReadableStream<Uint8Array>({
      start(c) {
        controller = c
        c.enqueue(encoder.encode(`data: {"type":"init","conversationId":${convoId}}\n`))
        c.enqueue(encoder.encode('data: {"type":"token","content":"Hel"}\n'))
      },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
    return {
      finish() {
        controller.enqueue(encoder.encode('data: {"type":"complete","content":"Hello"}\n'))
        controller.close()
      },
    }
  }

  async function send(component: VueWrapper) {
    await component.find<HTMLTextAreaElement>('textarea').setValue('hello')
    await component.find('form').trigger('submit.prevent')
    await vi.waitFor(() => expect(component.find('[data-testid="stream-progress"]').exists()).toBe(true))
  }

  it('shows no Working… line during its turn or after it when nothing is queued', async () => {
    serveConversation(5, [row(1, 'user', 'earlier')])
    // The read after the stream is held, so the test sees the page before the answer lands.
    let releaseAfterStream!: () => void
    const afterStream = new Promise<void>((resolve) => {
      releaseAfterStream = resolve
    })
    serveQueue(5, async () => {
      if (queueReads[5] === 3) await afterStream
      return { busy: busy[5] === true, queueSize: 0 }
    })
    const turn = streamingTurn(5)
    const component = await openConversation(5)
    await send(component)

    busy[5] = true
    emitBus('conversation.busy', { conversationId: 5, busy: true })
    await vi.waitFor(() => expect(vmOf(component).backgroundBusy).toBe(true))
    expect(queueReads[5]).toBe(2)
    expect(backgroundLine(component).exists()).toBe(false)
    expect(announcer(component).text()).toBe('Generating…')

    // The release precedes the stream's complete frame.
    busy[5] = false
    turn.finish()
    await vi.waitFor(() => expect(component.find('[data-testid="stream-progress"]').exists()).toBe(false))
    await vi.waitFor(() => expect(queueReads[5]).toBe(3))
    expect(backgroundLine(component).exists()).toBe(false)

    releaseAfterStream()
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(vmOf(component).backgroundBusy).toBe(false)
    expect(backgroundLine(component).exists()).toBe(false)
  })

  it('shows Working… after its turn when a queued message runs on with no stream', async () => {
    serveConversation(5, [row(1, 'user', 'earlier')])
    serveQueue(5)
    const turn = streamingTurn(5)
    const component = await openConversation(5)
    await send(component)

    busy[5] = true
    turn.finish()

    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))
    expect(component.find('[data-testid="stream-progress"]').exists()).toBe(false)
    expect(announcer(component).text()).toBe('Working…')
  })
})
