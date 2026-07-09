import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'

/**
 * JCLAW-314 — pages/chat.vue critical user-flow coverage.
 *
 * The existing chat.page.test.ts covers tool-call rendering, subagent
 * transcripts, async-announce polling, attachment gates, and truncation
 * markers. This sibling spec covers the still-uncovered user flows:
 *
 *   - Sending a message: form submit triggers POST /api/chat/stream
 *     with the expected JSON body.
 *   - Conversation switch: loadConversation populates message list and
 *     clears the input.
 *   - Image attachment: addAttachments produces a pending attachment when
 *     the model advertises supportsVision.
 */

function setupChatApi() {
  // Pin a vision-capable model so attachment AC paths can run.
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'vision-agent', modelProvider: 'ollama-cloud', modelId: 'qwen2.5-vl',
      enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama-cloud.models', value:
        '[{"id":"qwen2.5-vl","name":"Qwen 2.5 VL","supportsThinking":false,"supportsVision":true}]' },
    ],
  }))
  registerEndpoint('/api/conversations', () => [])
}

beforeEach(() => {
  // The chat composable layer caches conversation lists via useFetch; flush
  // so each case starts with the test-local fixture.
  clearNuxtData()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Chat page — POST /api/chat/stream on form submit', () => {
  it('issues a POST to /api/chat/stream with the typed text and selected agent', async () => {
    setupChatApi()

    // The page uses native globalThis.fetch for the streaming endpoint
    // (not $fetch — streaming bodies need ReadableStream). Stub it to a
    // valid no-op stream so the post-stream flow exits cleanly without
    // throwing on parsing nothing.
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      // Synthesize a minimal stream: an init frame then [DONE]. The chat
      // page parses lines starting with 'data: '; anything else is ignored.
      const encoder = new TextEncoder()
      const body = new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('data: {"type":"init","conversationId":42}\n'))
          controller.enqueue(encoder.encode('data: {"type":"done"}\n'))
          controller.close()
        },
      })
      return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    })

    const component = await mountSuspended(Chat)
    await flushPromises()

    // Type a message into the textarea, then submit the form. The form's
    // @submit.prevent handler calls sendMessage().
    const textarea = component.find<HTMLTextAreaElement>('textarea')
    await textarea.setValue('hello world')
    const form = component.find('form')
    expect(form.exists()).toBe(true)
    await form.trigger('submit.prevent')
    await flushPromises()

    // Look for the chat-stream POST in the spy history. There may be other
    // fetches (e.g. /api/chat/upload short-circuits when no attachments),
    // so filter on URL.
    const streamCall = fetchSpy.mock.calls.find((call) => {
      const url = String(call[0] ?? '')
      return url.includes('/api/chat/stream')
    })
    expect(streamCall).toBeTruthy()
    const init = streamCall![1] as RequestInit
    expect(init.method).toBe('POST')
    const body = JSON.parse(init.body as string) as Record<string, unknown>
    expect(body.message).toBe('hello world')
    expect(body.agentId).toBe(1)
  })

  it('clears the textarea after a successful submit', async () => {
    setupChatApi()
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
      const encoder = new TextEncoder()
      const body = new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('data: {"type":"init","conversationId":42}\n'))
          controller.enqueue(encoder.encode('data: {"type":"done"}\n'))
          controller.close()
        },
      })
      return new Response(body, { status: 200 })
    })

    const component = await mountSuspended(Chat)
    await flushPromises()

    const textarea = component.find<HTMLTextAreaElement>('textarea')
    await textarea.setValue('clear-after-send')
    await component.find('form').trigger('submit.prevent')
    await flushPromises()
    // sendMessage zeroes input.value before the stream fires; observe.
    expect(textarea.element.value).toBe('')
  })

  it('does not POST when the input is empty and no attachments are queued', async () => {
    setupChatApi()
    // Always provide a mock implementation — without one, vi.spyOn falls
    // through to the real fetch and can hit the network in jsdom.
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () =>
      new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } }))

    const component = await mountSuspended(Chat)
    await flushPromises()

    // Submit an empty form. The early-return in sendMessage guards on
    // !rawText && !attachedFiles.value.length, so no /api/chat/stream POST
    // should fire.
    await component.find('form').trigger('submit.prevent')
    await flushPromises()

    const streamCall = fetchSpy.mock.calls.find(call =>
      String(call[0] ?? '').includes('/api/chat/stream'))
    expect(streamCall).toBeUndefined()
  })
})

describe('Chat page — conversation switch via loadConversation', () => {
  it('loads the target conversation messages and renders the user/assistant turns', async () => {
    setupChatApi()
    registerEndpoint('/api/conversations/99/messages', () => [
      { id: 5000, role: 'user', content: 'how do I configure skills?',
        createdAt: '2026-05-15T10:00:00Z' },
      { id: 5001, role: 'assistant', content: 'Configure skills in /settings/skills.',
        createdAt: '2026-05-15T10:00:01Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      messages: Array<{ role: string, content?: string | null }>
    }
    await vm.loadConversation(99)
    await flushPromises()

    // Both turns landed in the message list.
    expect(vm.messages.length).toBe(2)
    expect(vm.messages[0]!.role).toBe('user')
    expect(vm.messages[0]!.content).toBe('how do I configure skills?')
    expect(vm.messages[1]!.role).toBe('assistant')
    expect(vm.messages[1]!.content).toBe('Configure skills in /settings/skills.')

    // Page DOM also shows them — the message list iterates messages.
    const html = component.html()
    expect(html).toContain('how do I configure skills?')
    expect(html).toContain('Configure skills in /settings/skills.')
  })

  it('replaces the message list when switching from one conversation to another', async () => {
    setupChatApi()
    registerEndpoint('/api/conversations/100/messages', () => [
      { id: 6000, role: 'user', content: 'first conversation only',
        createdAt: '2026-05-15T09:00:00Z' },
    ])
    registerEndpoint('/api/conversations/101/messages', () => [
      { id: 7000, role: 'user', content: 'second conversation only',
        createdAt: '2026-05-15T10:00:00Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      messages: Array<{ role: string, content?: string | null }>
    }

    await vm.loadConversation(100)
    await flushPromises()
    expect(component.text()).toContain('first conversation only')

    // Switching to 101 must REPLACE the loaded list — not append.
    await vm.loadConversation(101)
    await flushPromises()
    expect(component.text()).toContain('second conversation only')
    expect(component.text()).not.toContain('first conversation only')
  })
})

describe('Chat page — image attachment on a vision-capable model', () => {
  beforeEach(() => {
    // jsdom doesn't implement URL.createObjectURL; the chat page calls it to
    // build thumbnail preview URLs. Stub it with a no-op that returns a fake
    // blob URL — the assertion is on the file queue, not on the preview map.
    vi.spyOn(URL, 'createObjectURL').mockImplementation(() => 'blob:test-fake')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
  })

  it('queues an image attachment when the active model advertises supportsVision', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      addAttachments: (files: File[]) => void
      attachError: string | null
      attachedFiles: File[]
    }
    const png = new File([new Uint8Array([0x89, 0x50, 0x4E, 0x47])], 'snap.png', { type: 'image/png' })
    vm.addAttachments([png])
    await flushPromises()

    expect(vm.attachError).toBeNull()
    expect(vm.attachedFiles.length).toBe(1)
    expect(vm.attachedFiles[0]!.name).toBe('snap.png')
  })

  it('renders a paperclip chip with the attached filename so the user sees the pending upload', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      addAttachments: (files: File[]) => void
    }
    const png = new File([new Uint8Array([0x89, 0x50, 0x4E, 0x47])], 'attached.png', { type: 'image/png' })
    vm.addAttachments([png])
    await flushPromises()

    // The composer renders one chip per attachedFile with the filename
    // visible. Probe the DOM directly to catch a template regression.
    expect(component.html()).toContain('attached.png')
  })
})

describe('Chat page — image-gen progress bar is scoped to the generate_image turn (JCLAW-683)', () => {
  // Bug: the progress bar polled /api/imagegen/progress on EVERY streaming
  // turn, so a real generation's 0%-load phase leaked onto unrelated turns.
  // Fix: polling starts only when a turn fires a generate_image tool call, and
  // the bar is keyed to that turn.

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

  async function sendOnce(component: VueWrapper, text: string) {
    const textarea = component.find<HTMLTextAreaElement>('textarea')
    await textarea.setValue(text)
    await component.find('form').trigger('submit.prevent')
    await flushPromises()
  }

  it('does NOT poll /api/imagegen/progress on a turn with no generate_image tool call', async () => {
    setupChatApi()
    let progressPolls = 0
    registerEndpoint('/api/imagegen/progress', () => {
      progressPolls++
      return { percent: 40 }
    })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"token","content":"hi"}\n',
      'data: {"type":"complete","content":"hi"}\n',
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    await sendOnce(component, 'just chat, no image')

    expect(progressPolls).toBe(0)
    // And the transient bar never appears.
    expect(component.html()).not.toContain('Generating image')
  })

  it('polls /api/imagegen/progress once a generate_image tool call fires in the turn', async () => {
    setupChatApi()
    let progressPolls = 0
    registerEndpoint('/api/imagegen/progress', () => {
      progressPolls++
      return { percent: 40 }
    })
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"tool_call","id":"tc1","name":"generate_image","arguments":"{\\"prompt\\":\\"a cat\\"}"}\n',
      'data: {"type":"complete","content":"here is your cat"}\n',
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    await sendOnce(component, 'draw me a cat')

    // The immediate poll fired synchronously when the generate_image tool call
    // was dispatched during the stream.
    expect(progressPolls).toBeGreaterThanOrEqual(1)
  })

  it('does not leak polling across turns: a plain turn after a generate_image turn adds no polls', async () => {
    setupChatApi()
    let progressPolls = 0
    registerEndpoint('/api/imagegen/progress', () => {
      progressPolls++
      return { percent: 40 }
    })

    const component = await mountSuspended(Chat)
    await flushPromises()

    // Turn 1: generate_image → polls.
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"tool_call","id":"tc1","name":"generate_image","arguments":"{}"}\n',
      'data: {"type":"complete","content":"done"}\n',
    ])
    await sendOnce(component, 'make an image')
    const afterTurn1 = progressPolls
    expect(afterTurn1).toBeGreaterThanOrEqual(1)
    vi.restoreAllMocks()

    // Turn 2: plain turn — polling must not resume (it was torn down at turn 1 end).
    streamWith([
      'data: {"type":"init","conversationId":42}\n',
      'data: {"type":"token","content":"ok"}\n',
      'data: {"type":"complete","content":"ok"}\n',
    ])
    await sendOnce(component, 'now just talk')
    expect(progressPolls).toBe(afterTurn1)
  })
})

describe('Chat page — model selector re-syncs after a mid-turn model switch', () => {
  // Regression: the agent can rewrite its own model during a turn via the
  // jclaw_api tool (PUT /api/agents/{id}). The header selector reads the
  // agent through effectiveModel, but the post-stream finally block only
  // refreshed conversations — never agents — so the dropdown stayed pinned
  // to the pre-switch model until a manual reload.
  it('reflects the agent model changed by the jclaw_api tool once the turn completes', async () => {
    // The /api/agents handler reads a mutable var so a mid-turn switch is
    // observable when the finally block calls refreshAgents().
    let agentModelId = 'kimi-k2.5'
    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: agentModelId,
        enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
    ])
    registerEndpoint('/api/config', () => ({
      entries: [
        { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
        { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
        { key: 'provider.ollama-cloud.models', value:
          '[{"id":"kimi-k2.5","name":"Kimi K2.5"},{"id":"kimi-k2.6","name":"Kimi K2.6"}]' },
      ],
    }))
    registerEndpoint('/api/conversations', () => [])

    // Simulate the tool's server-side persist: flip the agent's model when
    // the stream POST fires, mimicking the jclaw_api PUT committing mid-turn.
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
      if (String(input ?? '').includes('/api/chat/stream')) {
        agentModelId = 'kimi-k2.6'
      }
      const encoder = new TextEncoder()
      const body = new ReadableStream({
        start(controller) {
          controller.enqueue(encoder.encode('data: {"type":"init","conversationId":42}\n'))
          controller.enqueue(encoder.encode('data: {"type":"done"}\n'))
          controller.close()
        },
      })
      return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    })

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as { selectedModelKey: string }

    // Baseline: the header shows the agent's starting model.
    expect(vm.selectedModelKey).toBe('ollama-cloud::kimi-k2.5')

    const textarea = component.find<HTMLTextAreaElement>('textarea')
    await textarea.setValue('switch your model please')
    await component.find('form').trigger('submit.prevent')
    await flushPromises()
    await flushPromises() // let the finally block's refreshAgents() resolve

    // The selector now reflects the mid-turn switch — no reload required.
    expect(vm.selectedModelKey).toBe('ollama-cloud::kimi-k2.6')
  })
})
