import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { ChecklistItem, Message } from '~/types/api'
import type { ConversationQueueStatus } from '~/composables/useChatBackgroundTurn'
import { useChatStream, type UseChatStream, type UseChatStreamDeps } from '~/composables/useChatStream'
import ChatToolCalls from '~/components/chat/ChatToolCalls.vue'
import { fakeConversationStreams, FakeEventSource, openStreamsOn, serveTurnStart, streamOn } from './conversation-stream'

// The SSE bus is mocked so a test can reconnect it.
const bus = vi.hoisted(() => ({ openHandlers: [] as Array<() => void> }))
vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => ({
    on: () => {},
    off: () => {},
    onEvent: () => {},
    onOpen: (handler: () => void) => {
      bus.openHandlers.push(handler)
    },
  }),
}))

beforeEach(() => {
  fakeConversationStreams()
  bus.openHandlers.length = 0
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const BUSY: ConversationQueueStatus = { busy: true, queueSize: 0, stoppable: true, pending: [] }
const IDLE: ConversationQueueStatus = { busy: false, queueSize: 0, stoppable: false, pending: [] }

function makeDeps(over: Partial<UseChatStreamDeps> = {}) {
  const messages = shallowRef<Message[]>([])
  const base: UseChatStreamDeps = {
    messages,
    selectedConvoId: ref<number | null>(null),
    subagentTranscript: ref(null),
    selectedAgentId: ref<number | null>(1),
    streaming: ref(false),
    streamReasoning: ref(''),
    followedConvoId: ref<number | null>(null),
    input: ref(''),
    chatInput: ref(null),
    attachedFiles: ref([]),
    attachError: ref<string | null>(null),
    attachmentPreviews: ref(new Map()),
    uploadAttachments: vi.fn(async () => []),
    scrollToBottom: vi.fn(),
    focusInput: vi.fn(),
    imageGenTurnKey: ref<string | null>(null),
    startImageProgressPolling: vi.fn(),
    browserSetupTurnKey: ref<string | null>(null),
    startBrowserSetupPolling: vi.fn(),
    startVideoPolling: vi.fn(),
    mergeSavedRows: vi.fn(async () => {}),
    readQueue: vi.fn(async () => BUSY),
    showQueued: vi.fn(),
    refreshConversations: vi.fn(),
    refreshAgents: vi.fn(),
    ...over,
  }
  return base
}

/** Deps open on conversation `id`, whose rows have loaded. */
function onConversation(id: number, over: Partial<UseChatStreamDeps> = {}) {
  return makeDeps({ selectedConvoId: ref<number | null>(id), followedConvoId: ref<number | null>(id), ...over })
}

async function mountStream(deps: UseChatStreamDeps) {
  let api!: UseChatStream
  const wrapper = await mountSuspended(
    defineComponent({
      setup() {
        api = useChatStream(deps)
        return () => h('div')
      },
    }),
  )
  return { wrapper, api }
}

async function send(api: UseChatStream, deps: UseChatStreamDeps, text: string) {
  deps.input.value = text
  await api.sendMessage()
  await flushPromises()
}

/** One whole turn on `stream`: init, the frames, then flushes. */
async function turn(stream: FakeEventSource, conversationId: number, ...frames: object[]) {
  stream.send({ type: 'init', conversationId, startedAt: '2026-11-07T10:00:00Z' })
  for (const f of frames) stream.send(f)
  await flushPromises()
}

const contents = (deps: UseChatStreamDeps) => deps.messages.value.map(m => [m.role, m.content])

describe('useChatStream', () => {
  describe('sendMessage guards', () => {
    it('does nothing in a read-only subagent transcript', async () => {
      const bodies = serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
      const deps = makeDeps({ subagentTranscript: ref({ agentId: 9, agentName: 'x' }) })
      const { api } = await mountStream(deps)
      await send(api, deps, 'hi')
      expect(bodies).toEqual([])
      expect(deps.messages.value).toEqual([])
    })

    it('does nothing with no text and no attachments', async () => {
      const bodies = serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
      const deps = makeDeps()
      const { api } = await mountStream(deps)
      await send(api, deps, '   ')
      expect(bodies).toEqual([])
    })
  })

  it('sends through POST /api/chat/turn and renders the turn once, from the stream', async () => {
    const bodies = serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    const stream = streamOn(42)
    expect(stream.url).toBe('/api/conversations/42/stream')

    await send(api, deps, 'hello there')
    expect(bodies).toEqual([{ agentId: 1, conversationId: 42, message: 'hello there', attachments: [] }])
    expect(contents(deps)).toEqual([['user', 'hello there']])
    expect(deps.streaming.value).toBe(false)
    expect(deps.input.value).toBe('')

    stream.send({ type: 'init', conversationId: 42, startedAt: '2026-11-07T10:00:00Z' })
    expect(deps.streaming.value).toBe(true)
    stream.send({ type: 'token', content: 'Hi ' })
    stream.send({ type: 'token', content: 'back' })
    stream.send({ type: 'complete', content: 'Hi back' })
    await flushPromises()

    expect(contents(deps)).toEqual([['user', 'hello there'], ['assistant', 'Hi back']])
    expect(deps.streaming.value).toBe(false)
    expect(deps.refreshConversations).toHaveBeenCalled()
    expect(deps.refreshAgents).toHaveBeenCalled()
  })

  it('takes the turn\'s start time from its init, for the progress timer', async () => {
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    streamOn(42).send({ type: 'init', conversationId: 42, startedAt: '2026-11-07T10:00:00Z' })
    expect(api.streamStartedAt.value).toBe(Date.parse('2026-11-07T10:00:00Z'))
    streamOn(42).send({ type: 'complete', content: 'done' })
    expect(api.streamStartedAt.value).toBeNull()
  })

  it('fetches the turn\'s saved rows on init before it reads the queue, so a drained message shows before its Queued row goes', async () => {
    const order: string[] = []
    const deps = onConversation(42, {
      mergeSavedRows: vi.fn(async () => {
        order.push('rows')
      }),
      readQueue: vi.fn(async (opts?: { init?: boolean }) => {
        order.push(opts?.init ? 'queue at init' : 'queue')
        return BUSY
      }),
    })
    await mountStream(deps)
    streamOn(42).send({ type: 'init', conversationId: 42 })
    await flushPromises()

    expect(order).toEqual(['rows', 'queue at init'])
    const [opts] = (deps.mergeSavedRows as ReturnType<typeof vi.fn>).mock.calls[0]! as [{ pairable: (m: Message) => boolean }]
    const bubble = deps.messages.value.at(-1)!
    expect(opts.pairable(bubble)).toBe(false)
    expect(opts.pairable({ role: 'user', content: 'drained', createdAt: '' })).toBe(true)
  })

  it('waits for its own send\'s answer before fetching rows on the init that answer started', async () => {
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'started', conversationId: 42 }
    })
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    deps.input.value = 'hello'
    const sending = api.sendMessage()
    await flushPromises()

    streamOn(42).send({ type: 'init', conversationId: 42 })
    await flushPromises()
    expect(deps.mergeSavedRows).not.toHaveBeenCalled()

    answer()
    await sending
    await flushPromises()
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)
  })

  it('reconciles with the saved rows on the terminal frame, since every engine commits the reply before it', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    streamOn(42).send({ type: 'init', conversationId: 42 })
    await flushPromises()
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)

    streamOn(42).send({ type: 'complete', content: 'done' })
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(2)
    expect(deps.refreshConversations).toHaveBeenCalledTimes(1)
  })

  it('leaves the bubble open when a merge runs unpaired, the merge the turn before it started included', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    const stream = streamOn(42)
    stream.send({ type: 'init', conversationId: 42 })
    await flushPromises()
    stream.send({ type: 'complete', content: 'first reply' })
    const first = deps.messages.value[0]!
    // A drained message's turn opens while the first one's terminal merge is still fetching.
    stream.send({ type: 'init', conversationId: 42 })
    const second = deps.messages.value[1]!
    await flushPromises()

    const calls = (deps.mergeSavedRows as ReturnType<typeof vi.fn>).mock.calls as Array<[{ pairable: (m: Message) => boolean }]>
    expect(calls).toHaveLength(3)
    for (const [opts] of calls) {
      expect(opts.pairable(second)).toBe(false)
      expect(opts.pairable(first)).toBe(true)
    }
  })

  it('drops an init\'s row fetch when a newer turn opened while it waited on a send\'s answer', async () => {
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'queued', conversationId: 42, position: 1 }
    })
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    deps.input.value = 'hello'
    const sending = api.sendMessage()
    await flushPromises()
    streamOn(42).send({ type: 'init', conversationId: 42 }, '1')
    streamOn(42).send({ type: 'init', conversationId: 42 }, '2')
    answer()
    await sending
    await flushPromises()

    // Only the newer turn fetches its rows and makes its init read.
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)
    expect((deps.readQueue as ReturnType<typeof vi.fn>).mock.calls.filter(([opts]) => opts?.init)).toHaveLength(1)
  })

  it('hides Stop as each turn starts, until that turn\'s own queue read says it applies', async () => {
    const clearStoppable = vi.fn()
    const deps = onConversation(42, { clearStoppable })
    await mountStream(deps)
    streamOn(42).send({ type: 'init', conversationId: 42 })
    expect(clearStoppable).toHaveBeenCalledTimes(1)
  })

  it('renders a turn it did not send — a drained message, another tab\'s, a channel\'s — the same way', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42, { type: 'status', content: 'Using tool: web_fetch' },
      { type: 'tool_call', id: 'tc1', name: 'web_fetch', arguments: '{}', resultText: 'page' },
      { type: 'complete', content: 'Fetched it.' })

    expect(contents(deps)).toEqual([['assistant', 'Fetched it.']])
    expect(deps.messages.value[0]!.toolCalls!.map(tc => tc.name)).toEqual(['web_fetch'])
  })

  it('opens a new chat\'s conversation from the answer, and sends its picks with the first message only (JCLAW-1196)', async () => {
    const bodies = serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
    const pending = ref<{ modelProvider?: string, modelId?: string, thinkingMode?: string } | null>(
      { modelProvider: 'anthropic', modelId: 'opus', thinkingMode: 'high' })
    const deps = makeDeps({ pendingOverrides: pending })
    const { api } = await mountStream(deps)
    expect(FakeEventSource.all).toEqual([])

    await send(api, deps, 'first')
    expect(bodies[0]).toEqual({ agentId: 1, conversationId: null, message: 'first', attachments: [],
      modelProvider: 'anthropic', modelId: 'opus', thinkingMode: 'high' })
    expect(deps.selectedConvoId.value).toBe(42)
    expect(deps.followedConvoId.value).toBe(42)
    await turn(streamOn(42), 42, { type: 'complete', content: 'ok' })
    expect(contents(deps)).toEqual([['user', 'first'], ['assistant', 'ok']])

    await send(api, deps, 'second')
    expect(bodies[1]).toEqual({ agentId: 1, conversationId: 42, message: 'second', attachments: [] })
  })

  it('loads the rows of a new chat whose turn ended before its stream opened', async () => {
    serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
    const deps = makeDeps({ readQueue: vi.fn(async () => IDLE) })
    const { api } = await mountStream(deps)
    await send(api, deps, 'quick one')

    streamOn(42).open()
    await flushPromises()

    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)
    expect(deps.streaming.value).toBe(false)
  })

  it('moves a send the server queued to the Queued rows, with its place in line and its attachments\' names', async () => {
    serveTurnStart(() => ({ status: 'queued', conversationId: 42, position: 2 }))
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    const deps = onConversation(42, {
      attachedFiles: ref([new File(['%PDF'], 'notes.pdf', { type: 'application/pdf' })]),
      uploadAttachments: vi.fn(async () => [{ attachmentId: 'a1', originalFilename: 'notes.pdf', mimeType: 'application/pdf',
        sizeBytes: 4, kind: 'FILE' as const }]),
    })
    const { api } = await mountStream(deps)
    await turn(streamOn(42), 42, { type: 'token', content: 'working' })

    await send(api, deps, 'and then this')

    expect(contents(deps)).toEqual([['assistant', 'working']])
    expect(deps.showQueued).toHaveBeenCalledWith({ text: 'and then this', attachmentNames: ['notes.pdf'], position: 2 })
    expect(deps.readQueue).toHaveBeenLastCalledWith()
    expect(deps.streaming.value).toBe(true)
  })

  it('shows an unsaved slash command reply as a row that pairs with nothing, and merges a saved one in', async () => {
    serveTurnStart(body => body.message === '/model kimi'
      ? { status: 'command', conversationId: 42, content: 'Model set.', saved: true }
      : { status: 'command', conversationId: 42, content: 'Stopped.', saved: false })
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    await send(api, deps, '/model kimi')
    // The saved reply committed before the answer: the merge brings it, so no copy is added here.
    expect(contents(deps)).toEqual([['user', '/model kimi']])
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)

    await send(api, deps, '/stop')
    expect(contents(deps)).toEqual([['user', '/model kimi'], ['user', '/stop'], ['assistant', 'Stopped.']])
    expect(deps.messages.value.map(m => m._local)).toEqual([true, true, true])
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)
  })

  it('opens the conversation a New Chat\'s command ran in as a load does, instead of following it with no rows', async () => {
    serveTurnStart(() => ({ status: 'command', conversationId: 5, content: 'Context cleared.', saved: false }))
    const openConversation = vi.fn(async (id: number) => {
      deps.selectedConvoId.value = id
      deps.messages.value = [{ id: 1, role: 'user', content: 'earlier', createdAt: '' }]
      deps.followedConvoId.value = id
    })
    const deps = makeDeps({ openConversation })
    const { api } = await mountStream(deps)

    await send(api, deps, '/reset')

    expect(openConversation).toHaveBeenCalledWith(5)
    expect(contents(deps)).toEqual([['user', 'earlier'], ['user', '/reset'], ['assistant', 'Context cleared.']])
    expect(deps.messages.value.map(m => m._local)).toEqual([undefined, true, true])
  })

  it('drops a new chat\'s answer when the operator opened another conversation meanwhile', async () => {
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'started', conversationId: 42 }
    })
    const deps = makeDeps()
    const { api } = await mountStream(deps)
    deps.input.value = 'first'
    const sending = api.sendMessage()
    await flushPromises()

    // The operator picks conversation 7; its rows are still loading, so its stream is not open yet.
    deps.selectedConvoId.value = 7
    answer()
    await sending
    await flushPromises()

    expect(deps.selectedConvoId.value).toBe(7)
    expect(FakeEventSource.all).toEqual([])
  })

  it('opens the stream of a conversation whose load never did, once a send there starts a turn', async () => {
    serveTurnStart(() => ({ status: 'started', conversationId: 5 }))
    const deps = makeDeps({ selectedConvoId: ref<number | null>(5) })
    const { api } = await mountStream(deps)
    expect(openStreamsOn(5)).toEqual([])

    await send(api, deps, 'hello')

    expect(deps.followedConvoId.value).toBe(5)
    expect(streamOn(5).url).toBe('/api/conversations/5/stream')
  })

  it('switches to the conversation /new made, showing what it holds (JCLAW-26)', async () => {
    serveTurnStart(() => ({ status: 'command', conversationId: 77, content: 'New conversation started.', saved: true }))
    const openConversation = vi.fn(async (id: number) => {
      deps.selectedConvoId.value = id
      deps.messages.value = [{ id: 900, role: 'assistant', content: 'New conversation started.', createdAt: '' }]
      deps.followedConvoId.value = id
    })
    const deps = onConversation(42, { openConversation })
    deps.messages.value = [{ id: 1, role: 'user', content: 'old', createdAt: '' }]
    const { api } = await mountStream(deps)
    await send(api, deps, '/new')

    expect(openConversation).toHaveBeenCalledWith(77)
    expect(contents(deps)).toEqual([['assistant', 'New conversation started.']])
    expect(openStreamsOn(42)).toEqual([])
    expect(streamOn(77).url).toBe('/api/conversations/77/stream')
  })

  it('places nothing from a command when the operator opens another conversation while its conversation loads', async () => {
    serveTurnStart(() => ({ status: 'command', conversationId: 5, content: 'Context cleared.', saved: false }))
    let loaded!: () => void
    const openConversation = vi.fn((id: number) => {
      deps.selectedConvoId.value = id
      // A load another one superseded writes no rows, as useChatConversation's does.
      return new Promise<void>((resolve) => {
        loaded = resolve
      })
    })
    const deps = makeDeps({ openConversation })
    const { api } = await mountStream(deps)
    deps.input.value = '/reset'
    const sending = api.sendMessage()
    await vi.waitFor(() => expect(openConversation).toHaveBeenCalledWith(5))

    deps.selectedConvoId.value = 9
    deps.messages.value = [{ id: 90, role: 'user', content: 'conversation nine', createdAt: '' }]
    loaded()
    await sending

    expect(contents(deps)).toEqual([['user', 'conversation nine']])
  })

  /** Deps whose load of a command's conversation fails, after `during` runs while it loads. */
  function failingLoad(during: (deps: UseChatStreamDeps) => void = () => {}) {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const deps = makeDeps({ openConversation: vi.fn(async (id: number) => {
      deps.selectedConvoId.value = id
      during(deps)
      throw new Error('load failed')
    }) })
    return deps
  }

  it('leaves a saved /new reply to the merge when its conversation fails to load, and says why', async () => {
    serveTurnStart(() => ({ status: 'command', conversationId: 5, content: 'What would you like to discuss?', saved: true }))
    const deps = failingLoad()
    const { api } = await mountStream(deps)

    await expect(send(api, deps, '/new')).resolves.toBeUndefined()

    expect(contents(deps)).toEqual([['user', '/new'], ['assistant', 'Error: load failed']])
    expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)
    expect(console.error).toHaveBeenCalledWith('Failed to open the conversation a command ran in:', expect.any(Error))
  })

  it('opens the stream of a conversation whose load failed, since the view selected it', async () => {
    serveTurnStart(() => ({ status: 'command', conversationId: 5, content: 'What would you like to discuss?', saved: true }))
    const deps = failingLoad()
    const { api } = await mountStream(deps)

    await send(api, deps, '/new')

    expect(deps.followedConvoId.value).toBe(5)
    expect(openStreamsOn(5)).toHaveLength(1)
  })

  it('places an unsaved reply and why its conversation failed right after its own message', async () => {
    serveTurnStart(body => body.message === '/new'
      ? { status: 'command', conversationId: 5, content: 'Started.', saved: false }
      : { status: 'queued', conversationId: 5, position: 1 })
    const deps = failingLoad((d) => {
      d.messages.value = [...d.messages.value, { _key: 'later', role: 'user', content: 'later', createdAt: '', _local: true }]
    })
    const { api } = await mountStream(deps)

    await send(api, deps, '/new')

    expect(contents(deps)).toEqual([['user', '/new'], ['assistant', 'Started.'], ['assistant', 'Error: load failed'],
      ['user', 'later']])
  })

  it('ends the turn a typed /stop ended when its stopped frame never comes', async () => {
    serveTurnStart(() => ({ status: 'command', conversationId: 42, content: 'Stopped.', saved: false }))
    const queue = { status: BUSY }
    const deps = onConversation(42, { readQueue: vi.fn(async () => queue.status) })
    const { api } = await mountStream(deps)
    await turn(streamOn(42), 42, { type: 'token', content: 'long task' })

    queue.status = IDLE
    await send(api, deps, '/stop')

    expect(deps.streaming.value).toBe(false)
    expect(contents(deps)).toEqual([['user', '/stop'], ['assistant', 'Stopped.']])
  })

  it('keeps a message left waiting behind a New Chat command out of the next New Chat, whose later message follows its own first', async () => {
    let answerHelp!: () => void
    const helpHeld = new Promise<void>((resolve) => {
      answerHelp = resolve
    })
    let answerX!: () => void
    const xHeld = new Promise<void>((resolve) => {
      answerX = resolve
    })
    const bodies = serveTurnStart(async (body) => {
      if (body.message === '/help') {
        await helpHeld
        return { status: 'command', conversationId: null, content: 'Commands.', saved: false }
      }
      if (body.message === 'x') {
        await xHeld
        return { status: 'started', conversationId: 44 }
      }
      return { status: 'started', conversationId: body.message === 'first' ? 45 : 44 }
    })
    const deps = makeDeps()
    const { api } = await mountStream(deps)
    const sending = (text: string) => {
      deps.input.value = text
      return api.sendMessage()
    }
    const all = [sending('/help'), sending('first')]
    await flushPromises()

    api.leaveView()
    all.push(sending('x'))
    await vi.waitFor(() => expect(bodies.map(b => b.message)).toEqual(['/help', 'x']))
    answerHelp()
    await vi.waitFor(() => expect(bodies.map(b => b.message)).toEqual(['/help', 'x', 'first']))
    all.push(sending('y'))
    answerX()
    await Promise.all(all)

    expect(bodies.map(b => [b.message, b.conversationId])).toEqual([['/help', null], ['x', null], ['first', null], ['y', 44]])
  })

  it('marks a send unpairable until its answer comes, so no merge can give a queued message a saved row\'s id', async () => {
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'started', conversationId: 42 }
    })
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    deps.input.value = 'hello'
    const sending = api.sendMessage()
    await flushPromises()
    expect(deps.messages.value[0]!._sending).toBe(true)

    answer()
    await sending
    expect(deps.messages.value[0]!._sending).toBeUndefined()
  })

  it('sends a second New Chat message to the conversation the first one\'s answer creates', async () => {
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    const bodies = serveTurnStart(async (body) => {
      if (body.message === 'first') await answered
      return { status: body.message === 'first' ? 'started' : 'queued', conversationId: 42, position: 1 }
    })
    const deps = makeDeps()
    const { api } = await mountStream(deps)
    deps.input.value = 'first'
    const first = api.sendMessage()
    await flushPromises()
    deps.input.value = 'second'
    const second = api.sendMessage()
    await flushPromises()
    expect(bodies.map(b => b.message)).toEqual(['first'])

    answer()
    await first
    await second
    await flushPromises()

    expect(bodies.map(b => [b.message, b.conversationId])).toEqual([['first', null], ['second', 42]])
    expect(deps.showQueued).toHaveBeenCalledWith({ text: 'second', attachmentNames: [], position: 1 })
  })

  it('places nothing from a New Chat\'s answer after the operator left it for another New Chat', async () => {
    let answer!: () => void
    const answered = new Promise<void>((resolve) => {
      answer = resolve
    })
    serveTurnStart(async () => {
      await answered
      return { status: 'started', conversationId: 42 }
    })
    const deps = makeDeps()
    const { api } = await mountStream(deps)
    deps.input.value = 'first'
    const sending = api.sendMessage()
    await flushPromises()

    api.leaveView()
    answer()
    await sending
    await flushPromises()

    expect(deps.selectedConvoId.value).toBeNull()
    expect(FakeEventSource.all).toEqual([])
  })

  it('scrolls for the operator\'s own send whatever the reader\'s place, and for a turn\'s frames only at the bottom', async () => {
    serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
    const followToBottom = vi.fn()
    const deps = onConversation(42, { followToBottom })
    const { api } = await mountStream(deps)
    await send(api, deps, 'hello')
    expect(deps.scrollToBottom).toHaveBeenCalledTimes(1)

    await turn(streamOn(42), 42,
      { type: 'reasoning', content: 'Thinking it over' },
      { type: 'token', content: 'Hi' },
      { type: 'tool_call', id: 'tc1', name: 'exec', arguments: '{}', resultText: 'ok' },
      { type: 'status', content: JSON.stringify({ usage: { prompt: 10, completion: 2, total: 12 } }) },
      { type: 'complete', content: 'Hi' })
    await flushPromises()
    expect(deps.scrollToBottom).toHaveBeenCalledTimes(1)
    // init, reasoning, token, tool_call, usage, complete, and the end of the turn.
    expect(followToBottom).toHaveBeenCalledTimes(7)
  })

  it('shows why a send was refused on a reply row that pairs with nothing', async () => {
    registerEndpoint('/api/chat/turn', {
      method: 'POST',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 500)
        return { code: 'internal_error', message: 'The turn could not start.' }
      },
    })
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const deps = onConversation(42)
    const { api } = await mountStream(deps)
    await send(api, deps, 'go')

    expect(contents(deps)).toEqual([['user', 'go'], ['assistant', 'Error: The turn could not start.']])
    expect(deps.messages.value.map(m => m._local)).toEqual([true, true])
    expect(deps.streaming.value).toBe(false)
  })

  it('starts browser-setup polling on the turn that calls the browser, before the call finishes', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42,
      { type: 'status', content: 'Using tool: web_fetch' },
      { type: 'status', content: 'Using tool: browser' },
      { type: 'complete', content: 'done' })

    // Once, for the browser status only — web_fetch never downloads a browser.
    expect(deps.startBrowserSetupPolling).toHaveBeenCalledTimes(1)
    expect(deps.browserSetupTurnKey.value).toBe(deps.messages.value[0]!._key)
  })

  it('takes the router\'s route frame onto the reply instead of showing it as status text (JCLAW-1222)', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    const route = { class: 'chat', provider: 'ollama-cloud', model: 'glm-5.3-flash', reason: 'no task markers' }
    await turn(streamOn(42), 42,
      { type: 'status', content: JSON.stringify({ route }) },
      { type: 'status', content: JSON.stringify({ route: { ...route, provider: 'openrouter', failover: true } }) },
      { type: 'token', content: 'hi' },
      { type: 'complete', content: 'hi' })

    expect(deps.messages.value[0]!._route).toEqual({ ...route, provider: 'openrouter', failover: true })
  })

  it('takes each context frame onto the reply so the meter moves before the turn ends', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42,
      { type: 'status', content: '{"contextTokens":116155}' },
      { type: 'status', content: '{"contextTokens":131072}' },
      { type: 'complete', content: 'hi' })

    expect(deps.messages.value[0]!._contextTokens).toBe(131072)
  })

  it('stamps the reasoning→content transition (collapses the thinking card once)', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42,
      { type: 'reasoning', content: 'let me think' },
      { type: 'token', content: 'answer' },
      { type: 'complete', content: 'answer' })

    const assistant = deps.messages.value[0]!
    expect(assistant.reasoning).toBe('let me think')
    expect(assistant.content).toBe('answer')
    expect(assistant.thinkingCollapsed).toBe(true)
  })

  it('starts image-gen progress polling when a generate_image tool call fires', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42,
      { type: 'tool_call', id: 'tc1', name: 'generate_image', arguments: '{}' },
      { type: 'complete', content: 'done' })

    expect(deps.startImageProgressPolling).toHaveBeenCalledTimes(1)
    expect(deps.messages.value[0]!.toolCalls!.map(tc => tc.name)).toEqual(['generate_image'])
  })

  it('draws the view a live tool_call frame carries', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    const mcpApp = { server: 'serpapi', tool: 'search_table', resourceUri: 'ui://prefab/tool/583d325b0e9c/renderer.html',
      arguments: { params: { q: 'cats' } }, result: { content: [{ type: 'text', text: '3 results' }], isError: false } }
    await turn(streamOn(42), 42,
      { type: 'tool_call', id: 'tc1', name: 'mcp_serpapi', icon: 'plug', arguments: '{"tool":"search_table"}',
        resultText: '3 results', resultStructured: { mcpApp } },
      { type: 'complete', content: 'Here is the table.' })

    const calls = deps.messages.value[0]!.toolCalls!
    expect(calls[0]!.resultStructured).toEqual({ mcpApp })
    const card = await mountSuspended(ChatToolCalls, { props: { toolCalls: calls, collapsed: false, mcpApps: true } })
    expect(card.findAll('[data-testid="mcp-app-frame"] iframe').map(f => f.attributes('src')))
      .toEqual(['/api/mcp-servers/view?server=serpapi&uri=ui%3A%2F%2Fprefab%2Ftool%2F583d325b0e9c%2Frenderer.html'])
  })

  it('leaves a call a joined turn already shows on a saved row off its bubble', async () => {
    const deps = onConversation(42)
    deps.messages.value = [{ id: 5, role: 'assistant', content: null, createdAt: '',
      toolCalls: [{ id: 'tc1', name: 'exec', icon: 'wrench', arguments: '{}', resultText: 'ok', resultStructured: null }] }]
    await mountStream(deps)
    await turn(streamOn(42), 42,
      { type: 'tool_call', id: 'tc1', name: 'exec', arguments: '{}', resultText: 'ok' },
      { type: 'tool_call', id: 'tc2', name: 'exec', arguments: '{}', resultText: 'ok' })

    expect(deps.messages.value[1]!.toolCalls!.map(tc => tc.id)).toEqual(['tc2'])
  })

  it('takes the live checklist from a successful checklist call and drops it when the next turn starts', async () => {
    const liveChecklist = ref<ChecklistItem[] | null>(null)
    const deps = onConversation(42, { liveChecklist })
    await mountStream(deps)
    const items = [{ content: 'Run tests', status: 'in_progress', activeForm: 'Running tests' }]
    await turn(streamOn(42), 42,
      { type: 'tool_call', id: 'tc1', name: 'checklist', arguments: JSON.stringify({ items }),
        resultText: 'Checklist updated successfully (1 items).' },
      { type: 'complete', content: 'done' })
    expect(liveChecklist.value).toEqual(items)

    streamOn(42).send({ type: 'init', conversationId: 42 })
    expect(liveChecklist.value).toBeNull()
  })

  it('leaves the live checklist alone when the tool refused the call', async () => {
    const liveChecklist = ref<ChecklistItem[] | null>(null)
    const deps = onConversation(42, { liveChecklist })
    await mountStream(deps)
    await turn(streamOn(42), 42,
      { type: 'tool_call', id: 'tc1', name: 'checklist', arguments: '{"items":[]}',
        resultText: 'Error: At most one item may be in_progress. Found 2.' },
      { type: 'complete', content: 'done' })
    expect(liveChecklist.value).toBeNull()
  })

  it('marks what streamed as stopped when the turn ends stopped', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42, { type: 'token', content: 'Half an answer' }, { type: 'stopped' })
    expect(contents(deps)).toEqual([['assistant', 'Half an answer\n\n_(stopped)_']])
    expect(deps.streaming.value).toBe(false)

    await turn(streamOn(42), 42, { type: 'stopped' })
    expect(contents(deps).at(-1)).toEqual(['assistant', '_(stopped before any response)_'])
  })

  /** The saved row of a round that ran call `callId`, which hydration hangs the call on. */
  const savedRound = (id: number, callId: string): Message => ({ id, role: 'assistant', content: null, createdAt: '',
    toolCalls: [{ id: callId, name: 'exec', icon: 'wrench', arguments: '{}', resultText: 'ok', resultStructured: null }] })

  it('drops the calls an errored bubble streamed once a merge shows them on their saved rows', async () => {
    let failed = false
    const deps = onConversation(42, {
      mergeSavedRows: vi.fn(async () => {
        if (failed) deps.messages.value = [savedRound(5, 'tc1'), ...deps.messages.value]
      }),
    })
    await mountStream(deps)
    await turn(streamOn(42), 42, { type: 'tool_call', id: 'tc1', name: 'exec', arguments: '{}', resultText: 'ok' })
    failed = true
    streamOn(42).send({ type: 'error', content: '**Provider error** — try again.' })
    await flushPromises()

    const bubble = deps.messages.value.at(-1)!
    expect(bubble._local).toBe(true)
    expect(bubble.toolCalls).toEqual([])
  })

  it('drops the calls an open bubble streamed once a merge brings in the rows of the rounds that made them', async () => {
    let release!: () => void
    const deps = onConversation(42, {
      mergeSavedRows: vi.fn(() => new Promise<void>((resolve) => {
        release = () => {
          deps.messages.value = [savedRound(5, 'tc1'), ...deps.messages.value]
          resolve()
        }
      })),
    })
    await mountStream(deps)
    const stream = streamOn(42)
    stream.send({ type: 'init', conversationId: 42 })
    await flushPromises()
    stream.send({ type: 'tool_call', id: 'tc1', name: 'exec', arguments: '{}', resultText: 'ok' })
    stream.send({ type: 'tool_call', id: 'tc2', name: 'exec', arguments: '{}', resultText: 'ok' })
    release()
    await flushPromises()

    expect(deps.messages.value.at(-1)!.toolCalls!.map(tc => tc.id)).toEqual(['tc2'])
    expect(deps.streaming.value).toBe(true)
  })

  it('shows the error frame\'s text in a bubble that keeps its place, since no saved row matches it', async () => {
    const deps = onConversation(42)
    await mountStream(deps)
    await turn(streamOn(42), 42, { type: 'error', content: '**Provider error** — try again.' })
    expect(contents(deps)).toEqual([['assistant', '**Provider error** — try again.']])
    expect(deps.messages.value[0]!._local).toBe(true)
    expect(deps.streaming.value).toBe(false)
  })

  describe('resync and reconnect', () => {
    it('reloads the rows on a resync and rebuilds the turn from the frames after it', async () => {
      const deps = onConversation(42)
      await mountStream(deps)
      const stream = streamOn(42)
      stream.send({ type: 'init', conversationId: 42 }, '100')
      stream.send({ type: 'token', content: 'stale' }, '101')
      await flushPromises()
      ;(deps.mergeSavedRows as ReturnType<typeof vi.fn>).mockClear()

      // A reconnect whose last id was merged away: the init again, the resync, then what the server kept.
      stream.send({ type: 'init', conversationId: 42 }, '100')
      stream.send({ type: 'resync' }, '99')
      stream.send({ type: 'token', content: 'The whole reply so far' }, '104')
      await flushPromises()

      expect(deps.mergeSavedRows).toHaveBeenCalledWith(expect.objectContaining({ full: true }))
      expect(contents(deps)).toEqual([['assistant', 'The whole reply so far']])
      expect(deps.streaming.value).toBe(true)
    })

    it('keeps the same bubble through the server\'s resync then init, rebuilt from the frames after them', async () => {
      const liveChecklist = ref<ChecklistItem[] | null>(null)
      const deps = onConversation(42, { liveChecklist })
      await mountStream(deps)
      const stream = streamOn(42)
      const items = [{ content: 'Write it', status: 'in_progress', activeForm: 'Writing it' }]
      stream.send({ type: 'init', conversationId: 42 }, '100')
      stream.send({ type: 'token', content: 'stale' }, '101')
      stream.send({ type: 'tool_call', id: 'tc1', name: 'checklist', arguments: JSON.stringify({ items }),
        resultText: 'Checklist updated successfully (1 items).' }, '102')
      await flushPromises()
      const bubble = deps.messages.value.at(-1)!

      // The resync stands first when nothing before the init was dropped.
      stream.send({ type: 'resync' }, '99')
      stream.send({ type: 'init', conversationId: 42 }, '100')
      stream.send({ type: 'token', content: 'The whole reply' }, '130')
      await flushPromises()

      expect(deps.messages.value).toEqual([bubble])
      expect(bubble.content).toBe('The whole reply')
      expect(liveChecklist.value).toEqual(items)
      expect(deps.streaming.value).toBe(true)
    })

    it('drops the replayed calls that the resync\'s reload shows on their saved rows', async () => {
      const deps = onConversation(42)
      let finishReload!: () => void
      deps.mergeSavedRows = vi.fn(async (opts?: { full?: boolean }) => {
        if (!opts?.full) return
        await new Promise<void>((resolve) => {
          finishReload = resolve
        })
        deps.messages.value = [{ id: 5, role: 'assistant', content: null, createdAt: '',
          toolCalls: [{ id: 'tc1', name: 'exec', icon: 'wrench', arguments: '{}', resultText: 'ok', resultStructured: null }] },
        ...deps.messages.value]
      })
      await mountStream(deps)
      const stream = streamOn(42)
      stream.send({ type: 'init', conversationId: 42 }, '100')
      await flushPromises()

      stream.send({ type: 'resync' }, '99')
      stream.send({ type: 'tool_call', id: 'tc1', name: 'exec', arguments: '{}', resultText: 'ok' }, '110')
      stream.send({ type: 'tool_call', id: 'tc2', name: 'exec', arguments: '{}', resultText: 'ok' }, '111')
      expect(deps.messages.value.at(-1)!.toolCalls!.map(tc => tc.id)).toEqual(['tc1', 'tc2'])
      finishReload()
      await flushPromises()

      expect(deps.messages.value.at(-1)!.toolCalls!.map(tc => tc.id)).toEqual(['tc2'])
    })

    it('leaves a turn whose init arrived while the open read was out, whatever that read says', async () => {
      let release!: (status: ConversationQueueStatus) => void
      const deps = onConversation(42, {
        readQueue: vi.fn(() => new Promise<ConversationQueueStatus>((resolve) => {
          release = resolve
        })),
      })
      await mountStream(deps)
      const stream = streamOn(42)
      stream.open()
      stream.send({ type: 'init', conversationId: 42 })
      stream.send({ type: 'token', content: 'Started after the read' })
      release(IDLE)
      await flushPromises()

      expect(contents(deps)).toEqual([['assistant', 'Started after the read']])
      expect(deps.streaming.value).toBe(true)
    })

    it('drops a turn that ended while the stream was away and loads its saved rows instead', async () => {
      const queue = { status: BUSY }
      const deps = onConversation(42, { readQueue: vi.fn(async () => queue.status) })
      await mountStream(deps)
      const stream = streamOn(42)
      await turn(stream, 42, { type: 'token', content: 'Half' })
      ;(deps.mergeSavedRows as ReturnType<typeof vi.fn>).mockClear()

      queue.status = IDLE
      stream.open()
      await flushPromises()

      expect(deps.messages.value).toEqual([])
      expect(deps.streaming.value).toBe(false)
      expect(deps.mergeSavedRows).toHaveBeenCalledTimes(1)
    })

    it('drops an open turn whose terminal it missed when the next turn\'s init arrives', async () => {
      const deps = onConversation(42)
      await mountStream(deps)
      const stream = streamOn(42)
      stream.send({ type: 'init', conversationId: 42 }, '1')
      stream.send({ type: 'token', content: 'Half' }, '2')
      stream.send({ type: 'init', conversationId: 42 }, '9')
      stream.send({ type: 'token', content: 'Next' }, '10')
      await flushPromises()

      expect(contents(deps)).toEqual([['assistant', 'Next']])
    })

    it('reopens a stream the browser gave up on with the last id it got, backing off 5 s doubling to 60 s, ten times', async () => {
      vi.useFakeTimers()
      try {
        const deps = onConversation(42)
        await mountStream(deps)
        const first = streamOn(42)
        first.open()
        first.send({ type: 'init', conversationId: 42 }, '1700000000000001')
        first.send({ type: 'token', content: 'Half' }, '1700000000000002')
        await vi.advanceTimersByTimeAsync(0)
        first.fail()

        const delays: number[] = []
        for (const delay of [5, 10, 20, 40, 60, 60, 60, 60, 60, 60]) {
          await vi.advanceTimersByTimeAsync(delay * 1000 - 1)
          expect(openStreamsOn(42)).toEqual([])
          await vi.advanceTimersByTimeAsync(1)
          const retry = streamOn(42)
          expect(retry.url).toBe('/api/conversations/42/stream?lastEventId=1700000000000002')
          delays.push(delay)
          retry.fail()
        }
        expect(delays).toHaveLength(10)

        await vi.advanceTimersByTimeAsync(600_000)
        expect(FakeEventSource.all).toHaveLength(11)
        expect(openStreamsOn(42)).toEqual([])
        // Given up: the turn is dropped, so the Working… line and the poller show it from here.
        expect(deps.streaming.value).toBe(false)
        expect(deps.messages.value).toEqual([])
        expect(deps.mergeSavedRows).toHaveBeenLastCalledWith({ pairable: expect.any(Function) })
      }
      finally {
        vi.useRealTimers()
      }
    })
  })

  describe('a stream that gave up', () => {
    /** Fails the stream on 42 and each of its ten retries, as an error status would. */
    async function giveUp() {
      streamOn(42).fail()
      for (const delay of [5, 10, 20, 40, 60, 60, 60, 60, 60, 60]) {
        await vi.advanceTimersByTimeAsync(delay * 1000)
        streamOn(42).fail()
      }
      expect(openStreamsOn(42)).toEqual([])
    }

    it('starts its backoff over once a retry opens', async () => {
      vi.useFakeTimers()
      try {
        const deps = onConversation(42)
        await mountStream(deps)
        streamOn(42).fail()
        await vi.advanceTimersByTimeAsync(5000)
        streamOn(42).open()
        streamOn(42).fail()

        await vi.advanceTimersByTimeAsync(4999)
        expect(openStreamsOn(42)).toEqual([])
        await vi.advanceTimersByTimeAsync(1)
        expect(openStreamsOn(42)).toHaveLength(1)
      }
      finally {
        vi.useRealTimers()
      }
    })

    it('opens again when a send there is answered', async () => {
      vi.useFakeTimers()
      try {
        serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
        const streamUp = ref(false)
        const deps = onConversation(42, { streamUp })
        const { api } = await mountStream(deps)
        expect(streamUp.value).toBe(true)
        await giveUp()
        expect(streamUp.value).toBe(false)

        deps.input.value = 'hello?'
        await api.sendMessage()
        await vi.advanceTimersByTimeAsync(0)

        expect(openStreamsOn(42)).toHaveLength(1)
        expect(streamUp.value).toBe(true)
      }
      finally {
        vi.useRealTimers()
      }
    })

    it('opens again when the event bus reconnects', async () => {
      vi.useFakeTimers()
      try {
        const deps = onConversation(42)
        await mountStream(deps)
        await giveUp()

        for (const handler of bus.openHandlers) handler()

        expect(openStreamsOn(42)).toHaveLength(1)
      }
      finally {
        vi.useRealTimers()
      }
    })
  })

  describe('leaving and Stop', () => {
    it('closes the stream on a switch and on unmount, without stopping the turn', async () => {
      const stopped = vi.fn(() => ({ stopped: true }))
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: stopped })
      const deps = onConversation(42)
      const { wrapper } = await mountStream(deps)
      await turn(streamOn(42), 42, { type: 'token', content: 'working' })

      deps.selectedConvoId.value = 7
      expect(openStreamsOn(42)).toEqual([])
      expect(deps.streaming.value).toBe(false)

      deps.followedConvoId.value = 7
      const seven = streamOn(7)
      wrapper.unmount()
      expect(seven.closed).toBe(true)
      await flushPromises()
      expect(stopped).not.toHaveBeenCalled()
    })

    it('posts the stop endpoint of the conversation the stream runs in, and the stopped frame ends the bubble', async () => {
      // As the server does: the stopped frame is published before the stop answers.
      const stopped = vi.fn(() => {
        streamOn(42).send({ type: 'stopped' })
        return { stopped: true }
      })
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: stopped })
      const deps = onConversation(42)
      const { api } = await mountStream(deps)
      await turn(streamOn(42), 42, { type: 'token', content: 'long task' })

      api.stopStreaming()
      await vi.waitFor(() => expect(deps.readQueue).toHaveBeenLastCalledWith())
      expect(stopped).toHaveBeenCalledTimes(1)
      expect(deps.focusInput).toHaveBeenCalled()
      expect(deps.streaming.value).toBe(false)
      expect(contents(deps)).toEqual([['assistant', 'long task\n\n_(stopped)_']])
    })

    it('ends the turn stopped when the stop answers that it stopped and its frame has not come', async () => {
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: () => ({ stopped: true }) })
      const deps = onConversation(42, { readQueue: vi.fn(async () => BUSY) })
      const { api } = await mountStream(deps)
      await turn(streamOn(42), 42, { type: 'token', content: 'long task' })

      api.stopStreaming()

      await vi.waitFor(() => expect(deps.streaming.value).toBe(false))
      expect(contents(deps)).toEqual([['assistant', 'long task\n\n_(stopped)_']])
      streamOn(42).send({ type: 'stopped' })
      expect(contents(deps)).toEqual([['assistant', 'long task\n\n_(stopped)_']])
    })

    it('drops the turn after a stop that found it already over, when its terminal frame never comes', async () => {
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: () => ({ stopped: false }) })
      const queue = { status: BUSY }
      const deps = onConversation(42, { readQueue: vi.fn(async () => queue.status) })
      const { api } = await mountStream(deps)
      await turn(streamOn(42), 42, { type: 'token', content: 'long task' })

      queue.status = IDLE
      api.stopStreaming()

      await vi.waitFor(() => expect(deps.streaming.value).toBe(false))
      expect(deps.messages.value).toEqual([])
      expect(deps.mergeSavedRows).toHaveBeenLastCalledWith({ pairable: expect.any(Function) })
    })

    it('is a no-op when no turn is open', async () => {
      const stopped = vi.fn(() => ({ stopped: true }))
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: stopped })
      const deps = onConversation(42)
      const { api } = await mountStream(deps)
      api.stopStreaming()
      await flushPromises()
      expect(stopped).not.toHaveBeenCalled()
      expect(deps.focusInput).not.toHaveBeenCalled()
    })

    it('makes a send after Stop wait for the stop to finish', async () => {
      let release!: () => void
      const gate = new Promise<void>((resolve) => {
        release = resolve
      })
      registerEndpoint('/api/conversations/42/stop', {
        method: 'POST',
        handler: async () => {
          await gate
          return { stopped: true }
        },
      })
      const bodies = serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
      const deps = onConversation(42)
      const { api } = await mountStream(deps)
      await turn(streamOn(42), 42, { type: 'token', content: 'long task' })

      api.stopStreaming()
      deps.input.value = 'next'
      const sent = api.sendMessage()
      await flushPromises()
      expect(bodies).toEqual([])
      release()
      await sent
      await flushPromises()
      expect(bodies.map(b => b.message)).toEqual(['next'])
    })

    it('still sends when the stop POST fails', async () => {
      registerEndpoint('/api/conversations/42/stop', {
        method: 'POST',
        handler: () => {
          throw new Error('boom')
        },
      })
      vi.spyOn(console, 'error').mockImplementation(() => {})
      const bodies = serveTurnStart(() => ({ status: 'started', conversationId: 42 }))
      const deps = onConversation(42)
      const { api } = await mountStream(deps)
      await turn(streamOn(42), 42, { type: 'token', content: 'long task' })

      api.stopStreaming()
      await send(api, deps, 'next')
      expect(bodies.map(b => b.message)).toEqual(['next'])
    })
  })
})
