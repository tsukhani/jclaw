import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises, type VueWrapper } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'
import type { ConversationQueueStatus, QueuedMessageView } from '~/composables/useChatBackgroundTurn'
import type { Message } from '~/types/api'
import { serveConversation } from './conversation-endpoint'
import { fakeConversationStreams, FakeEventSource, openStreamsOn, serveTurnStart, streamOn } from './conversation-stream'

/**
 * JF-202: the chat page renders every turn of the open conversation from its stream, the tab's own included — one
 * test per row of the spec's I/O matrix, driving the page as the operator does.
 */

enableAutoUnmount(afterEach)
// A page's last reads can land after the file's environment closes, which vitest reports as a teardown error.
afterAll(() => {
  for (const level of ['error', 'warn', 'log'] as const) vi.spyOn(console, level).mockImplementation(() => {})
})

interface Row { id: number, role: string, content: string | null, createdAt: string, messageKind?: string, toolCalls?: unknown[], toolResults?: string }

function row(id: number, role: string, content: string, over: Partial<Row> = {}): Row {
  return { id, role, content, createdAt: `2026-11-07T10:00:${String(id).padStart(2, '0')}Z`, ...over }
}

/** What GET /api/conversations/{id}/queue answers, per conversation, as the test sets it. */
let queues: Record<number, ConversationQueueStatus>
let stops: number[]

function serveQueue(convoId: number, status: Partial<ConversationQueueStatus> = {}) {
  queues[convoId] = { busy: false, queueSize: 0, stoppable: false, pending: [], ...status }
  registerEndpoint(`/api/conversations/${convoId}/queue`, () => structuredClone(queues[convoId]!))
}

function setQueue(convoId: number, status: Partial<ConversationQueueStatus>) {
  queues[convoId] = { ...queues[convoId]!, ...status }
}

/** As the server stops: drop the queue, release the conversation, and end the turn on its stream, then answer. */
function serveStop(convoId: number, stopped = true) {
  registerEndpoint(`/api/conversations/${convoId}/stop`, {
    method: 'POST',
    handler: () => {
      stops.push(convoId)
      if (stopped) {
        setQueue(convoId, { busy: false, stoppable: false, pending: [], queueSize: 0 })
        for (const stream of openStreamsOn(convoId)) stream.send({ type: 'stopped' })
      }
      return { stopped }
    },
  })
}

function conversationEntry(id: number, channelType = 'web') {
  return { id, agentId: 1, agentName: 'main', channelType, peerId: 'admin', messageCount: 1, preview: 'demo',
    createdAt: '2026-11-07T10:00:00Z', updatedAt: '2026-11-07T10:00:00Z' }
}

beforeEach(() => {
  fakeConversationStreams()
  queues = {}
  stops = []
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
  registerEndpoint('/api/conversations', () => [conversationEntry(5)])
  registerEndpoint('/api/subagent-runs', () => [])
  registerEndpoint('/api/mcp-servers/elicitations', () => [])
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

type ChatVm = {
  loadConversation: (id: number) => Promise<void>
  pollForAnnounce: () => Promise<void>
  messages: Message[]
}

const vmOf = (component: VueWrapper) => component.vm as unknown as ChatVm

/** The chat page with conversation `convoId` open and its stream connected. */
async function openConversation(convoId: number): Promise<{ component: VueWrapper, stream: FakeEventSource }> {
  const component = await mountSuspended(Chat)
  await flushPromises()
  await vmOf(component).loadConversation(convoId)
  await flushPromises()
  const stream = openStreamsOn(convoId).at(-1)!
  stream.open()
  await flushPromises()
  return { component, stream }
}

async function type(component: VueWrapper, text: string) {
  await component.find<HTMLTextAreaElement>('textarea').setValue(text)
  await component.find('form').trigger('submit.prevent')
  await flushPromises()
}

const textarea = (component: VueWrapper) => component.find<HTMLTextAreaElement>('textarea').element
const sendButton = (component: VueWrapper) => component.find('button[type="submit"]')
const stopButton = (component: VueWrapper) => component.find('[data-testid="chat-stop"]')
const progressLine = (component: VueWrapper) => component.find('[data-testid="stream-progress"]')
/** Each Queued row as its attachment names, its text and its label. */
const queuedRows = (component: VueWrapper) => component.findAll('[data-testid="queued-message"]').map(r =>
  [...r.findAll('span[title]').map(a => a.text()), r.find('.rounded-2xl').text(), r.find('[data-testid="queued-label"]').text()].join(' | '))
const shown = (component: VueWrapper) => vmOf(component).messages
  .filter(m => m.role !== 'tool' && (m.content || m.toolCalls?.length))
  .map(m => `${m.role}: ${m.content ?? ''}`)

const announcer = (component: VueWrapper) => component.find('[data-testid="chat-progress-announcer"]')

/** How often `text` shows on the page. */
const times = (component: VueWrapper, text: string) => component.text().split(text).length - 1

describe('Chat page — every turn renders from the conversation stream (JF-202)', () => {
  it('own turn: renders the reply live and keeps one reply row once the turn has saved it', async () => {
    const rows = [row(1, 'user', 'earlier')]
    serveConversation(5, rows)
    serveQueue(5)
    const bodies = serveTurnStart(() => {
      rows.push(row(2, 'user', 'hello there'))
      setQueue(5, { busy: true, stoppable: true })
      return { status: 'started', conversationId: 5 }
    })
    const { component, stream } = await openConversation(5)

    await type(component, 'hello there')
    expect(bodies).toEqual([{ agentId: 1, conversationId: 5, message: 'hello there', attachments: [] }])
    stream.send({ type: 'init', conversationId: 5, startedAt: new Date().toISOString() })
    stream.send({ type: 'token', content: 'Hi ' })
    await vi.waitFor(() => expect(component.text()).toContain('Hi'))
    expect(progressLine(component).exists()).toBe(true)
    await vi.waitFor(() => expect(stopButton(component).exists()).toBe(true))

    rows.push(row(3, 'assistant', 'Hi back'))
    setQueue(5, { busy: false, stoppable: false })
    stream.send({ type: 'token', content: 'back' })
    stream.send({ type: 'complete', content: 'Hi back' })
    await vi.waitFor(() => expect(vmOf(component).messages.map(m => m.id)).toEqual([1, 2, 3]))

    expect(shown(component)).toEqual(['user: earlier', 'user: hello there', 'assistant: Hi back'])
    expect(times(component, 'Hi back')).toBe(1)
    expect(progressLine(component).exists()).toBe(false)
    expect(stopButton(component).exists()).toBe(false)
  })

  it('new chat: the answer\'s id selects the conversation and opens its stream, from which the turn renders', async () => {
    const rows: Row[] = []
    serveConversation(42, rows)
    serveQueue(42, { busy: true, stoppable: true })
    serveTurnStart(() => {
      rows.push(row(1, 'user', 'first question'))
      return { status: 'started', conversationId: 42 }
    })
    const component = await mountSuspended(Chat)
    await flushPromises()

    await type(component, 'first question')
    await vi.waitFor(() => expect(useRouter().currentRoute.value.query.conversation).toBe('42'))
    const stream = streamOn(42)
    expect(stream.url).toBe('/api/conversations/42/stream')
    stream.open()
    stream.send({ type: 'init', conversationId: 42 })
    stream.send({ type: 'token', content: 'An answer' })
    await vi.waitFor(() => expect(component.text()).toContain('An answer'))
    expect(times(component, 'first question')).toBe(1)
  })

  it('new chat: a turn already over when the stream opens loads its rows', async () => {
    const rows: Row[] = []
    serveConversation(42, rows)
    serveQueue(42)
    serveTurnStart(() => {
      rows.push(row(1, 'user', 'quick one'), row(2, 'assistant', 'Quick answer.'))
      return { status: 'started', conversationId: 42 }
    })
    const component = await mountSuspended(Chat)
    await flushPromises()

    await type(component, 'quick one')
    await vi.waitFor(() => expect(openStreamsOn(42)).toHaveLength(1))
    streamOn(42).open()

    await vi.waitFor(() => expect(shown(component)).toEqual(['user: quick one', 'assistant: Quick answer.']))
    expect(progressLine(component).exists()).toBe(false)
  })

  it('background turn: a drained message renders its progress line and tool calls live, without Stop, and its reply at the end', async () => {
    const rows = [row(1, 'user', 'earlier'), row(2, 'user', 'the drained message')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: false })
    const { component, stream } = await openConversation(5)

    stream.send({ type: 'init', conversationId: 5, startedAt: new Date(Date.now() - 65_000).toISOString() })
    stream.send({ type: 'status', content: 'Using tool: web_fetch' })
    stream.send({ type: 'tool_call', id: 'tc1', name: 'web_fetch', arguments: '{"url":"https://example.com"}', resultText: 'page text' })
    await vi.waitFor(() => expect(component.find('button[title$="tool calls"]').exists()).toBe(true))
    expect(progressLine(component).exists()).toBe(true)
    // Timed from the turn's start, not from when this page saw it.
    expect(progressLine(component).find('[aria-hidden="true"]').text()).toMatch(/^1:0\d$/)
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(stopButton(component).exists()).toBe(false)
    expect(sendButton(component).exists()).toBe(true)

    rows.push(row(3, 'assistant', 'Fetched the page.'))
    setQueue(5, { busy: false })
    stream.send({ type: 'complete', content: 'Fetched the page.' })
    await vi.waitFor(() => expect(vmOf(component).messages.map(m => m.id)).toEqual([1, 2, 3]))
    expect(times(component, 'Fetched the page.')).toBe(1)
  })

  it('channel turn: a Telegram conversation opened in Chat renders live, without Stop', async () => {
    serveConversation(8, [row(1, 'user', 'from telegram')])
    serveQueue(8, { busy: true, stoppable: false })
    const { component, stream } = await openConversation(8)

    stream.send({ type: 'init', conversationId: 8 })
    stream.send({ type: 'token', content: 'Replying on Telegram' })
    await vi.waitFor(() => expect(component.text()).toContain('Replying on Telegram'))
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(progressLine(component).exists()).toBe(true)
    expect(stopButton(component).exists()).toBe(false)
  })

  it('other tab: a turn another tab sends on this conversation renders here too', async () => {
    const rows = [row(1, 'user', 'earlier')]
    serveConversation(5, rows)
    serveQueue(5)
    const bodies = serveTurnStart(() => {
      rows.push(row(2, 'user', 'sent from tab B'))
      return { status: 'started', conversationId: 5 }
    })
    const tabA = await openConversation(5)
    const tabB = await openConversation(5)
    const [streamA, streamB] = openStreamsOn(5)

    await type(tabB.component, 'sent from tab B')
    for (const stream of [streamA!, streamB!]) {
      stream.send({ type: 'init', conversationId: 5 })
      stream.send({ type: 'token', content: 'Both tabs see this' })
    }

    for (const tab of [tabA, tabB]) {
      await vi.waitFor(() => expect(tab.component.text()).toContain('Both tabs see this'))
      await vi.waitFor(() => expect(times(tab.component, 'sent from tab B')).toBe(1))
    }
    expect(bodies).toHaveLength(1)
  })

  it('reload mid-turn: a new page shows the reply so far, then live', async () => {
    serveConversation(5, [row(1, 'user', 'write a long story')])
    serveQueue(5, { busy: true, stoppable: true })
    const { component, stream } = await openConversation(5)
    expect(stream.url).toBe('/api/conversations/5/stream')

    // The server replays the turn to a subscriber that names no last id: its init, then its text merged.
    stream.send({ type: 'init', conversationId: 5 }, '100')
    stream.send({ type: 'token', content: 'Once upon a time, ' }, '150')
    await vi.waitFor(() => expect(component.text()).toContain('Once upon a time,'))
    stream.send({ type: 'token', content: 'there was a parser.' }, '151')
    await vi.waitFor(() => expect(component.text()).toContain('Once upon a time, there was a parser.'))
    await vi.waitFor(() => expect(stopButton(component).exists()).toBe(true))
  })

  it('reload mid-turn, resync: the rows reload and the turn rebuilds from the frames after it', async () => {
    const rows = [row(1, 'user', 'write a long story')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 }, '100')
    stream.send({ type: 'token', content: 'Stale text' }, '101')
    await vi.waitFor(() => expect(component.text()).toContain('Stale text'))

    rows.push(row(2, 'system', 'A task reported in.', { messageKind: 'task_run_result' }))
    stream.send({ type: 'init', conversationId: 5 }, '100')
    stream.send({ type: 'resync' }, '120')
    stream.send({ type: 'token', content: 'The whole story so far' }, '130')

    await vi.waitFor(() => expect(vmOf(component).messages.map(m => m.id ?? 'bubble')).toEqual([1, 2, 'bubble']))
    await vi.waitFor(() => expect(component.text()).toContain('The whole story so far'))
    expect(component.text()).not.toContain('Stale text')
  })

  it('send mid-turn: the box stays enabled, Enter sends without stopping the turn, and the message waits as Queued until it drains', async () => {
    const rows = [row(1, 'user', 'long job')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true })
    serveStop(5)
    const bodies = serveTurnStart(() => {
      setQueue(5, { pending: [{ text: 'and one more thing', attachmentNames: [], position: 1 }], queueSize: 1 })
      return { status: 'queued', conversationId: 5, position: 1 }
    })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    stream.send({ type: 'token', content: 'Working on the long job' })
    await vi.waitFor(() => expect(progressLine(component).exists()).toBe(true))

    expect(textarea(component).disabled).toBe(false)
    expect(sendButton(component).exists()).toBe(true)
    await vi.waitFor(() => expect(stopButton(component).exists()).toBe(true))
    await component.find<HTMLTextAreaElement>('textarea').setValue('and one more thing')
    await component.find('textarea').trigger('keydown.enter')

    await vi.waitFor(() => expect(queuedRows(component)).toEqual(['and one more thing | Queued, position 1']))
    expect(announcer(component).text()).toBe('Queued, position 1')
    expect(bodies.map(b => b.message)).toEqual(['and one more thing'])
    expect(stops).toEqual([])
    expect(progressLine(component).exists()).toBe(true)
    expect(shown(component)).toEqual(['user: long job', 'assistant: Working on the long job'])

    // It drains: the first turn ends, and the message's own turn starts with its row saved.
    rows.push(row(2, 'assistant', 'Working on the long job'), row(3, 'user', 'and one more thing'))
    stream.send({ type: 'complete', content: 'Working on the long job' })
    setQueue(5, { pending: [], queueSize: 0, stoppable: false })
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(queuedRows(component)).toEqual([]))
    expect(times(component, 'and one more thing')).toBe(1)
    expect(vmOf(component).messages.filter(m => m.role === 'user').map(m => m.content)).toEqual(['long job', 'and one more thing'])
    // The drained message's turn replaces where the send went with what the turn is doing.
    expect(announcer(component).text()).toBe('Generating…')
  })

  it('clears the announcement of a queued send when the operator switches conversations', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveConversation(6, [row(1, 'user', 'the other one')])
    // Busy with a turn this page has not seen open, so no bubble's end clears the announcement.
    serveQueue(5, { busy: true, stoppable: true })
    serveQueue(6)
    serveTurnStart(() => ({ status: 'queued', conversationId: 5, position: 1 }))
    const { component } = await openConversation(5)
    await type(component, 'second')
    await vi.waitFor(() => expect(announcer(component).text()).toBe('Queued, position 1'))

    await vmOf(component).loadConversation(6)
    await flushPromises()

    expect(announcer(component).text()).toBe('')
  })

  it('shows none of a conversation\'s Queued rows after switching to one that queues nothing', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveConversation(6, [row(1, 'user', 'the other one')])
    serveQueue(5, { busy: true, stoppable: true, queueSize: 2, pending: [
      { text: 'second', attachmentNames: [], position: 1 },
      { text: 'third', attachmentNames: [], position: 2 },
    ] })
    // 6's queue reads land once its stream is up, where a shorter list is otherwise held for an init.
    let answerReads!: () => void
    const readsHeld = new Promise<void>((resolve) => {
      answerReads = resolve
    })
    registerEndpoint('/api/conversations/6/queue', async () => {
      await readsHeld
      return { busy: true, queueSize: 0, stoppable: false, pending: [] }
    })
    const { component } = await openConversation(5)
    await vi.waitFor(() => expect(queuedRows(component)).toHaveLength(2))

    await vmOf(component).loadConversation(6)
    await flushPromises()
    streamOn(6).open()
    answerReads()
    for (let i = 0; i < 5; i++) await flushPromises()

    expect(queuedRows(component)).toEqual([])
  })

  /** Holds the first send's answer (conversation 42) and answers every later send as started in conversation 43. */
  function holdFirstNewChatSend() {
    serveConversation(42, [])
    serveConversation(43, [])
    serveQueue(42)
    serveQueue(43)
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    const bodies = serveTurnStart(async (body) => {
      if (body.message !== 'first question') return { status: 'started', conversationId: 43 }
      await answered
      return { status: 'started', conversationId: 42 }
    })
    return { bodies, answer }
  }

  const sentTo = (bodies: Array<Record<string, unknown>>) => bodies.map(b => [b.message, b.agentId, b.conversationId])

  it('places nothing from a New Chat send whose answer lands after New Chat was clicked again, and sends the next one anew', async () => {
    const { bodies, answer } = holdFirstNewChatSend()
    const component = await mountSuspended(Chat)
    await flushPromises()
    await type(component, 'first question')

    await component.find('button[title="New conversation"]').trigger('click')
    await type(component, 'second question')
    await vi.waitFor(() => expect(sentTo(bodies)).toEqual([['first question', 1, null], ['second question', 1, null]]))
    answer()
    for (let i = 0; i < 5; i++) await flushPromises()

    await vi.waitFor(() => expect(useRouter().currentRoute.value.query.conversation).toBe('43'))
    expect(openStreamsOn(42)).toEqual([])
    expect(shown(component)).toEqual(['user: second question'])
  })

  it('places nothing from a New Chat send whose answer lands after the operator picked another agent, and sends the next one as that agent', async () => {
    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
        enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
      { id: 2, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
        enabled: true, isMain: false, thinkingMode: null, providerConfigured: true },
    ])
    const { bodies, answer } = holdFirstNewChatSend()
    const component = await mountSuspended(Chat)
    await flushPromises()
    await type(component, 'first question')

    await component.find('select').setValue('2')
    await flushPromises()
    await type(component, 'second question')
    await vi.waitFor(() => expect(sentTo(bodies)).toEqual([['first question', 1, null], ['second question', 2, null]]))
    answer()
    for (let i = 0; i < 5; i++) await flushPromises()

    await vi.waitFor(() => expect(useRouter().currentRoute.value.query.conversation).toBe('43'))
    expect(openStreamsOn(42)).toEqual([])
    expect(shown(component)).toEqual(['user: second question'])
  })

  it('sends a second New Chat message as the agent it was typed under, though the operator picked another before the first answer', async () => {
    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
        enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
      { id: 2, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
        enabled: true, isMain: false, thinkingMode: null, providerConfigured: true },
    ])
    const { bodies, answer } = holdFirstNewChatSend()
    const component = await mountSuspended(Chat)
    await flushPromises()
    await type(component, 'first question')
    await type(component, 'second question')

    await component.find('select').setValue('2')
    await flushPromises()
    answer()

    await vi.waitFor(() => expect(sentTo(bodies)).toEqual([['first question', 1, null], ['second question', 1, 42]]))
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(shown(component)).toEqual([])
  })

  it('shows why a second New Chat message was refused, after the first one\'s /new opened its conversation', async () => {
    serveQueue(5)
    serveConversation(5, [row(1, 'assistant', 'What would you like to discuss?')])
    let answerFirst!: () => void
    const firstHeld = new Promise<void>((resolve) => {
      answerFirst = resolve
    })
    const bodies: Array<Record<string, unknown>> = []
    registerEndpoint('/api/chat/turn', {
      method: 'POST',
      handler: async (event) => {
        const { readBody, setResponseStatus } = await import('h3')
        const body = await readBody<Record<string, unknown>>(event)
        bodies.push(body)
        if (body.message === '/new') {
          await firstHeld
          return { status: 'command', conversationId: 5, content: 'What would you like to discuss?', saved: true }
        }
        setResponseStatus(event, 500)
        return { code: 'internal_error', message: 'The turn could not start.' }
      },
    })
    const component = await mountSuspended(Chat)
    await flushPromises()
    await type(component, '/new')
    await type(component, 'second question')

    answerFirst()

    await vi.waitFor(() => expect(component.text()).toContain('Error: The turn could not start.'))
    expect(sentTo(bodies)).toEqual([['/new', 1, null], ['second question', 1, 5]])
    expect(shown(component)).toEqual(['assistant: What would you like to discuss?', 'user: second question',
      'assistant: Error: The turn could not start.'])
  })

  it('shows why a New Chat /new conversation failed to load, then its saved reply once, from its stream and rows', async () => {
    // The load's reads fail, retry included; a merge's read, after a cursor, finds the saved reply.
    registerEndpoint('/api/conversations/5/messages', async (event) => {
      const { getQuery, setResponseStatus } = await import('h3')
      if (getQuery(event).after != null) return [row(1, 'assistant', 'What would you like to discuss?')]
      setResponseStatus(event, 500)
      return { code: 'internal_error', message: 'The conversation could not load.' }
    })
    serveQueue(5)
    serveTurnStart(() => ({ status: 'command', conversationId: 5, content: 'What would you like to discuss?', saved: true }))
    const component = await mountSuspended(Chat)
    await flushPromises()

    await type(component, '/new')

    await vi.waitFor(() => expect(shown(component)).toEqual(['user: /new', 'assistant: Error: The conversation could not load.',
      'assistant: What would you like to discuss?']))
    expect(times(component, 'What would you like to discuss?')).toBe(1)
    expect(openStreamsOn(5)).toHaveLength(1)
  })

  const PICKABLE = '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false},'
    + '{"id":"glm-5","name":"GLM 5","supportsThinking":false}]'

  /** The config's one provider offering `models`; the agent's own is kimi-k2.5. */
  function serveModels(models = PICKABLE) {
    registerEndpoint('/api/config', () => ({
      entries: [
        { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
        { key: 'provider.ollama-cloud.models', value: models },
      ],
    }))
  }

  type Pick = { of: 'model' | 'thinking', modelProvider: string | null, modelId: string | null, thinkingMode: string | null }
  const modelPick = (modelId: string | null): Pick =>
    ({ of: 'model', modelProvider: modelId && 'ollama-cloud', modelId, thinkingMode: null })
  const thinkingPick = (thinkingMode: string | null): Pick => ({ of: 'thinking', modelProvider: null, modelId: null, thinkingMode })

  /** Answers each command `picks` names with its pick, once `held` resolves, and any other message as started in 42. */
  function servePicks(picks: Record<string, Pick>, held: Promise<void> = Promise.resolve()) {
    serveConversation(42, [])
    serveQueue(42)
    return serveTurnStart(async (body) => {
      const pick = picks[body.message as string]
      if (!pick) return { status: 'started', conversationId: 42 }
      await held
      return { status: 'command', conversationId: null, content: 'Picked.', saved: false, pick }
    })
  }

  /** Each message sent, with the picks it carried. */
  const carried = (bodies: Array<Record<string, unknown>>) =>
    bodies.map(b => [b.message, b.modelProvider ?? null, b.modelId ?? null, b.thinkingMode ?? null])

  async function typeInNewConversation(...messages: string[]) {
    const component = await mountSuspended(Chat)
    await flushPromises()
    for (const message of messages) await type(component, message)
    return component
  }

  it('sends a message typed before a New conversation /model answer with the model it picked', async () => {
    serveModels()
    let answer!: () => void
    const held = new Promise<void>((resolve) => {
      answer = resolve
    })
    const bodies = servePicks({ '/model ollama-cloud/glm-5': modelPick('glm-5') }, held)
    await typeInNewConversation('/model ollama-cloud/glm-5', 'hello')

    answer()

    await vi.waitFor(() => expect(carried(bodies)).toEqual([['/model ollama-cloud/glm-5', null, null, null],
      ['hello', 'ollama-cloud', 'glm-5', null]]))
  })

  it('sends every message typed behind a New conversation command to the one conversation the first of them makes', async () => {
    let answer!: () => void
    const held = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveConversation(42, [])
    serveQueue(42)
    const bodies = serveTurnStart(async (body) => {
      if (body.message !== '/help') return { status: 'started', conversationId: 42 }
      await held
      return { status: 'command', conversationId: null, content: 'Commands.', saved: false }
    })
    await typeInNewConversation('/help', 'first', 'second')

    answer()

    await vi.waitFor(() => expect(sentTo(bodies)).toEqual([['/help', 1, null], ['first', 1, null], ['second', 1, 42]]))
  })

  it('clears a New conversation\'s model pick on /model reset, so the first message carries none', async () => {
    serveModels()
    const bodies = servePicks({ '/model ollama-cloud/glm-5': modelPick('glm-5'), '/model reset': modelPick(null) })
    const component = await typeInNewConversation('/model ollama-cloud/glm-5')
    const picker = () => component.findComponent({ name: 'ChatModelCombobox' }).props('modelKey')
    await vi.waitFor(() => expect(picker()).toBe('ollama-cloud::glm-5'))

    await type(component, '/model reset')
    await vi.waitFor(() => expect(picker()).toBe('ollama-cloud::kimi-k2.5'))
    await type(component, 'hello')

    await vi.waitFor(() => expect(carried(bodies).at(-1)).toEqual(['hello', null, null, null]))
  })

  it('holds /think off in a New conversation as the Think pill would, and the first message carries it', async () => {
    serveModels('[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":true,"thinkingLevels":["low","high"]}]')
    const bodies = servePicks({ '/think off': thinkingPick('off') })

    await typeInNewConversation('/think off', 'hello')

    await vi.waitFor(() => expect(carried(bodies)).toEqual([['/think off', null, null, null], ['hello', null, null, 'off']]))
  })

  it('holds a /think level in a New conversation, which the Think pill turns off and back on, and the first message carries it', async () => {
    serveModels('[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":true,"thinkingLevels":["low","high"]}]')
    const bodies = servePicks({ '/think high': thinkingPick('high') })

    const component = await typeInNewConversation('/think high')
    const pill = () => component.find('button[title^="Thinking "]')
    await vi.waitFor(() => expect(pill().attributes('title')).toBe('Thinking on — click to turn off, or hover to pick a level'))
    await pill().trigger('click')
    await vi.waitFor(() => expect(pill().attributes('title')).toBe('Thinking off — click to turn on'))
    await pill().trigger('click')
    await type(component, 'hello')

    await vi.waitFor(() => expect(carried(bodies)).toEqual([['/think high', null, null, null], ['hello', null, null, 'high']]))
  })

  it('holds a /think level the server accepted though the page\'s model list says the model does not think', async () => {
    serveModels()
    const bodies = servePicks({ '/think high': thinkingPick('high') })

    await typeInNewConversation('/think high', 'hello')

    await vi.waitFor(() => expect(carried(bodies)).toEqual([['/think high', null, null, null], ['hello', null, null, 'high']]))
  })

  it('turns thinking off when /model reset leaves a level the agent\'s own model does not offer', async () => {
    serveModels('[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false},'
      + '{"id":"glm-5","name":"GLM 5","supportsThinking":true,"thinkingLevels":["low","high"]}]')
    const bodies = servePicks({ '/model ollama-cloud/glm-5': modelPick('glm-5'), '/think high': thinkingPick('high'),
      '/model reset': modelPick(null) })

    await typeInNewConversation('/model ollama-cloud/glm-5', '/think high', '/model reset', 'hello')

    await vi.waitFor(() => expect(carried(bodies).at(-1)).toEqual(['hello', null, null, 'off']))
  })

  it('places nothing from a message waiting behind a New conversation command once the operator opened another, nor makes the next New conversation wait on it', async () => {
    serveConversation(5, [row(1, 'user', 'earlier')])
    serveQueue(5)
    serveConversation(43, [])
    serveQueue(43)
    serveConversation(44, [])
    serveQueue(44)
    let answerHelp!: () => void
    const helpHeld = new Promise<void>((resolve) => {
      answerHelp = resolve
    })
    let answerFirst!: () => void
    const firstHeld = new Promise<void>((resolve) => {
      answerFirst = resolve
    })
    const bodies = serveTurnStart(async (body) => {
      if (body.message === '/help') {
        await helpHeld
        return { status: 'command', conversationId: null, content: 'Commands.', saved: false }
      }
      if (body.message === 'first') {
        await firstHeld
        return { status: 'started', conversationId: 43 }
      }
      return { status: 'started', conversationId: 44 }
    })
    const component = await typeInNewConversation('/help', 'first')

    await vmOf(component).loadConversation(5)
    await vi.waitFor(() => expect(useRouter().currentRoute.value.query.conversation).toBe('5'))
    await component.find('button[title="New conversation"]').trigger('click')
    await vi.waitFor(() => expect(useRouter().currentRoute.value.query.conversation).toBeUndefined())
    answerHelp()
    await vi.waitFor(() => expect(sentTo(bodies)).toEqual([['/help', 1, null], ['first', 1, null]]))
    await type(component, 'later')
    answerFirst()

    await vi.waitFor(() => expect(sentTo(bodies)).toEqual([['/help', 1, null], ['first', 1, null], ['later', 1, null]]))
    expect(shown(component)).toEqual(['user: later'])
  })

  it('clears a New conversation\'s thinking pick on /think reset, so the first message carries none', async () => {
    serveModels('[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":true,"thinkingLevels":["low","high"]}]')
    const bodies = servePicks({ '/think off': thinkingPick('off'), '/think reset': thinkingPick(null) })

    await typeInNewConversation('/think off', '/think reset', 'hello')

    await vi.waitFor(() => expect(carried(bodies).at(-1)).toEqual(['hello', null, null, null]))
  })

  it('pairs no saved row with a send still unanswered, so a queued answer removes only its own row', async () => {
    const rows = [row(1, 'user', 'long job')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true })
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      setQueue(5, { queueSize: 1, pending: [{ text: 'mine', attachmentNames: [], position: 1 }] })
      return { status: 'queued', conversationId: 5, position: 1 }
    })
    const { component } = await openConversation(5)
    await type(component, 'mine')

    // Another tab's message is saved meanwhile, and a merge brings it in.
    rows.push(row(2, 'user', 'from another tab'))
    await vmOf(component).pollForAnnounce()
    await vi.waitFor(() => expect(component.text()).toContain('from another tab'))
    answer()

    await vi.waitFor(() => expect(queuedRows(component)).toEqual(['mine | Queued, position 1']))
    expect(shown(component)).toEqual(['user: long job', 'user: from another tab'])
  })

  it('shows a saved message with a waiting send\'s text once that send is answered as queued', async () => {
    const rows = [row(1, 'user', 'long job')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true })
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      setQueue(5, { queueSize: 1, pending: [{ text: 'again', attachmentNames: [], position: 1 }] })
      return { status: 'queued', conversationId: 5, position: 1 }
    })
    const { component } = await openConversation(5)
    await type(component, 'again')

    // Another tab sent the same words first; until this send is answered, that row may be its saved copy.
    rows.push(row(2, 'user', 'again'))
    await vmOf(component).pollForAnnounce()
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(shown(component)).toEqual(['user: long job', 'user: again'])
    answer()

    await vi.waitFor(() => expect(queuedRows(component)).toEqual(['again | Queued, position 1']))
    await vi.waitFor(() => expect(vmOf(component).messages.map(m => m.id)).toEqual([1, 2]))
    expect(shown(component)).toEqual(['user: long job', 'user: again'])
  })

  it('shows a saved command reply typed mid-turn once, where the server saved it, a later round after it', async () => {
    const rows = [row(1, 'user', 'long job')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true })
    serveTurnStart(() => {
      rows.push(row(2, 'assistant', 'Model set to kimi-k2.5.'))
      return { status: 'command', conversationId: 5, content: 'Model set to kimi-k2.5.', saved: true }
    })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    stream.send({ type: 'token', content: 'Working' })
    await vi.waitFor(() => expect(component.text()).toContain('Working'))

    await type(component, '/model kimi-k2.5')
    await vi.waitFor(() => expect(times(component, 'Model set to kimi-k2.5.')).toBe(1))
    rows.push(row(3, 'assistant', 'A round of the running turn.', {
      toolCalls: [{ id: 'tc1', type: 'function', function: { name: 'exec', arguments: '{}' } }] }))
    rows.push(row(4, 'tool', 'ok', { toolResults: 'tc1' }))
    stream.send({ type: 'complete', content: 'Working, done.' })

    await vi.waitFor(() => expect(vmOf(component).messages.filter(m => m.role !== 'tool').map(m => m.id ?? m.content))
      .toEqual([1, '/model kimi-k2.5', 2, 3]))
    expect(times(component, 'Model set to kimi-k2.5.')).toBe(1)
  })

  it('keeps a Queued row its turn took from the queue until that turn\'s init brings its saved row', async () => {
    const rows = [row(1, 'user', 'long job')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true, queueSize: 1,
      pending: [{ text: 'and then this', attachmentNames: [], position: 1 }] })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(queuedRows(component)).toEqual(['and then this | Queued, position 1']))

    // The first turn ends and hands the conversation to the queued message, which leaves the queue first.
    setQueue(5, { pending: [], queueSize: 0, stoppable: false })
    rows.push(row(2, 'assistant', 'Done with the long job.'))
    stream.send({ type: 'complete', content: 'Done with the long job.' })
    await vi.waitFor(() => expect(progressLine(component).exists()).toBe(false))
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(queuedRows(component)).toEqual(['and then this | Queued, position 1'])

    rows.push(row(3, 'user', 'and then this'))
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(queuedRows(component)).toEqual([]))
    expect(times(component, 'and then this')).toBe(1)
  })

  it('places nothing from a send whose answer lands after the operator opened another conversation', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveConversation(6, [row(1, 'user', 'the other conversation')])
    serveQueue(5, { busy: true, stoppable: true })
    serveQueue(6)
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'queued', conversationId: 5, position: 1 }
    })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    await type(component, 'sent from 5')

    // The answer lands while conversation 6 is still loading.
    let loaded!: () => void
    const loading = new Promise<void>((resolve) => {
      loaded = resolve
    })
    registerEndpoint('/api/conversations/6/messages', async () => {
      await loading
      return [row(1, 'user', 'the other conversation')]
    })
    const switching = vmOf(component).loadConversation(6)
    await flushPromises()
    answer()
    for (let i = 0; i < 5; i++) await flushPromises()
    loaded()
    await switching
    for (let i = 0; i < 5; i++) await flushPromises()

    expect(queuedRows(component)).toEqual([])
    expect(shown(component)).toEqual(['user: the other conversation'])
  })

  it('typed /stop: on a stoppable turn the turn ends stopped and the queue empties, and its reply never pairs', async () => {
    const rows = [row(1, 'user', 'long job')]
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true, queueSize: 1,
      pending: [{ text: 'second', attachmentNames: [], position: 1 }] })
    serveTurnStart(() => {
      // The stop commits its marker, then ends the turn on the stream, then answers.
      setQueue(5, { busy: false, stoppable: false, pending: [], queueSize: 0 })
      rows.push(row(2, 'assistant', 'Half an answer'))
      for (const open of openStreamsOn(5)) open.send({ type: 'stopped' })
      return { status: 'command', conversationId: 5, content: 'Stopped.', saved: false }
    })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    stream.send({ type: 'token', content: 'Half an answer' })
    await vi.waitFor(() => expect(stopButton(component).text()).toBe('Stop and drop 1 queued'))

    await type(component, '/stop')

    await vi.waitFor(() => expect(queuedRows(component)).toEqual([]))
    expect(stopButton(component).exists()).toBe(false)
    expect(progressLine(component).exists()).toBe(false)
    expect(component.text()).toContain('(stopped)')
    // The stopped bubble takes the saved row's id; the command and its reply stay where they were sent, unpaired.
    await vi.waitFor(() => expect(vmOf(component).messages.map(m => m.id ?? m.content)).toEqual([1, 2, '/stop', 'Stopped.']))
  })

  it('typed /stop: a turn chat cannot stop yet runs on, and an idle conversation says there is nothing to stop', async () => {
    serveConversation(5, [row(1, 'user', 'drained job')])
    serveQueue(5, { busy: true, stoppable: false })
    serveTurnStart(() => queues[5]!.busy
      ? { status: 'command', conversationId: 5, content: 'This turn can\'t be stopped from chat yet.', saved: false }
      : { status: 'command', conversationId: 5, content: 'Nothing to stop.', saved: false })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(progressLine(component).exists()).toBe(true))

    await type(component, '/stop')
    await vi.waitFor(() => expect(component.text()).toContain('This turn can\'t be stopped from chat yet.'))
    expect(progressLine(component).exists()).toBe(true)

    setQueue(5, { busy: false })
    stream.send({ type: 'complete', content: 'Finished anyway.' })
    await type(component, '/stop')
    await vi.waitFor(() => expect(component.text()).toContain('Nothing to stop.'))
  })

  it('subagent result waiting: Stop counts the result it would drop, with no Queued row for it', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveQueue(5, { busy: true, stoppable: true, queueSize: 1, pending: [] })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })

    await vi.waitFor(() => expect(stopButton(component).text()).toBe('Stop and drop 1 queued'))
    expect(stopButton(component).attributes('aria-label')).toBe('Stop and drop 1 queued')
    expect(queuedRows(component)).toEqual([])
  })

  it('shows a queued quoted reply\'s quote as a quote, not the raw block the server folded into it', async () => {
    serveConversation(5, [row(1, 'user', 'first message')])
    serveQueue(5, { busy: true, stoppable: true, queueSize: 1, pending: [
      { text: '[Replying to your message]\n> first message\n\nabout that', attachmentNames: [], position: 1 },
    ] })
    const { component } = await openConversation(5)

    await vi.waitFor(() => expect(component.findAll('[data-testid="queued-message"]')).toHaveLength(1))
    const queued = component.find('[data-testid="queued-message"]')
    expect(queued.find('[data-testid="message-quote"]').text()).toBe('Replying to your messagefirst message')
    expect(queued.text()).not.toContain('[Replying to')
    expect(queued.find('[data-testid="queued-label"]').text()).toBe('Queued, position 1')
  })

  it('loads earlier rows during an open turn, keeping its bubble last and streaming', async () => {
    const rows = Array.from({ length: 150 }, (_, i) => row(i + 1, i % 2 ? 'assistant' : 'user', `message ${i + 1}`))
    for (const r of rows) r.createdAt = `2026-11-07T10:${String(Math.floor(r.id / 60)).padStart(2, '0')}:${String(r.id % 60).padStart(2, '0')}Z`
    serveConversation(5, rows)
    serveQueue(5, { busy: true, stoppable: true })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    stream.send({ type: 'token', content: 'Still writing' })
    await vi.waitFor(() => expect(component.text()).toContain('Still writing'))
    const before = vmOf(component).messages.length

    await component.find('[data-testid="chat-load-older"]').trigger('click')

    await vi.waitFor(() => expect(vmOf(component).messages.length).toBeGreaterThan(before))
    expect(vmOf(component).messages.at(-1)!.content).toBe('Still writing')
    expect(vmOf(component).messages[0]!.id).toBe(1)
    expect(progressLine(component).exists()).toBe(true)
    stream.send({ type: 'token', content: ', and more' })
    await vi.waitFor(() => expect(component.text()).toContain('Still writing, and more'))
  })

  it('leaves a reader who scrolled up during a turn where they are while its frames arrive', async () => {
    // jsdom has neither: the page's scroll tracking needs a ResizeObserver, and frames run when the test says.
    vi.stubGlobal('ResizeObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    let frames: FrameRequestCallback[] = []
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    const runFrames = () => {
      const due = frames
      frames = []
      for (const cb of due) cb(0)
    }
    serveConversation(5, [row(1, 'user', 'write a long story')])
    serveQueue(5, { busy: true, stoppable: true })
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    stream.send({ type: 'token', content: 'Once upon a time' })
    await vi.waitFor(() => expect(component.text()).toContain('Once upon a time'))
    runFrames()

    const list = component.find('[data-testid="chat-messages-scroll"]').element as HTMLElement
    for (const [key, value] of Object.entries({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })) {
      Object.defineProperty(list, key, { configurable: true, writable: true, value })
    }
    list.dispatchEvent(new Event('scroll'))
    stream.send({ type: 'reasoning', content: 'and then' })
    stream.send({ type: 'token', content: ', there was a parser.' })
    stream.send({ type: 'tool_call', id: 'tc1', name: 'exec', arguments: '{}', resultText: 'ok' })
    await flushPromises()
    runFrames()

    expect(list.scrollTop).toBe(300)
  })

  it('drops a Queued row the queue no longer lists once the stream gives up, when no init will come', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveQueue(5, { busy: true, stoppable: true, queueSize: 1,
      pending: [{ text: 'and then this', attachmentNames: [], position: 1 }] })
    const { component, stream } = await openConversation(5)
    await vi.waitFor(() => expect(queuedRows(component)).toEqual(['and then this | Queued, position 1']))

    // Its turn takes it from the queue while the stream is failing; the turn's init will never arrive here.
    setQueue(5, { queueSize: 0, pending: [] })
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      stream.fail()
      for (const delay of [5, 10, 20, 40, 60, 60, 60, 60, 60, 60]) {
        await vi.advanceTimersByTimeAsync(delay * 1000)
        streamOn(5).fail()
      }
    }
    finally {
      vi.useRealTimers()
    }

    await vi.waitFor(() => expect(queuedRows(component)).toEqual([]))
    expect(openStreamsOn(5)).toEqual([])
  })

  it('queued, reloaded: a page opened while messages wait shows them as Queued rows, with their places', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    const pending: QueuedMessageView[] = [
      { text: 'second', attachmentNames: [], position: 1 },
      { text: 'third', attachmentNames: ['notes.pdf'], position: 2 },
    ]
    serveQueue(5, { busy: true, stoppable: true, pending, queueSize: 2 })
    const { component } = await openConversation(5)

    await vi.waitFor(() => expect(queuedRows(component)).toEqual(['second | Queued, position 1', 'notes.pdf | third | Queued, position 2']))
    // No assistant bubble waits under a queued message.
    expect(vmOf(component).messages.map(m => m.role)).toEqual(['user'])
  })

  it('stop: Stop on a stoppable turn posts the stop, and the turn ends stopped', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveQueue(5, { busy: true, stoppable: true })
    serveStop(5)
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    stream.send({ type: 'token', content: 'Half an answer' })
    await vi.waitFor(() => expect(stopButton(component).exists()).toBe(true))
    expect(stopButton(component).attributes('title')).toBe('Stop generating')
    expect(stopButton(component).attributes('aria-label')).toBe('Stop generating')
    expect(stopButton(component).text()).toBe('')

    await stopButton(component).trigger('click')
    await vi.waitFor(() => expect(stops).toEqual([5]))

    await vi.waitFor(() => expect(stopButton(component).exists()).toBe(false))
    expect(component.text()).toContain('Half an answer')
    expect(component.text()).toContain('(stopped)')
    expect(progressLine(component).exists()).toBe(false)
  })

  it('stop: when the turn ended first, its own terminal frame settles the bubble', async () => {
    serveConversation(5, [row(1, 'user', 'quick job')])
    serveQueue(5, { busy: true, stoppable: true })
    serveStop(5, false)
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(stopButton(component).exists()).toBe(true))

    await stopButton(component).trigger('click')
    await vi.waitFor(() => expect(stops).toEqual([5]))
    for (let i = 0; i < 5; i++) await flushPromises()
    expect(progressLine(component).exists()).toBe(true)

    stream.send({ type: 'complete', content: 'Done before the stop.' })
    await vi.waitFor(() => expect(progressLine(component).exists()).toBe(false))
    expect(component.text()).toContain('Done before the stop.')
    expect(component.text()).not.toContain('(stopped)')
  })

  it('stop, messages queued: Stop says it drops them, and after it no Queued row remains', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveQueue(5, { busy: true, stoppable: true, queueSize: 2, pending: [
      { text: 'second', attachmentNames: [], position: 1 },
      { text: 'third', attachmentNames: [], position: 2 },
    ] })
    serveStop(5)
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(stopButton(component).text()).toBe('Stop and drop 2 queued'))
    expect(stopButton(component).attributes('title')).toBe('Stop and drop 2 queued')
    expect(stopButton(component).attributes('aria-label')).toBe('Stop and drop 2 queued')
    expect(queuedRows(component)).toHaveLength(2)

    await stopButton(component).trigger('click')

    await vi.waitFor(() => expect(queuedRows(component)).toEqual([]))
    expect(stopButton(component).exists()).toBe(false)
  })

  it('slash command: a command\'s reply renders, and /new switches to the conversation it made', async () => {
    const rows = [row(1, 'user', 'earlier')]
    serveConversation(5, rows)
    serveConversation(77, [row(1, 'assistant', 'New conversation started.')])
    serveQueue(5)
    serveQueue(77)
    // Each reply commits before its answer, which says so.
    serveTurnStart((body) => {
      if (body.message === '/new') return { status: 'command', conversationId: 77, content: 'New conversation started.', saved: true }
      rows.push(row(2, 'assistant', 'Model set to kimi-k2.5.'))
      return { status: 'command', conversationId: 5, content: 'Model set to kimi-k2.5.', saved: true }
    })
    const { component } = await openConversation(5)

    await type(component, '/model kimi-k2.5')
    await vi.waitFor(() => expect(component.text()).toContain('Model set to kimi-k2.5.'))
    expect(shown(component)).toEqual(['user: earlier', 'user: /model kimi-k2.5', 'assistant: Model set to kimi-k2.5.'])

    await type(component, '/new')
    await vi.waitFor(() => expect(useRouter().currentRoute.value.query.conversation).toBe('77'))
    expect(shown(component)).toEqual(['assistant: New conversation started.'])
    expect(openStreamsOn(5)).toEqual([])
    expect(openStreamsOn(77)).toHaveLength(1)
  })

  it('late row: a row that commits below a shown one renders in server order, as after a reload', async () => {
    const rows = [row(1, 'user', 'go'), row(3, 'assistant', 'Done.')]
    serveConversation(5, rows)
    serveQueue(5)
    const { component } = await openConversation(5)

    rows.splice(1, 0, row(2, 'system', 'A task reported in.', { messageKind: 'task_run_result' }))
    await vmOf(component).pollForAnnounce()

    await vi.waitFor(() => expect(vmOf(component).messages.map(m => m.id)).toEqual([1, 2, 3]))
    const live = component.findAll('[data-testid]').map(e => e.attributes('data-testid'))
    component.unmount()
    const { component: reloaded } = await openConversation(5)
    expect(vmOf(reloaded).messages.map(m => m.id)).toEqual([1, 2, 3])
    expect(reloaded.findAll('[data-testid]').map(e => e.attributes('data-testid'))).toEqual(live)
  })

  it('leave: switching, New Chat and unmount each close the stream, and the turn keeps running', async () => {
    serveConversation(5, [row(1, 'user', 'long job')])
    serveConversation(6, [row(1, 'user', 'another')])
    serveQueue(5, { busy: true, stoppable: true })
    serveQueue(6)
    serveStop(5)
    const { component, stream } = await openConversation(5)
    stream.send({ type: 'init', conversationId: 5 })
    await vi.waitFor(() => expect(progressLine(component).exists()).toBe(true))

    await vmOf(component).loadConversation(6)
    await flushPromises()
    expect(stream.closed).toBe(true)
    expect(progressLine(component).exists()).toBe(false)
    expect(streamOn(6).url).toBe('/api/conversations/6/stream')

    await component.find('button[title="New conversation"]').trigger('click')
    await flushPromises()
    expect(openStreamsOn(6)).toEqual([])

    await vmOf(component).loadConversation(5)
    await flushPromises()
    const again = streamOn(5)
    component.unmount()
    expect(again.closed).toBe(true)
    await flushPromises()
    expect(stops).toEqual([])
    expect(FakeEventSource.all.every(s => s.closed)).toBe(true)
  })
})
