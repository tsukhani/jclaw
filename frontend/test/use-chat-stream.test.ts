import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { ChecklistItem, Message } from '~/types/api'
import { useChatStream, type UseChatStream, type UseChatStreamDeps } from '~/composables/useChatStream'
import ChatToolCalls from '~/components/chat/ChatToolCalls.vue'

// The stream endpoint uses native globalThis.fetch (ReadableStream body), so
// stub it with an SSE frame list; everything else returns empty JSON.
function streamWith(frames: string[]) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    if (!String(input ?? '').includes('/api/chat/stream')) {
      return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    const encoder = new TextEncoder()
    const body = new ReadableStream({
      start(controller) {
        for (const f of frames) controller.enqueue(encoder.encode(f))
        controller.close()
      },
    })
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  })
}

// The first stream announces conversationId (when given) and stays open until its request is
// aborted, as a turn does while the user decides to Stop; later sends answer with laterFrames.
function streamOpenThen(conversationId: number | null, laterFrames: string[] = []) {
  let calls = 0
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    if (!String(input ?? '').includes('/api/chat/stream')) {
      return new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    const encoder = new TextEncoder()
    const first = calls++ === 0
    const signal = (init as RequestInit | undefined)?.signal
    const body = new ReadableStream({
      start(controller) {
        if (first) {
          if (conversationId !== null) {
            controller.enqueue(encoder.encode(`data: {"type":"init","conversationId":${conversationId}}\n`))
          }
          signal?.addEventListener('abort', () => controller.error(new DOMException('aborted', 'AbortError')))
          return
        }
        for (const f of laterFrames) controller.enqueue(encoder.encode(f))
        controller.close()
      },
    })
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
  })
}

beforeEach(() => {
  registerEndpoint('/api/conversations/42/queue', () => ({ busy: false }))
})

afterEach(() => {
  vi.restoreAllMocks()
})

function makeDeps(over: Partial<UseChatStreamDeps> = {}) {
  const messages = shallowRef<Message[]>([])
  const base: UseChatStreamDeps = {
    messages,
    selectedConvoId: ref<number | null>(null),
    subagentTranscript: ref(null),
    selectedAgentId: ref<number | null>(1),
    streaming: ref(false),
    streamReasoning: ref(''),
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
    reconcileMessageIds: vi.fn(async () => {}),
    refreshConversations: vi.fn(),
    refreshAgents: vi.fn(),
    ...over,
  }
  return base
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

describe('useChatStream', () => {
  describe('sendMessage guards', () => {
    it('does nothing while already streaming', async () => {
      const deps = makeDeps({ streaming: ref(true), input: ref('hi') })
      const fetchSpy = streamWith([])
      const { api } = await mountStream(deps)
      await api.sendMessage()
      expect(fetchSpy).not.toHaveBeenCalled()
      expect(deps.messages.value).toHaveLength(0)
    })

    it('does nothing in a read-only subagent transcript', async () => {
      const deps = makeDeps({ subagentTranscript: ref({ agentId: 9, agentName: 'x' }), input: ref('hi') })
      const fetchSpy = streamWith([])
      const { api } = await mountStream(deps)
      await api.sendMessage()
      expect(fetchSpy).not.toHaveBeenCalled()
    })

    it('does nothing with no text and no attachments', async () => {
      const deps = makeDeps({ input: ref('   ') })
      const fetchSpy = streamWith([])
      const { api } = await mountStream(deps)
      await api.sendMessage()
      expect(fetchSpy).not.toHaveBeenCalled()
    })
  })

  it('pushes an optimistic user bubble + assistant reply and streams content in', async () => {
    const deps = makeDeps({ input: ref('hello there') })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"token","content":"Hi "}\n',
      'data: {"type":"token","content":"back"}\n',
      'data: {"type":"complete","content":"Hi back"}\n',
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    expect(deps.messages.value).toHaveLength(2)
    expect(deps.messages.value[0]).toMatchObject({ role: 'user', content: 'hello there' })
    expect(deps.messages.value[1]).toMatchObject({ role: 'assistant', content: 'Hi back' })
    expect(deps.selectedConvoId.value).toBe(42) // set from the init frame
    expect(deps.streaming.value).toBe(false) // finally reset
    expect(deps.input.value).toBe('') // composer cleared
    expect(deps.scrollToBottom).toHaveBeenCalled()
    expect(deps.refreshConversations).toHaveBeenCalled()
    expect(deps.refreshAgents).toHaveBeenCalled()
  })

  it('starts browser-setup polling on the turn that calls the browser, before the call finishes', async () => {
    const deps = makeDeps({ input: ref('open the page') })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"status","content":"Using tool: web_fetch"}\n',
      'data: {"type":"status","content":"Using tool: browser"}\n',
      'data: {"type":"complete","content":"done"}\n',
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    // Once, for the browser status only — web_fetch never downloads a browser.
    expect(deps.startBrowserSetupPolling).toHaveBeenCalledTimes(1)
    expect(deps.browserSetupTurnKey.value).toBe(deps.messages.value[1]!._key)
  })

  it('sends fresh-chat picks with the first message and never with a follow-up (JCLAW-1196)', async () => {
    const pending = ref<{ modelProvider?: string, modelId?: string, thinkingMode?: string } | null>(
      { modelProvider: 'anthropic', modelId: 'opus', thinkingMode: 'high' })
    const deps = makeDeps({ input: ref('first'), pendingOverrides: pending })
    const fetchSpy = streamWith(['data: {"type":"init","conversationId":42}\n', 'data: {"type":"complete","content":"ok"}\n'])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()
    const first = JSON.parse(String(fetchSpy.mock.calls.find(c => String(c[0]).includes('/api/chat/stream'))![1]!.body))
    expect(first).toMatchObject({ conversationId: null, modelProvider: 'anthropic', modelId: 'opus', thinkingMode: 'high' })

    // The conversation now carries the overrides; a follow-up names it and sends no picks.
    deps.input.value = 'second'
    fetchSpy.mockClear()
    await api.sendMessage()
    await flushPromises()
    const second = JSON.parse(String(fetchSpy.mock.calls.find(c => String(c[0]).includes('/api/chat/stream'))![1]!.body))
    expect(second.conversationId).toBe(42)
    expect(second).not.toHaveProperty('modelProvider')
    expect(second).not.toHaveProperty('thinkingMode')
  })

  it('takes the router\'s route frame onto the reply instead of showing it as status text (JCLAW-1222)', async () => {
    const deps = makeDeps({ input: ref('hello') })
    const route = { class: 'chat', provider: 'ollama-cloud', model: 'glm-5.3-flash', reason: 'no task markers' }
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      `data: ${JSON.stringify({ type: 'status', content: JSON.stringify({ route }) })}\n`,
      `data: ${JSON.stringify({ type: 'status', content: JSON.stringify({ route: { ...route, provider: 'openrouter', failover: true } }) })}\n`,
      'data: {"type":"token","content":"hi"}\n',
      'data: {"type":"complete","content":"hi"}\n',
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    const assistant = deps.messages.value[1]!
    expect(assistant._route).toMatchObject({ provider: 'openrouter', model: 'glm-5.3-flash', failover: true })
  })

  it('takes each context frame onto the reply so the meter moves before the turn ends', async () => {
    const deps = makeDeps({ input: ref('hello') })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      `data: ${JSON.stringify({ type: 'status', content: '{"contextTokens":116155}' })}\n`,
      `data: ${JSON.stringify({ type: 'status', content: '{"contextTokens":131072}' })}\n`,
      'data: {"type":"token","content":"hi"}\n',
      'data: {"type":"complete","content":"hi"}\n',
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    expect(deps.messages.value[1]!._contextTokens).toBe(131072)
  })

  it('stamps the reasoning→content transition (collapses the thinking card once)', async () => {
    const deps = makeDeps({ input: ref('think then answer') })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"reasoning","content":"let me think"}\n',
      'data: {"type":"token","content":"answer"}\n',
      'data: {"type":"complete","content":"answer"}\n',
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    const assistant = deps.messages.value[1] as Message & { thinkingCollapsed?: boolean }
    expect(assistant.reasoning).toBe('let me think')
    expect(assistant.content).toBe('answer')
    expect(assistant.thinkingCollapsed).toBe(true) // transition fired
  })

  it('starts image-gen progress polling when a generate_image tool call fires', async () => {
    const deps = makeDeps({ input: ref('draw a cat') })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"tool_call","id":"tc1","name":"generate_image","arguments":"{}"}\n',
      'data: {"type":"complete","content":"done"}\n',
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    expect(deps.startImageProgressPolling).toHaveBeenCalled()
    const assistant = deps.messages.value[1]
    expect(assistant!.toolCalls).toHaveLength(1)
    expect(assistant!.toolCalls![0]!.name).toBe('generate_image')
  })

  it('starts image-gen progress polling for a generate_image call made through tool_invoke, not for another tool', async () => {
    const frame = (o: object) => `data: ${JSON.stringify(o)}\n`
    const invoke = (tool: string) => [
      frame({ type: 'init', conversationId: 42 }),
      frame({ type: 'tool_call', id: 'tc1', name: 'tool_invoke', arguments: JSON.stringify({ tool, args: { prompt: 'a cat' } }) }),
      frame({ type: 'complete', content: 'done' }),
    ]

    const image = makeDeps({ input: ref('draw a cat') })
    streamWith(invoke('generate_image'))
    await (await mountStream(image)).api.sendMessage()
    await flushPromises()
    expect(image.startImageProgressPolling).toHaveBeenCalledTimes(1)

    const other = makeDeps({ input: ref('count things') })
    streamWith(invoke('generate_video'))
    await (await mountStream(other)).api.sendMessage()
    await flushPromises()
    expect(other.startImageProgressPolling).not.toHaveBeenCalled()
  })

  it('draws the view a live tool_call frame carries', async () => {
    const deps = makeDeps({ input: ref('a search table please') })
    const frame = (o: object) => `data: ${JSON.stringify(o)}\n`
    const mcpApp = { server: 'serpapi', tool: 'search_table', resourceUri: 'ui://prefab/tool/583d325b0e9c/renderer.html',
      arguments: { params: { q: 'cats' } }, result: { content: [{ type: 'text', text: '3 results' }], isError: false } }
    streamWith([
      frame({ type: 'init', conversationId: 42 }),
      frame({ type: 'tool_call', id: 'tc1', name: 'mcp_serpapi', icon: 'plug', arguments: '{"tool":"search_table"}',
        resultText: '3 results', resultStructured: { mcpApp } }),
      frame({ type: 'complete', content: 'Here is the table.' }),
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()

    const calls = deps.messages.value[1]!.toolCalls!
    expect(calls[0]!.resultStructured).toEqual({ mcpApp })
    const card = await mountSuspended(ChatToolCalls, { props: { toolCalls: calls, collapsed: false, mcpApps: true } })
    expect(card.findAll('[data-testid="mcp-app-frame"] iframe').map(f => f.attributes('src')))
      .toEqual(['/api/mcp-servers/view?server=serpapi&uri=ui%3A%2F%2Fprefab%2Ftool%2F583d325b0e9c%2Frenderer.html'])
  })

  it('takes the live checklist from a successful checklist call and drops it when the next send starts', async () => {
    const liveChecklist = ref<ChecklistItem[] | null>(null)
    const deps = makeDeps({ input: ref('plan it'), liveChecklist })
    const items = [{ content: 'Run tests', status: 'in_progress', activeForm: 'Running tests' }]
    const frame = (o: object) => `data: ${JSON.stringify(o)}\n`
    streamWith([
      frame({ type: 'init', conversationId: 42 }),
      frame({ type: 'tool_call', id: 'tc1', name: 'checklist', arguments: JSON.stringify({ items }),
        resultText: 'Checklist updated successfully (1 items).' }),
      frame({ type: 'complete', content: 'done' }),
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()
    expect(liveChecklist.value).toEqual(items)

    streamWith([frame({ type: 'init', conversationId: 42 }), frame({ type: 'complete', content: 'ok' })])
    deps.input.value = 'next'
    await api.sendMessage()
    await flushPromises()
    expect(liveChecklist.value).toBeNull()
  })

  it('leaves the live checklist alone when the tool refused the call', async () => {
    const liveChecklist = ref<ChecklistItem[] | null>(null)
    const deps = makeDeps({ input: ref('plan it'), liveChecklist })
    const frame = (o: object) => `data: ${JSON.stringify(o)}\n`
    streamWith([
      frame({ type: 'init', conversationId: 42 }),
      frame({ type: 'tool_call', id: 'tc1', name: 'checklist', arguments: '{"items":[]}',
        resultText: 'Error: At most one item may be in_progress. Found 2.' }),
      frame({ type: 'complete', content: 'done' }),
    ])
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()
    expect(liveChecklist.value).toBeNull()
  })

  it('appends a (stopped) marker when the stream aborts', async () => {
    const deps = makeDeps({ input: ref('go') })
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      const err = new Error('aborted')
      err.name = 'AbortError'
      throw err
    })
    const { api } = await mountStream(deps)
    await api.sendMessage()
    await flushPromises()
    expect(deps.messages.value[1]!.content).toContain('stopped')
    expect(deps.streaming.value).toBe(false)
  })

  describe('stopStreaming', () => {
    it('resets streaming state and refocuses the composer', async () => {
      const deps = makeDeps({ streaming: ref(true) })
      const { api } = await mountStream(deps)
      api.stopStreaming()
      expect(deps.streaming.value).toBe(false)
      expect(deps.focusInput).toHaveBeenCalled()
    })

    it('is a no-op when not streaming', async () => {
      const deps = makeDeps({ streaming: ref(false) })
      const { api } = await mountStream(deps)
      api.stopStreaming()
      expect(deps.focusInput).not.toHaveBeenCalled()
    })

    it('posts the stop endpoint of the conversation the stream runs in', async () => {
      const stopped = vi.fn(() => ({ stopped: true }))
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: stopped })
      streamOpenThen(42)
      const deps = makeDeps({ selectedConvoId: ref<number | null>(42), input: ref('long task') })
      const { api } = await mountStream(deps)
      void api.sendMessage()
      await flushPromises()
      api.stopStreaming()
      await flushPromises()
      expect(stopped).toHaveBeenCalledTimes(1)
    })

    it('stops the streaming conversation after the user switched to another one', async () => {
      const streamed = vi.fn(() => ({ stopped: true }))
      const onScreen = vi.fn(() => ({ stopped: false }))
      registerEndpoint('/api/conversations/42/stop', { method: 'POST', handler: streamed })
      registerEndpoint('/api/conversations/7/stop', { method: 'POST', handler: onScreen })
      streamOpenThen(42)
      const deps = makeDeps({ selectedConvoId: ref<number | null>(42), input: ref('long task') })
      const { api } = await mountStream(deps)
      void api.sendMessage()
      await flushPromises()
      deps.selectedConvoId.value = 7
      api.stopStreaming()
      await flushPromises()
      expect(streamed).toHaveBeenCalledTimes(1)
      expect(onScreen).not.toHaveBeenCalled()
    })

    it('posts nothing before the stream knows its conversation', async () => {
      const stopped = vi.fn(() => ({ stopped: true }))
      registerEndpoint('/api/conversations/null/stop', { method: 'POST', handler: stopped })
      registerEndpoint('/api/conversations/undefined/stop', { method: 'POST', handler: stopped })
      streamOpenThen(null)
      const deps = makeDeps({ selectedConvoId: ref<number | null>(null), input: ref('first message') })
      const { api } = await mountStream(deps)
      void api.sendMessage()
      await flushPromises()
      api.stopStreaming()
      await flushPromises()
      expect(stopped).not.toHaveBeenCalled()
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
      const fetchSpy = streamOpenThen(42, ['data: {"type":"complete","content":"fresh"}\n'])
      const deps = makeDeps({ selectedConvoId: ref<number | null>(42), input: ref('long task') })
      const { api } = await mountStream(deps)
      void api.sendMessage()
      await flushPromises()
      api.stopStreaming()
      deps.input.value = 'next'
      const sent = api.sendMessage()
      await flushPromises()
      const streamCalls = () => fetchSpy.mock.calls.filter(c => String(c[0]).includes('/api/chat/stream'))
      expect(streamCalls()).toHaveLength(1)
      release()
      await sent
      await flushPromises()
      expect(streamCalls()).toHaveLength(2)
    })

    it('still sends when the stop POST fails', async () => {
      registerEndpoint('/api/conversations/42/stop', {
        method: 'POST',
        handler: () => {
          throw new Error('boom')
        },
      })
      vi.spyOn(console, 'error').mockImplementation(() => {})
      const fetchSpy = streamOpenThen(42, ['data: {"type":"complete","content":"fresh"}\n'])
      const deps = makeDeps({ selectedConvoId: ref<number | null>(42), input: ref('long task') })
      const { api } = await mountStream(deps)
      void api.sendMessage()
      await flushPromises()
      api.stopStreaming()
      deps.input.value = 'next'
      await api.sendMessage()
      await flushPromises()
      expect(fetchSpy.mock.calls.filter(c => String(c[0]).includes('/api/chat/stream'))).toHaveLength(2)
      expect(deps.messages.value.at(-1)!.content).toBe('fresh')
    })
  })
})
