import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { defineComponent, h, ref } from 'vue'
import Chat from '~/pages/chat.vue'
import { useChatBackgroundTurn, type UseChatBackgroundTurn } from '~/composables/useChatBackgroundTurn'
import { serveConversation } from './conversation-endpoint'
import { fakeConversationStreams, serveTurnStart, streamOn, type FakeEventSource } from './conversation-stream'

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
  fakeConversationStreams()
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
  vi.unstubAllGlobals()
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

describe('Chat page — a message another tab queues', () => {
  it('shows it as a Queued row, and counts it on Stop, as soon as the busy event the queueing publishes arrives', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    let status: Record<string, unknown> = { busy: true, queueSize: 0, stoppable: true, pending: [] }
    serveQueue(5, async () => structuredClone(status) as { busy: boolean, queueSize: number })
    const component = await openConversation(5)
    const stream = streamOn(5)
    stream.open()
    stream.send({ type: 'init', conversationId: 5 })
    const stop = () => component.find('[data-testid="chat-stop"]')
    await vi.waitFor(() => expect(stop().attributes('title')).toBe('Stop generating'))
    const reads = queueReads[5]!

    status = { ...status, queueSize: 1, pending: [{ text: 'sent from tab A', attachmentNames: [], position: 1 }] }
    emitBus('conversation.busy', { conversationId: 5, busy: true })

    await vi.waitFor(() => expect(stop().text()).toBe('Stop and drop 1 queued'))
    expect(queueReads[5]).toBe(reads + 1)
    expect(component.findAll('[data-testid="queued-message"]').map(r => r.find('.rounded-2xl').text()))
      .toEqual(['sent from tab A'])
  })
})

describe('Chat page — a send the server queues', () => {
  /** Sends `typed`, quoting `quote` when given, and lets a queue read list `listed` before the answer queues it. */
  async function readBeforeAnswer(typed: string, listed: string, quote?: string) {
    serveConversation(5, [row(1, 'user', 'long job')])
    let status: Record<string, unknown> = { busy: true, queueSize: 0, stoppable: true, pending: [] }
    serveQueue(5, async () => structuredClone(status) as { busy: boolean, queueSize: number })
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'queued', conversationId: 5, position: 1 }
    })
    const component = await openConversation(5)
    if (quote) {
      await component.find('[data-testid="reply-message"]').trigger('click')
      expect(component.find('[data-testid="reply-quote"]').text()).toContain(quote)
    }
    await component.find<HTMLTextAreaElement>('textarea').setValue(typed)
    await component.find('form').trigger('submit.prevent')

    status = { ...status, queueSize: 1, pending: [{ text: listed, attachmentNames: [], position: 1 }] }
    emitBus('conversation.busy', { conversationId: 5, busy: true })
    const rows = () => component.findAll('[data-testid="queued-message"]').map(r => r.find('.rounded-2xl').text())
    await vi.waitFor(() => expect(rows()).toHaveLength(1))
    answer()
    for (let i = 0; i < 5; i++) await flushPromises()
    return rows()
  }

  it('shows one Queued row when a queue read listed the message before the send\'s answer arrived', async () => {
    expect(await readBeforeAnswer('and then this', 'and then this')).toEqual(['and then this'])
  })

  it('shows one Queued row for a quoted reply a read listed with its quote before the answer arrived', async () => {
    const rows = await readBeforeAnswer('about that', '[Replying to your message]\n> long job\n\nabout that', 'long job')
    expect(rows).toEqual(['Replying to your messagelong jobabout that'])
  })
})

describe('the queue read\'s hold on a message its turn took', () => {
  async function mountQueue(streamUp: boolean) {
    const pending = [{ text: 'and then this', attachmentNames: [], position: 1 }]
    let status: Record<string, unknown> = { busy: true, queueSize: 1, stoppable: true, pending }
    serveQueue(5, async () => structuredClone(status) as { busy: boolean, queueSize: number })
    let api!: UseChatBackgroundTurn
    await mountSuspended(defineComponent({
      setup() {
        api = useChatBackgroundTurn({ selectedConvoId: ref(5), streaming: ref(false), fetchNewRows: async () => {},
          streamUp: ref(streamUp) })
        return () => h('div')
      },
    }))
    await vi.waitFor(() => expect(api.pending.value).toHaveLength(1))
    // Its turn takes it from the queue; the conversation stays busy with that turn.
    status = { ...status, queueSize: 0, pending: [] }
    return api
  }

  it('keeps the row while the stream is up, for the turn\'s init to replace with its saved row', async () => {
    const api = await mountQueue(true)
    await api.readQueue()
    expect(api.pending.value.map(p => p.text)).toEqual(['and then this'])
    await api.readQueue({ init: true })
    expect(api.pending.value).toEqual([])
  })

  it('drops the row at once while the stream is down, when no init will come', async () => {
    const api = await mountQueue(false)
    await api.readQueue()
    expect(api.pending.value).toEqual([])
  })
})

describe('Chat page — the turns the conversation stream reports', () => {
  /** Sends a message the server starts in conversation `convoId`, saving it to `rows` first, and opens its turn on the stream. */
  async function sendAndStart(component: VueWrapper, convoId: number, rows: Row[]): Promise<FakeEventSource> {
    serveTurnStart(() => {
      rows.push(row(2, 'user', 'hello'))
      return { status: 'started', conversationId: convoId }
    })
    await component.find<HTMLTextAreaElement>('textarea').setValue('hello')
    await component.find('form').trigger('submit.prevent')
    const stream = streamOn(convoId)
    stream.send({ type: 'init', conversationId: convoId })
    stream.send({ type: 'token', content: 'Hel' })
    await vi.waitFor(() => expect(component.find('[data-testid="stream-progress"]').exists()).toBe(true))
    return stream
  }

  it('shows no Working… line during a turn the stream reports, even on a busy event, nor after it when nothing is queued', async () => {
    const rows = [row(1, 'user', 'earlier')]
    serveConversation(5, rows)
    serveQueue(5)
    const component = await openConversation(5)
    const stream = await sendAndStart(component, 5, rows)

    busy[5] = true
    emitBus('conversation.busy', { conversationId: 5, busy: true })
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(vmOf(component).backgroundBusy).toBe(false)
    expect(backgroundLine(component).exists()).toBe(false)
    expect(announcer(component).text()).toBe('Generating…')

    busy[5] = false
    stream.send({ type: 'complete', content: 'Hello' })
    await vi.waitFor(() => expect(component.find('[data-testid="stream-progress"]').exists()).toBe(false))
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(vmOf(component).backgroundBusy).toBe(false)
    expect(backgroundLine(component).exists()).toBe(false)
    expect(announcer(component).text()).toBe('')
  })

  it('shows Working… from a turn\'s end until the queued message\'s init, which the stream then renders', async () => {
    const rows = [row(1, 'user', 'earlier')]
    serveConversation(5, rows)
    serveQueue(5)
    const component = await openConversation(5)
    const stream = await sendAndStart(component, 5, rows)

    busy[5] = true
    rows.push(row(3, 'assistant', 'Hello'))
    stream.send({ type: 'complete', content: 'Hello' })
    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))
    expect(component.find('[data-testid="stream-progress"]').exists()).toBe(false)
    expect(announcer(component).text()).toBe('Working…')

    rows.push(row(4, 'user', 'the queued follow-up'))
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(component.find('[data-testid="stream-progress"]').exists()).toBe(true))
    expect(backgroundLine(component).exists()).toBe(false)
    await vi.waitFor(() => expect(component.text()).toContain('the queued follow-up'))
    expect(component.text().split('hello')).toHaveLength(2)
  })
})

describe('Chat page — tool calls a turn the page is not streaming adds', () => {
  type ToolRow = { id: number, role: string, content: string | null, createdAt: string, reasoning?: string, toolCalls?: unknown[], toolResults?: string }

  const at = (id: number) => `2026-11-06T10:00:${String(id).padStart(2, '0')}Z`

  function said(id: number, role: string, content: string): ToolRow {
    return { id, role, content, createdAt: at(id) }
  }

  /** The assistant row a round saves first, carrying the call. */
  function callRow(id: number, callId: string): ToolRow {
    return { id, role: 'assistant', content: null, createdAt: at(id),
      toolCalls: [{ id: callId, type: 'function', function: { name: 'exec', arguments: `{"command":"run ${callId}"}` } }] }
  }

  /** The tool row that saves the call's result. */
  function resultRow(id: number, callId: string): ToolRow {
    return { id, role: 'tool', content: `${callId} done`, createdAt: at(id), toolResults: callId }
  }

  /** One saved round: the call, then its result. */
  function round(id: number, callId: string): ToolRow[] {
    return [callRow(id, callId), resultRow(id + 1, callId)]
  }

  /** The tool-calls blocks the page shows, oldest first, each as its header text and whether it is open. */
  function blocks(component: VueWrapper) {
    return component.findAll('button[title$="tool calls"]').map(b =>
      `${b.text().replace(/\s+/g, ' ').trim()}, ${b.attributes('title') === 'Collapse tool calls' ? 'open' : 'closed'}`)
  }

  /** The results of the calls shown expanded, oldest first. */
  function openResults(component: VueWrapper) {
    return component.findAll('pre').map(p => p.text())
  }

  async function busyConversation(rows: ToolRow[]) {
    serveConversation(5, rows)
    busy[5] = true
    serveQueue(5)
    const component = await openConversation(5)
    await vi.waitFor(() => expect(backgroundLine(component).exists()).toBe(true))
    return component
  }

  function commit(rows: ToolRow[], ...added: ToolRow[]) {
    rows.push(...added)
    emitBus('conversation.updated', { conversationId: 5 })
  }

  function finish(rows: ToolRow[], reply: ToolRow) {
    rows.push(reply)
    busy[5] = false
    emitBus('conversation.busy', { conversationId: 5, busy: false })
  }

  it('shows each call once, on one collapsed block that grows, and the block a reload shows once the reply lands', async () => {
    const rows = [said(1, 'user', 'run it three times')]
    const component = await busyConversation(rows)

    commit(rows, ...round(2, 'call_a'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['1 tool call, closed']))
    commit(rows, ...round(4, 'call_b'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['2 tool calls, closed']))
    commit(rows, ...round(6, 'call_c'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['3 tool calls, closed']))

    finish(rows, said(8, 'assistant', 'All three runs are done.'))
    await vi.waitFor(() => expect(component.text()).toContain('All three runs are done.'))
    expect(blocks(component)).toEqual(['3 tool calls, closed'])
    component.unmount()

    const reloaded = await openConversation(5)
    expect(blocks(reloaded)).toEqual(['3 tool calls, closed'])
  })

  it('keeps a block the operator opened open on the row that takes its calls, through a round and the reply', async () => {
    const rows = [said(1, 'user', 'run it twice'), ...round(2, 'call_a')]
    const component = await busyConversation(rows)
    await component.find('button[title="Expand tool calls"]').trigger('click')
    await vi.waitFor(() => expect(blocks(component)).toEqual(['1 tool call, open']))

    commit(rows, ...round(4, 'call_b'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['2 tool calls, open']))

    finish(rows, said(6, 'assistant', 'Both runs are done.'))
    await vi.waitFor(() => expect(component.text()).toContain('Both runs are done.'))
    expect(blocks(component)).toEqual(['2 tool calls, open'])
  })

  it('keeps each call open or closed as the operator left it when its block moves', async () => {
    const rows = [said(1, 'user', 'run it three times'), ...round(2, 'call_a'), ...round(4, 'call_b')]
    const component = await busyConversation(rows)
    await component.find('button[title="Expand tool calls"]').trigger('click')
    await vi.waitFor(() => expect(openResults(component)).toEqual(['call_b done']))
    await component.find('button[title="Expand this call"]').trigger('click')
    await vi.waitFor(() => expect(openResults(component)).toEqual(['call_a done', 'call_b done']))
    await component.findAll('button[title="Collapse this call"]')[1]!.trigger('click')
    await vi.waitFor(() => expect(openResults(component)).toEqual(['call_a done']))

    commit(rows, ...round(6, 'call_c'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['3 tool calls, open']))
    expect(openResults(component)).toEqual(['call_a done', 'call_c done'])
  })

  it('opens no new block for a block the operator opened on an earlier reply', async () => {
    const rows = [
      said(1, 'user', 'run it'), ...round(2, 'call_a'), said(4, 'assistant', 'The run is done.'),
      said(5, 'user', 'run it again'),
    ]
    const component = await busyConversation(rows)
    await component.find('button[title="Expand tool calls"]').trigger('click')
    await vi.waitFor(() => expect(blocks(component)).toEqual(['1 tool call, open']))

    commit(rows, ...round(6, 'call_b'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['1 tool call, open', '1 tool call, closed']))
  })

  it('shows a call\'s result when it commits after the call', async () => {
    const rows = [said(1, 'user', 'run it'), callRow(2, 'call_a')]
    const component = await busyConversation(rows)
    await component.find('button[title="Expand tool calls"]').trigger('click')
    await vi.waitFor(() => expect(blocks(component)).toEqual(['1 tool call, open']))
    expect(openResults(component)).toEqual([])

    commit(rows, resultRow(3, 'call_a'))
    await vi.waitFor(() => expect(openResults(component)).toEqual(['call_a done']))
    expect(blocks(component)).toEqual(['1 tool call, open'])
  })

  it('keeps a reasoning card the operator opened open across a merge', async () => {
    const rows = [said(1, 'user', 'run it'), { ...said(2, 'assistant', 'Starting on it.'), reasoning: 'Plan the run first.' }]
    const component = await busyConversation(rows)
    await component.find('button[title="Expand reasoning"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[data-reasoning-body]').exists()).toBe(true))

    commit(rows, ...round(3, 'call_a'))
    await vi.waitFor(() => expect(blocks(component)).toEqual(['1 tool call, closed']))
    expect(component.find('button[title="Collapse reasoning"]').exists()).toBe(true)
    expect(component.find('[data-reasoning-body]').text()).toBe('Plan the run first.')
  })
})
