import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { Message } from '~/types/api'
import { useChatStream, type UseChatStream, type UseChatStreamDeps } from '~/composables/useChatStream'

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
  })
})
