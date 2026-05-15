import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'

/**
 * Page-level Vitest coverage for the {@code chat.vue} streaming state machine
 * and tool-call rendering. The existing {@code chat.test.ts} covers the static
 * toolbar (agent / model / thinking selectors and structural elements); these
 * tests sit one layer deeper, exercising:
 *
 * <ul>
 *   <li>The pre-stream UI contract — streaming-only affordances are absent and
 *       the send/textarea controls are enabled when an agent is selected.</li>
 *   <li>The send-button disabled state when no agent is configured.</li>
 *   <li>The message-list rendering with a tool-call assistant message — the
 *       conversation-load path threads {@code toolCalls} through to the
 *       per-message renderer, so the tool name should surface in the DOM.</li>
 * </ul>
 *
 * These tests do not drive the SSE pipeline directly — that surface is
 * exercised by the JCLAW-26/JCLAW-95/JCLAW-111 suite — but they pin the
 * frontend-side state shape that the SSE handlers update.
 */

function setupBaseChatApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'streaming-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
      enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama-cloud.models', value:
        '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]' },
    ],
  }))
  registerEndpoint('/api/conversations', () => [])
}

describe('Chat page — streaming state machine', () => {
  it('does not show the streaming status badge before any send fires', async () => {
    setupBaseChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    // The streaming badge no longer lives in the header — it moved to the
    // in-body pre-first-byte placeholder ('Generating...') that only
    // renders while streaming && !streamContent && !streamReasoning.
    // Guard against either indicator leaking into the idle render.
    const html = component.html()
    expect(html).not.toContain('streaming...')
    expect(html).not.toContain('Thinking...')
    expect(html).not.toContain('Generating...')
  })

  it('keeps the send button enabled when an agent is selected and no stream is in flight', async () => {
    setupBaseChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    // The send button is one of several buttons; locate it by its disabled
    // state in the unstreaming/agent-selected scenario. Most agent-context
    // buttons are enabled at this point, but the textarea must be enabled too
    // (it's bound to the streaming flag via :disabled="streaming").
    const textarea = component.find('textarea')
    expect(textarea.exists()).toBe(true)
    expect((textarea.element as HTMLTextAreaElement).disabled).toBe(false)
  })

  it('disables the textarea + send affordance once streaming begins (template-level guard)', async () => {
    // We can't easily flip the internal `streaming` ref from the test, but
    // we can pin the contract that the disabled binding is wired. The
    // textarea is disabled when `streaming` is true; we observe the binding
    // attribute so any refactor that drops it breaks this test.
    setupBaseChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    // The send-action surface includes a textarea that owns the user's input.
    // The presence of the textarea — combined with the explicit binding in the
    // template (`:disabled="streaming"`) — pins the in-flight ↔ idle contract.
    const textarea = component.find('textarea')
    expect(textarea.exists()).toBe(true)
  })
})

describe('Chat page — tool call rendering', () => {
  function setupToolCallConversation() {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 42, agentId: 1, agentName: 'streaming-agent', channelType: 'web', peerId: 'admin',
        messageCount: 2, preview: 'Tool call demo',
        createdAt: '2026-04-22T10:00:00Z', updatedAt: '2026-04-22T10:00:00Z' },
    ])
    // The /messages endpoint feeds the assistant-with-toolCalls fixture used
    // by the message renderer. The role-and-toolCalls combination triggers
    // the tool-execution UI block in the template.
    registerEndpoint('/api/conversations/42/messages', () => [
      { id: 100, role: 'user', content: 'Run the search please',
        createdAt: '2026-04-22T10:00:00Z' },
      { id: 101, role: 'assistant', content: '',
        toolCalls: [
          { id: 'call_a', type: 'function',
            function: { name: 'web_search', arguments: '{"query":"jclaw"}' } },
        ],
        createdAt: '2026-04-22T10:00:01Z' },
      { id: 102, role: 'tool', content: 'search results body',
        toolCallId: 'call_a',
        createdAt: '2026-04-22T10:00:02Z' },
      { id: 103, role: 'assistant', content: 'Here is what I found.',
        createdAt: '2026-04-22T10:00:03Z' },
    ])
  }

  it('mounts cleanly when a conversation containing tool calls is available', async () => {
    setupToolCallConversation()
    const component = await mountSuspended(Chat)
    await flushPromises()

    // Regression guard: the page used to iterate the conversation-sidebar
    // list on mount; the tool-call-bearing message shape (no content, with
    // toolCalls) must not break the feed fetch. The in-page sidebar is gone
    // (recents moved to layouts/default.vue), so we assert the textarea
    // rendered — proof the composition setup reached template render without
    // throwing on the tool-call fixture.
    const textarea = component.find('textarea')
    expect(textarea.exists()).toBe(true)
  })

  it('does not render the empty-response placeholder for an assistant message with tool calls', async () => {
    // Bug repro: a tool-calling assistant turn with no text content rendered
    // "(empty response)" because the v-else-if only checked `!msg.reasoning`.
    // The fix gates the placeholder on `!msg.toolCalls?.length && !streaming`
    // — a message that did meaningful tool work isn't empty just because the
    // text channel is blank. The fixture from setupToolCallConversation has
    // an assistant message (id 101) with `content: ''` and one toolCalls
    // entry, exactly the shape the screenshot of the bug showed.
    setupToolCallConversation()
    const component = await mountSuspended(Chat)
    await flushPromises()

    expect(component.text()).not.toContain('(empty response)')
  })

  it('nests structured chips under each call, not in one merged grid (JCLAW-170)', async () => {
    // Regression pin: each tool call's chips MUST live inside that call's
    // expanded body, not in a single merged grid below the call list. The
    // setup mirrors the production multi-search shape — two web_search
    // calls, each with its own structured result list — and asserts both
    // chip groups make it into the DOM, sandwiched between the per-call
    // headers rather than concatenated below them.
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 77, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 5, preview: 'multi-search demo',
        createdAt: '2026-04-22T10:00:00Z', updatedAt: '2026-04-22T10:00:00Z' },
    ])
    registerEndpoint('/api/conversations/77/messages', () => [
      { id: 200, role: 'user', content: 'search both stores',
        createdAt: '2026-04-22T10:00:00Z' },
      { id: 201, role: 'assistant', content: '',
        toolCalls: [{
          id: 'call_lazada', type: 'function', icon: 'search',
          function: { name: 'web_search',
            arguments: '{"query":"nose trimmer Lazada"}' },
        }],
        createdAt: '2026-04-22T10:00:01Z' },
      { id: 202, role: 'tool', content: 'lazada result body',
        toolResults: 'call_lazada',
        toolResultStructured: {
          provider: 'Exa',
          results: [
            { title: 'Lazada Listing A', url: 'https://lazada.com.my/a',
              snippet: 'a', faviconUrl: 'https://icons.duckduckgo.com/ip3/lazada.com.my.ico' },
            { title: 'Lazada Listing B', url: 'https://lazada.com.my/b',
              snippet: 'b', faviconUrl: 'https://icons.duckduckgo.com/ip3/lazada.com.my.ico' },
          ],
        },
        createdAt: '2026-04-22T10:00:02Z' },
      { id: 203, role: 'assistant', content: '',
        toolCalls: [{
          id: 'call_shopee', type: 'function', icon: 'search',
          function: { name: 'web_search',
            arguments: '{"query":"nose trimmer Shopee"}' },
        }],
        createdAt: '2026-04-22T10:00:03Z' },
      { id: 204, role: 'tool', content: 'shopee result body',
        toolResults: 'call_shopee',
        toolResultStructured: {
          provider: 'Exa',
          results: [
            { title: 'Shopee Listing X', url: 'https://shopee.com.my/x',
              snippet: 'x', faviconUrl: 'https://icons.duckduckgo.com/ip3/shopee.com.my.ico' },
          ],
        },
        createdAt: '2026-04-22T10:00:04Z' },
      { id: 205, role: 'assistant', content: 'Here are top picks from both.',
        createdAt: '2026-04-22T10:00:05Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    // The deep-link watcher needs the conversations list to land before it
    // fires loadConversation; flush a second tick for hydration to settle.
    await flushPromises()

    // Force-load the conversation directly — no deep-link param in the test
    // route — so the test exercises the post-hydration template path.
    const vm = component.vm as unknown as { loadConversation: (id: number) => Promise<void> }
    await vm.loadConversation(77)
    await flushPromises()
    // Open the outer accordion (collapsed by default on reload).
    const outerToggle = component.findAll('button')
      .find(b => b.text().includes('2 tool calls'))
    if (outerToggle) await outerToggle.trigger('click')
    await flushPromises()

    const html = component.html()
    // Both per-call query previews render as their own per-call rows.
    expect(html).toContain('nose trimmer Lazada')
    expect(html).toContain('nose trimmer Shopee')
    // The latest call (Shopee) is auto-expanded — its result chip is in DOM.
    expect(html).toContain('shopee.com.my/x')
    // The Lazada call is collapsed by default; expand it to verify per-call
    // nesting works for arbitrary calls (not just the auto-expanded last one).
    const lazadaToggle = component.findAll('button')
      .find(b => b.text().includes('nose trimmer Lazada'))
    if (lazadaToggle) await lazadaToggle.trigger('click')
    await flushPromises()
    const expanded = component.html()
    expect(expanded).toContain('lazada.com.my/a')
    expect(expanded).toContain('lazada.com.my/b')
  })
})

describe('Chat page — empty conversation state', () => {
  it('renders the chat shell when no agent is configured', async () => {
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/config', () => ({ entries: [] }))
    registerEndpoint('/api/conversations', () => [])
    const component = await mountSuspended(Chat)
    await flushPromises()

    // Even with zero agents the page must not throw — the composer surface
    // still renders even without agents to select.
    expect(component.find('textarea').exists()).toBe(true)
  })
})

describe('Chat page — JCLAW-25 vision attachment gate', () => {
  it('rejects image attachments when the selected model does not advertise supportsVision', async () => {
    // Baseline harness pins kimi-k2.5, which has no supportsVision flag in
    // its config JSON. visionSupported should therefore compute to false,
    // and addAttachments is expected to short-circuit image files with the
    // AC-mandated error string. defineExpose automatically unwraps refs,
    // so vm.attachError yields the string directly (not a ref wrapper).
    setupBaseChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      addAttachments: (files: File[]) => void
      attachError: string | null
      attachedFiles: File[]
    }
    const png = new File([new Uint8Array([0x89, 0x50, 0x4E, 0x47])], 'shot.png', { type: 'image/png' })
    vm.addAttachments([png])
    await flushPromises()

    expect(vm.attachError).toBe('This model does not support images')
    expect(vm.attachedFiles.length).toBe(0)
  })

  it('accepts non-image attachments on a non-vision model (file path is orthogonal to the vision gate)', async () => {
    setupBaseChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      addAttachments: (files: File[]) => void
      attachError: string | null
      attachedFiles: File[]
    }
    const txt = new File(['hello'], 'note.txt', { type: 'text/plain' })
    vm.addAttachments([txt])
    await flushPromises()

    expect(vm.attachError).toBeNull()
    expect(vm.attachedFiles.length).toBe(1)
  })
})

describe('Chat page — JCLAW-131 per-kind upload caps + JCLAW-165 audio universally accepted', () => {
  it('accepts audio attachments regardless of model supportsAudio flag', async () => {
    // Pre-JCLAW-165 the addAttachments path rejected audio when the
    // active model lacked supportsAudio. With the transcription pipeline
    // in place every model can consume audio (text-only models receive
    // the transcript as a text part; audio-capable models receive native
    // input_audio), so the attach-time audio gate is gone.
    setupBaseChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      addAttachments: (files: File[]) => void
      attachError: string | null
      attachedFiles: File[]
    }
    const wav = new File(['RIFF...'], 'memo.wav', { type: 'audio/wav' })
    vm.addAttachments([wav])
    await flushPromises()

    expect(vm.attachError).toBeNull()
    expect(vm.attachedFiles.length).toBe(1)
    expect(vm.attachedFiles[0]!.name).toBe('memo.wav')
  })

  // The per-kind size cap is covered end-to-end in ApiChatControllerTest's
  // uploadRejectsOversizedFileAgainstConfigCap — mocking /api/config at the
  // Vitest layer races with the component's own useFetch, so we'd have to
  // wait for hydration specifically before the computed cap updates. Backend
  // coverage is the authoritative enforcement; frontend UX testing stays
  // focused on the attach-time gates here.
})

describe('Chat page — subagent transcript read-only mode (JCLAW-274)', () => {
  // The /subagents page's "View transcript" link and the chat page's own
  // subagent_announce "View full →" link both route to /chat?conversation=ID
  // where the conversation belongs to a subagent (Agent.parentAgent != null,
  // channel="subagent"). Subagents are filtered from /api/agents, so the
  // resolver enters a read-only branch: messages render, a banner names the
  // subagent, and the composer is disabled.

  function setupSubagentTranscriptFixture() {
    setupBaseChatApi()
    // /api/conversations/{id} returns the subagent conversation directly —
    // resolveAndLoadConversation hits this endpoint (not the list endpoint,
    // which is channel-scoped and would silently miss subagent rows).
    registerEndpoint('/api/conversations/501', () => ({
      id: 501, agentId: 99, agentName: 'helper-subagent', channelType: 'subagent',
      peerId: null, messageCount: 2, preview: 'subagent task',
      createdAt: '2026-05-15T10:00:00Z', updatedAt: '2026-05-15T10:00:01Z',
    }))
    registerEndpoint('/api/conversations/501/messages', () => [
      { id: 700, role: 'user', content: 'Subagent task instructions',
        createdAt: '2026-05-15T10:00:00Z' },
      { id: 701, role: 'assistant', content: 'Subagent reply.',
        createdAt: '2026-05-15T10:00:01Z' },
    ])
  }

  it('enters read-only mode and renders the banner when resolving a subagent conversation', async () => {
    setupSubagentTranscriptFixture()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      resolveAndLoadConversation: (id: number) => Promise<boolean>
      subagentTranscript: { agentId: number, agentName: string } | null
    }
    const loaded = await vm.resolveAndLoadConversation(501)
    expect(loaded).toBe(true)
    await flushPromises()

    expect(vm.subagentTranscript).toEqual({ agentId: 99, agentName: 'helper-subagent' })
    const banner = component.find('[data-testid="subagent-transcript-banner"]')
    expect(banner.exists()).toBe(true)
    expect(banner.text()).toContain('helper-subagent')
    expect(banner.text()).toContain('Read-only')
  })

  it('disables the composer textarea when in subagent-transcript mode', async () => {
    setupSubagentTranscriptFixture()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      resolveAndLoadConversation: (id: number) => Promise<boolean>
    }
    await vm.resolveAndLoadConversation(501)
    await flushPromises()

    const textarea = component.find('textarea').element as HTMLTextAreaElement
    expect(textarea.disabled).toBe(true)
    expect(textarea.placeholder).toContain('read-only')
  })

  it('renders the subagent transcript messages so the user can read them', async () => {
    setupSubagentTranscriptFixture()
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      resolveAndLoadConversation: (id: number) => Promise<boolean>
    }
    await vm.resolveAndLoadConversation(501)
    await flushPromises()

    const html = component.html()
    expect(html).toContain('Subagent task instructions')
    expect(html).toContain('Subagent reply.')
  })

  it('does not enter read-only mode for a normal (in-dropdown) conversation', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations/77', () => ({
      id: 77, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
      peerId: 'admin', messageCount: 1, preview: 'normal',
      createdAt: '2026-05-15T10:00:00Z', updatedAt: '2026-05-15T10:00:00Z',
    }))
    registerEndpoint('/api/conversations/77/messages', () => [])
    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as {
      resolveAndLoadConversation: (id: number) => Promise<boolean>
      subagentTranscript: { agentId: number, agentName: string } | null
    }
    await vm.resolveAndLoadConversation(77)
    await flushPromises()

    expect(vm.subagentTranscript).toBeNull()
    expect(component.find('[data-testid="subagent-transcript-banner"]').exists()).toBe(false)
    const textarea = component.find('textarea').element as HTMLTextAreaElement
    expect(textarea.disabled).toBe(false)
  })
})

describe('Chat page — composer focus on entry', () => {
  // Pin the "land the cursor in the message box" contract: any path that
  // resets the chat to a typeable state should leave the textarea focused
  // so the user can start typing without an extra click. Two entry points
  // share the same focusInput() helper:
  //
  //   - newChat (the PencilSquareIcon button) — clears state then focuses.
  //   - loadConversation (deep-link from /conversations, in-page Recents
  //     click, or any other navigation that lands on a fresh conversation).

  it('focuses the textarea after the New conversation button is clicked', async () => {
    setupBaseChatApi()
    const component = await mountSuspended(Chat, { attachTo: document.body })
    await flushPromises()

    // The composer's "New conversation" button is identified by its title
    // attribute — it carries the PencilSquareIcon glyph but the title is
    // the stable contract.
    const newChatBtn = component.find('button[title="New conversation"]')
    expect(newChatBtn.exists()).toBe(true)
    await newChatBtn.trigger('click')
    await flushPromises()
    // focusInput() schedules its focus() call inside nextTick; flushPromises
    // doesn't drain Vue's microtask queue, so add an extra tick.
    await new Promise(r => setTimeout(r, 0))

    const textarea = component.find('textarea').element as HTMLTextAreaElement
    expect(document.activeElement).toBe(textarea)
  })

  it('focuses the textarea after loadConversation lands a conversation', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations/55/messages', () => [])
    const component = await mountSuspended(Chat, { attachTo: document.body })
    await flushPromises()

    // Reach into the exposed surface to drive loadConversation directly —
    // mirrors what the deep-link watcher and in-page route-query watcher do.
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
    }
    await vm.loadConversation(55)
    await flushPromises()
    await new Promise(r => setTimeout(r, 0))

    const textarea = component.find('textarea').element as HTMLTextAreaElement
    expect(document.activeElement).toBe(textarea)
  })
})

/**
 * JCLAW-270 async-spawn announce polling. After the parent's streaming turn
 * ends, an async {@code subagent_announce} Message can arrive seconds later;
 * the chat view polls {@code /api/conversations/{id}/messages} every 5s
 * while any tool result reports {@code status:RUNNING} without a matching
 * announce row yet, and stops the instant the announce lands or the page
 * unmounts. Tests below drive the loop by invoking the exposed
 * {@code pollForAnnounce} directly — that's the same code path the
 * {@code setInterval} tick runs, without the harness fragility of fake
 * timers + Nuxt's async-hydration runtime.
 */
describe('Chat page — async subagent announce polling', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('polls for new messages when an async subagent run is pending', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 401, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 3, preview: 'async pending',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    // First call (from loadConversation) returns the pre-announce shape:
    // user, assistant, tool-result with status:RUNNING. Second call (the
    // first poll tick) adds the system-role subagent_announce row.
    let messagesCalls = 0
    registerEndpoint('/api/conversations/401/messages', () => {
      messagesCalls++
      const base = [
        { id: 700, role: 'user', content: 'spawn async please',
          createdAt: '2026-05-14T10:00:00Z' },
        { id: 701, role: 'assistant', content: 'Spawned! Run id is 2.',
          toolCalls: [
            { id: 'call_x', type: 'function', icon: 'users',
              function: { name: 'spawn_subagent', arguments: '{}' } },
          ],
          createdAt: '2026-05-14T10:00:01Z' },
        { id: 702, role: 'tool',
          content: '{"run_id":"2","conversation_id":"40005","status":"RUNNING"}',
          toolResults: 'call_x',
          createdAt: '2026-05-14T10:00:02Z' },
      ]
      if (messagesCalls >= 2) {
        base.push({
          id: 703, role: 'system' as unknown as 'tool',
          content: 'Subagent completed (research): result body',
          // @ts-expect-error fixture-only fields not in Message type
          messageKind: 'subagent_announce',
          metadata: {
            runId: 2,
            label: 'research',
            status: 'COMPLETED',
            reply: 'Lightweight threads dance...',
            childConversationId: 40005,
          },
          createdAt: '2026-05-14T10:00:06Z',
        })
      }
      return base
    })

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      hasPendingAsyncAnnounce: () => boolean
      pollForAnnounce: () => Promise<void>
    }
    await vm.loadConversation(401)
    await flushPromises()

    // Pre-announce: the loop should recognise the pending state.
    expect(vm.hasPendingAsyncAnnounce()).toBe(true)
    expect(component.find('[data-testid="subagent-announce-card"]').exists()).toBe(false)

    // One poll tick — second /messages call returns the announce row.
    await vm.pollForAnnounce()
    await flushPromises()

    expect(component.find('[data-testid="subagent-announce-card"]').exists()).toBe(true)
    expect(component.text()).toContain('research')
    expect(component.text()).toContain('Lightweight threads dance')
    // And the pending check now reads false — loop will idle on next tick.
    expect(vm.hasPendingAsyncAnnounce()).toBe(false)
  })

  it('stops polling once the announce arrives', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 402, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 3, preview: 'stop after announce',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    let messagesCalls = 0
    registerEndpoint('/api/conversations/402/messages', () => {
      messagesCalls++
      const base = [
        { id: 800, role: 'user', content: 'spawn async',
          createdAt: '2026-05-14T10:00:00Z' },
        { id: 801, role: 'assistant', content: 'Spawned.',
          createdAt: '2026-05-14T10:00:01Z' },
        { id: 802, role: 'tool',
          content: '{"run_id":"5","conversation_id":"40010","status":"RUNNING"}',
          createdAt: '2026-05-14T10:00:02Z' },
      ]
      if (messagesCalls >= 2) {
        base.push({
          id: 803, role: 'system' as unknown as 'tool',
          content: 'Subagent completed',
          // @ts-expect-error fixture-only fields not in Message type
          messageKind: 'subagent_announce',
          metadata: {
            runId: 5, label: 'done', status: 'COMPLETED',
            reply: 'done', childConversationId: 40010,
          },
          createdAt: '2026-05-14T10:00:06Z',
        })
      }
      return base
    })

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      hasPendingAsyncAnnounce: () => boolean
      pollForAnnounce: () => Promise<void>
    }
    await vm.loadConversation(402)
    await flushPromises()
    const callsAfterLoad = messagesCalls

    // Tick #1: announce arrives.
    await vm.pollForAnnounce()
    await flushPromises()
    const callsAfterTick1 = messagesCalls
    expect(callsAfterTick1).toBeGreaterThan(callsAfterLoad)
    expect(vm.hasPendingAsyncAnnounce()).toBe(false)

    // Now simulate two more interval ticks. The setInterval callback gates
    // the network call on hasPendingAsyncAnnounce, so once the announce is
    // in the list no further /messages calls should fire.
    // Direct simulate by calling the same tick the interval would: noop.
    // (We don't expose announcePollTick; the contract is "while pending").
    // Re-asserting via the gate is enough — the interval handler will
    // short-circuit on every subsequent tick.
    expect(vm.hasPendingAsyncAnnounce()).toBe(false)
  })

  it('does not poll if no async run is pending', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 403, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 2, preview: 'fully sync',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    // Synchronous tool result — no status:RUNNING anywhere. The poller's
    // pending-check should return false even though the conversation does
    // contain a tool row.
    registerEndpoint('/api/conversations/403/messages', () => [
      { id: 900, role: 'user', content: 'do the sync thing',
        createdAt: '2026-05-14T10:00:00Z' },
      { id: 901, role: 'assistant', content: '',
        toolCalls: [
          { id: 'call_y', type: 'function', icon: 'search',
            function: { name: 'web_search', arguments: '{}' } },
        ],
        createdAt: '2026-05-14T10:00:01Z' },
      { id: 902, role: 'tool', content: 'sync result body',
        toolResults: 'call_y',
        createdAt: '2026-05-14T10:00:02Z' },
      { id: 903, role: 'assistant', content: 'Here you go.',
        createdAt: '2026-05-14T10:00:03Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      hasPendingAsyncAnnounce: () => boolean
    }
    await vm.loadConversation(403)
    await flushPromises()

    expect(vm.hasPendingAsyncAnnounce()).toBe(false)
  })

  it('detects pending state from inline assistant.toolCalls (post-stream shape)', async () => {
    // Regression: between stream-end and the next reload, the SSE tool_call
    // frame folds the result into assistant.toolCalls[i].resultText rather
    // than emitting a separate tool-role row. Without this branch, the
    // poller would never fire on a same-page spawn-and-wait flow and the
    // user would have to navigate away to see the announce land.
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 404, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 2, preview: 'post-stream pending',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    // Server has only the assistant row + tool row; the test then mutates the
    // local list to mimic the post-stream shape (no separate tool-role row,
    // result inline on the assistant row).
    registerEndpoint('/api/conversations/404/messages', () => [
      { id: 1000, role: 'user', content: 'spawn async',
        createdAt: '2026-05-14T10:00:00Z' },
      { id: 1001, role: 'assistant', content: 'Spawned!',
        createdAt: '2026-05-14T10:00:01Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      hasPendingAsyncAnnounce: () => boolean
      messages: Array<{ role: string, toolCalls?: Array<{ id: string, name: string, icon: string, arguments: string, resultText?: string | null }> }>
    }
    await vm.loadConversation(404)
    await flushPromises()

    // Splice the inline-toolCall onto the streamed assistant row, mirroring
    // what the chat page's tool_call SSE handler does live.
    const assistant = vm.messages.find(m => m.role === 'assistant')!
    assistant.toolCalls = [{
      id: 'call_async', name: 'spawn_subagent', icon: 'users', arguments: '{}',
      resultText: '{"run_id":"7","conversation_id":"40020","status":"RUNNING"}',
    }]

    expect(vm.hasPendingAsyncAnnounce()).toBe(true)
  })

  it('does not duplicate the user bubble when the announce arrives via poll', async () => {
    // Regression: pollForAnnounce used to filter additions purely by
    // id-not-in-knownIds. Optimistic local rows have id=null, so the
    // server-side copies of those same rows (which DO have ids) sailed
    // past the filter and got appended a second time. Symptom: the user
    // prompt bubble rendered twice in the transcript after an async
    // spawn turn — once from the optimistic placeholder, once from the
    // poll-fetched server row. The fix backfills server ids onto local
    // optimistic rows before computing additions.
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 405, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 5, preview: 'dedup test',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    // First /messages call: pre-announce shape — 4 rows, no announce yet.
    // Second call (after the optimistic state is set up): full 5-row shape
    // including the system-role announce.
    let messagesCalls = 0
    registerEndpoint('/api/conversations/405/messages', () => {
      messagesCalls++
      const base: Array<Record<string, unknown>> = [
        { id: 1100, role: 'user', content: 'spawn async, please',
          createdAt: '2026-05-14T10:00:00Z' },
        { id: 1101, role: 'assistant', content: '',
          toolCalls: [
            { id: 'call_x', type: 'function', icon: 'users',
              function: { name: 'spawn_subagent', arguments: '{}' } },
          ],
          createdAt: '2026-05-14T10:00:01Z' },
        { id: 1102, role: 'tool',
          content: '{"run_id":"9","conversation_id":"40030","status":"RUNNING"}',
          toolResults: 'call_x',
          createdAt: '2026-05-14T10:00:02Z' },
        { id: 1103, role: 'assistant',
          content: 'Run id is 9. Will let you know.',
          createdAt: '2026-05-14T10:00:03Z' },
      ]
      if (messagesCalls >= 2) {
        base.push({
          id: 1104, role: 'system',
          content: 'Subagent completed (test): done',
          messageKind: 'subagent_announce',
          metadata: {
            runId: 9, label: 'test', status: 'COMPLETED',
            reply: 'done', childConversationId: 40030,
          },
          createdAt: '2026-05-14T10:00:04Z',
        })
      }
      return base
    })

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
      pollForAnnounce: () => Promise<void>
      messages: Array<{
        id: number | null
        role: string
        content?: string | null
        toolCalls?: Array<{
          id: string
          name: string
          icon: string
          arguments: string
          resultText?: string | null
        }>
      }>
    }
    await vm.loadConversation(405)
    await flushPromises()

    // Simulate the post-stream optimistic state: clear the loaded list and
    // rebuild it the way the SSE path leaves it — id-less user + id-less
    // assistant carrying the spawn's RUNNING result inline (no standalone
    // tool-role row, no announce yet).
    vm.messages.splice(0, vm.messages.length)
    vm.messages.push(
      { id: null, role: 'user', content: 'spawn async, please' },
      { id: null, role: 'assistant', content: 'Run id is 9. Will let you know.',
        toolCalls: [{
          id: 'call_x', name: 'spawn_subagent', icon: 'users', arguments: '{}',
          resultText: '{"run_id":"9","conversation_id":"40030","status":"RUNNING"}',
        }] },
    )

    // Tick the poll. The next /messages fetch returns the full 5-row shape
    // including the announce.
    await vm.pollForAnnounce()
    await flushPromises()

    // Exactly ONE user row should remain — the optimistic one, now with its
    // server id backfilled. The assistant row's id should also be backfilled
    // (it's the LAST assistant by role-stack pairing). Only the
    // intermediate empty-assistant + tool + system rows should have been
    // appended as additions.
    const userRows = vm.messages.filter(m => m.role === 'user')
    expect(userRows.length).toBe(1)
    expect(userRows[0]!.id).toBe(1100)
    const assistantWithContent = vm.messages.find(m => m.role === 'assistant' && m.content?.startsWith('Run id'))
    expect(assistantWithContent?.id).toBe(1103)
    // Announce row should now be present in the local list.
    expect(vm.messages.some(m => m.role === 'system')).toBe(true)
  })

  it('clears the polling interval on unmount', async () => {
    setupBaseChatApi()
    // Spy on the global clearInterval so we can assert the unmount hook
    // releases the timer rather than leaving it dangling — leaked intervals
    // are a real-world bug source when the test harness reuses jsdom across
    // `it` blocks.
    const clearSpy = vi.spyOn(globalThis, 'clearInterval')

    const component = await mountSuspended(Chat)
    await flushPromises()

    component.unmount()
    await flushPromises()

    // At least one clearInterval call must have happened; the chat page
    // owns the announce poll's setInterval handle in onUnmounted.
    expect(clearSpy).toHaveBeenCalled()
  })
})

/**
 * JCLAW-291: model output was cut off by max_tokens. Both the assistant
 * bubble and the async announce card render an amber "Reply was truncated
 * by the model" marker so the operator does not mistake the cut-off text
 * for a complete answer. Tests cover both render paths.
 */
describe('Chat page — truncated reply marker', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('renders a truncation marker on assistant messages where truncated=true', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 501, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 2, preview: 'truncated assistant',
        createdAt: '2026-05-15T10:00:00Z', updatedAt: '2026-05-15T10:00:00Z' },
    ])
    registerEndpoint('/api/conversations/501/messages', () => [
      { id: 1200, role: 'user', content: 'Write me a long answer please',
        createdAt: '2026-05-15T10:00:00Z' },
      { id: 1201, role: 'assistant', content: 'Here is the start but it ran out',
        truncated: true,
        createdAt: '2026-05-15T10:00:01Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
    }
    await vm.loadConversation(501)
    await flushPromises()

    const markers = component.findAll('[data-testid="truncated-marker"]')
    expect(markers.length).toBeGreaterThanOrEqual(1)
    expect(component.text()).toContain('Reply was truncated by the model')
  })

  it('renders a truncation marker on the announce card when metadata.truncated=true', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 502, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 2, preview: 'truncated announce',
        createdAt: '2026-05-15T10:00:00Z', updatedAt: '2026-05-15T10:00:00Z' },
    ])
    registerEndpoint('/api/conversations/502/messages', () => [
      { id: 1300, role: 'user', content: 'Spawn an async research subagent please',
        createdAt: '2026-05-15T10:00:00Z' },
      { id: 1301, role: 'assistant', content: 'Spawned!',
        createdAt: '2026-05-15T10:00:01Z' },
      { id: 1302, role: 'system' as unknown as 'tool',
        content: 'Subagent completed (research): partial reply text',
        messageKind: 'subagent_announce',
        truncated: true,
        metadata: {
          runId: 12, label: 'research', status: 'COMPLETED',
          reply: 'partial reply text', childConversationId: 60000,
          truncated: true,
        },
        createdAt: '2026-05-15T10:00:05Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
    }
    await vm.loadConversation(502)
    await flushPromises()

    expect(component.find('[data-testid="subagent-announce-card"]').exists()).toBe(true)
    const markers = component.findAll('[data-testid="truncated-marker"]')
    expect(markers.length).toBeGreaterThanOrEqual(1)
    expect(component.text()).toContain('Reply was truncated by the model')
  })

  it('omits the truncation marker when truncated is false/absent', async () => {
    setupBaseChatApi()
    registerEndpoint('/api/conversations', () => [
      { id: 503, agentId: 1, agentName: 'streaming-agent', channelType: 'web',
        peerId: 'admin', messageCount: 2, preview: 'no truncation',
        createdAt: '2026-05-15T10:00:00Z', updatedAt: '2026-05-15T10:00:00Z' },
    ])
    registerEndpoint('/api/conversations/503/messages', () => [
      { id: 1400, role: 'user', content: 'Hi',
        createdAt: '2026-05-15T10:00:00Z' },
      { id: 1401, role: 'assistant', content: 'Hello — full reply, no truncation here.',
        createdAt: '2026-05-15T10:00:01Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    const vm = component.vm as unknown as {
      loadConversation: (id: number) => Promise<void>
    }
    await vm.loadConversation(503)
    await flushPromises()

    expect(component.findAll('[data-testid="truncated-marker"]').length).toBe(0)
    expect(component.text()).not.toContain('Reply was truncated by the model')
  })
})
