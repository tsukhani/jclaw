import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatMessage from '~/components/chat/ChatMessage.vue'
import type { Message } from '~/types/api'
import type { MessageUsage } from '~/utils/usage-cost'

function msg(o: Partial<Message> = {}): Message {
  return { id: 1, role: 'assistant', content: 'hello', createdAt: '2026-07-10T00:00:00Z', ...o } as Message
}

// Full required-prop set for the extracted per-message renderer (JCLAW-690).
function props(m: Message, o: Record<string, unknown> = {}) {
  return {
    msg: m,
    msgIdx: 0,
    renderToken: '',
    agentId: null,
    streaming: false,
    copiedMessageId: null,
    streamingMessageKey: null,
    streamContent: '',
    streamContentHtml: '',
    streamReasoningHtml: '',
    videoJobStatus: {},
    imageGenTurnKey: null,
    imageGenPercent: null,
    tokStatsHoverKey: null,
    runSlice: null,
    runLabel: '',
    runStatus: '',
    showModelSwitch: false,
    ...o,
  }
}

describe('ChatMessage (JCLAW-690)', () => {
  it('shows a task run\'s result as a notice with its facts, and no bubble for the user row behind it', async () => {
    const c = await mountSuspended(ChatMessage, {
      props: props(msg({
        role: 'user',
        messageKind: 'task_run_result',
        content: '[task result] Task \'daily-briefing\' (run 7) completed in 67.5 s. Its output was delivered to telegram. Tell the user how the run ended, without fetching or repeating its output here.',
        metadata: {
          taskId: 3, taskName: 'daily-briefing', runId: 7, status: 'COMPLETED', durationMs: 67540, deliveryStatus: 'DELIVERED',
          headline: 'Task \'daily-briefing\' (run 7) completed in 67.5 s.', outcome: 'Its output was delivered to telegram.',
        },
      } as Partial<Message>)),
    })

    const card = c.find('[data-testid="chat-task-run-result"]')
    expect(card.find('p').text()).toBe('Task \'daily-briefing\' (run 7) completed in 67.5 s. Its output was delivered to telegram.')
    expect(card.find('a').attributes('href')).toBe('/tasks')
    expect(c.text()).not.toContain('Tell the user')
  })

  it('renders a user message\'s plain-text content', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ role: 'user', content: 'howdy there' })) })
    expect(c.text()).toContain('howdy there')
  })

  it('renders an assistant message\'s markdown body', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ role: 'assistant', content: '# Big Heading' })) })
    const body = c.find('.prose-chat')
    expect(body.exists()).toBe(true)
    expect(body.html()).toContain('Big Heading')
  })

  it('claims a finished reply\'s visual figure and hides its code block', async () => {
    vi.stubGlobal('IntersectionObserver', class {
      observe() {}
      unobserve() {}
      disconnect() {}
    })
    try {
      const fence = '```visual\n' + JSON.stringify({ kind: 'chart', title: 'T', series: [{ name: 'A', points: [[1, 2], [2, 3]] }] }) + '\n```\n'
      const c = await mountSuspended(ChatMessage, { props: props(msg({ role: 'assistant', content: fence })) })
      const figure = c.find('figure.visual-view')
      expect(figure.attributes('data-visual-claimed')).toBe('')
      expect((figure.find(':scope > .code-block').element as HTMLElement).hidden).toBe(true)
    }
    finally {
      vi.unstubAllGlobals()
    }
  })

  it('renders a ChatToolCalls block when the message carries tool calls', async () => {
    const withTools = msg({ role: 'assistant', content: 'done', toolCalls: [{ id: 't1', name: 'web_search', icon: 'search', arguments: JSON.stringify({ query: 'cats' }) }] })
    const c = await mountSuspended(ChatMessage, { props: props(withTools) })
    expect(c.text()).toContain('1 tool call')
    expect(c.text()).toContain('Searched "cats"')
  })

  it('draws an MCP Apps view on the chat\'s own turn but not on a subagent run\'s', async () => {
    const view = { server: 'serpapi', tool: 'search_table', resourceUri: 'ui://prefab/table.html', arguments: {}, result: { content: [] } }
    const withView = msg({ role: 'assistant', content: 'done', toolCalls: [{ id: 't1', name: 'mcp_serpapi', icon: 'plug', arguments: '{}', resultStructured: { mcpApp: view } }] })
    const own = await mountSuspended(ChatMessage, { props: props(withView, { mcpApps: true }) })
    expect(own.findAll('[data-testid="mcp-app-frame"]')).toHaveLength(1)
    const inRun = await mountSuspended(ChatMessage, { props: props(withView, { mcpApps: true, runSlice: { runId: 1, position: 'middle', collapsed: false } }) })
    expect(inRun.find('[data-testid="mcp-app-frame"]').exists()).toBe(false)
    const transcript = await mountSuspended(ChatMessage, { props: props(withView) })
    expect(transcript.find('[data-testid="mcp-app-frame"]').exists()).toBe(false)
  })

  it('renders a ChatThinkingCard when the message carries reasoning', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ role: 'assistant', content: 'answer', reasoning: 'let me think' })) })
    const reasoningBody = c.find('[data-reasoning-body]')
    expect(reasoningBody.exists()).toBe(true)
    expect(reasoningBody.text()).toContain('let me think')
  })

  it('names the model the router chose, and says when it failed over (JCLAW-1222)', async () => {
    const route = { class: 'reasoning', provider: 'openrouter', model: 'deepseek-v4-pro', reason: 'reasoning markers: prove', failover: true, thinkingMode: 'medium' }
    const c = await mountSuspended(ChatMessage, { props: props(msg({ usage: { prompt: 1, completion: 1, total: 2, reasoning: 0, cached: 0, durationMs: 1, route } })) })
    const badge = c.find('[data-testid="route-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('Reasoning')
    expect(badge.text()).toContain('deepseek-v4-pro')
    expect(badge.text()).toContain('failover')
    expect(badge.find('[data-testid="route-effort"]').text()).toBe('· medium effort')
    expect(badge.attributes('title')).toContain('openrouter/deepseek-v4-pro')
  })

  it('shows the live route before the usage record lands, and no badge off the router', async () => {
    const live = msg({ content: '', _route: { class: 'chat', provider: 'ollama-cloud', model: 'glm-5.3-flash', reason: 'no task markers' } })
    const c = await mountSuspended(ChatMessage, { props: props(live, { streaming: true }) })
    expect(c.find('[data-testid="route-badge"]').text()).toContain('glm-5.3-flash')
    expect(c.find('[data-testid="route-effort"]').exists()).toBe(false)

    const plain = await mountSuspended(ChatMessage, { props: props(msg()) })
    expect(plain.find('[data-testid="route-badge"]').exists()).toBe(false)
  })

  it('emits copy/edit/delete from the user-row action buttons', async () => {
    const m = msg({ role: 'user', content: 'edit me' })
    const c = await mountSuspended(ChatMessage, { props: props(m) })
    await c.find('button[title="Copy to clipboard"]').trigger('click')
    expect(c.emitted('copy-message')![0]![0]).toBe(m)
    await c.find('button[title="Edit & resubmit"]').trigger('click')
    expect(c.emitted('edit-user-message')![0]![0]).toBe(m)
    await c.find('button[title="Delete message"]').trigger('click')
    expect(c.emitted('delete-message')![0]![0]).toBe(m)
  })

  it('emits regenerate/delete from the assistant footer buttons', async () => {
    const m = msg({ role: 'assistant', content: 'regen me' })
    const c = await mountSuspended(ChatMessage, { props: props(m) })
    await c.find('button[title="Regenerate response"]').trigger('click')
    expect(c.emitted('regenerate-message')![0]![0]).toBe(m)
    await c.find('button[title="Delete message"]').trigger('click')
    expect(c.emitted('delete-message')![0]![0]).toBe(m)
  })

  it.each(['user', 'assistant'] as const)('emits reply-message from a %s message (JCLAW-1299)', async (role) => {
    const m = msg({ role, content: 'reply to me' })
    const c = await mountSuspended(ChatMessage, { props: props(m) })
    await c.find('[data-testid="reply-message"]').trigger('click')
    expect(c.emitted('reply-message')![0]![0]).toBe(m)
  })

  it('shows a stored reply\'s quoted block apart from the words typed under it', async () => {
    const m = msg({ role: 'user', content: '[Replying to a reminder]\n> Collect the parcel\n\nwhich locker?' })
    const c = await mountSuspended(ChatMessage, { props: props(m) })
    const quote = c.find('[data-testid="message-quote"]')
    expect(quote.text()).toContain('Replying to a reminder')
    expect(quote.text()).toContain('Collect the parcel')
    expect(c.text()).toContain('which locker?')
    expect(c.text()).not.toContain('> Collect')
  })

  it('shows the quote an unsent reply carries before the server stores it', async () => {
    const m = msg({ role: 'user', content: 'which locker?', _quote: { kind: 'reminder', text: 'Collect the parcel' } })
    const c = await mountSuspended(ChatMessage, { props: props(m) })
    const quote = c.find('[data-testid="message-quote"]')
    expect(quote.text()).toContain('Replying to a reminder')
    expect(quote.text()).toContain('Collect the parcel')
  })

  it('shows a message without a quoted block as typed', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ role: 'user', content: '[not a quote]\nplain' })) })
    expect(c.find('[data-testid="message-quote"]').exists()).toBe(false)
    expect(c.text()).toContain('[not a quote]')
  })
})

describe('ChatMessage lewis fence (JCLAW-1309)', () => {
  it('redraws the fence once SmilesDrawer loads, with no prop change', async () => {
    // jsdom has no canvas; SmilesDrawer then estimates label widths instead of logging each miss.
    const canvas = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
    try {
      const m = msg({ role: 'assistant', content: 'Water:\n\n```lewis\n[H]O[H]\n```\n' })
      const c = await mountSuspended(ChatMessage, { props: props(m) })
      expect(c.find('.prose-chat code.language-lewis').exists()).toBe(true)
      expect(c.find('.prose-chat svg').exists()).toBe(false)
      await vi.waitFor(() => expect(c.find('.prose-chat figure.lewis svg').exists()).toBe(true), { timeout: 10_000 })
      expect(c.find('.prose-chat code.language-lewis').exists()).toBe(false)
    }
    finally {
      canvas.mockRestore()
    }
  })
})

describe('ChatMessage stop marker (JCLAW-1388)', () => {
  it('renders a stored stop marker as the muted stop note with no reply actions', async () => {
    const marker = msg({ id: 7, role: 'assistant', messageKind: 'stop_marker', content: '(Stopped by the user before replying.)' })
    const c = await mountSuspended(ChatMessage, { props: props(marker) })
    const note = c.find('[data-testid="stop-marker"] .italic')
    expect(note.exists()).toBe(true)
    expect(note.classes()).toContain('text-fg-muted')
    expect(c.text()).toContain('(stopped before any response)')
    expect(c.text()).not.toContain('Stopped by the user before replying')
    expect(c.find('button[title="Regenerate response"]').exists()).toBe(false)
    expect(c.find('button[aria-label="Read message aloud"]').exists()).toBe(false)
  })

  it('keeps Regenerate and Read aloud on an ordinary assistant reply', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ id: 8, role: 'assistant', content: 'a reply' })) })
    expect(c.find('[data-testid="stop-marker"]').exists()).toBe(false)
    expect(c.find('button[title="Regenerate response"]').exists()).toBe(true)
    expect(c.find('button[aria-label="Read message aloud"]').exists()).toBe(true)
  })
})

describe('ChatMessage usage card', () => {
  // The two-call reply the operator asked about: the second call reread the first's prompt plus the tool result.
  const twoCalls = {
    prompt: 54_581, completion: 228, total: 54_809, reasoning: 0, cached: 40_448,
    lastPrompt: 28_025, lastCompletion: 179, lastCached: 26_496, calls: 2, lastWriteMs: 925, durationMs: 3000,
    modelProvider: 'ollama-cloud', modelId: 'deepseek-v4.1-flash', providerMetrics: { total_duration: 2_051_161_715 },
  }

  async function openCard(id: number, usage: MessageUsage) {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ id, usage }), { tokStatsHoverKey: id }) })
    const card = document.body.querySelector('[data-testid="usage-card"]') as HTMLElement
    const rows = Object.fromEntries([...card.querySelectorAll('dl > div')]
      .map(row => [row.querySelector('dt')?.textContent?.trim(), row.querySelector('dd')?.textContent?.trim()]))
    const lines = [...card.querySelectorAll('p')].map(p => p.textContent?.trim())
    return { c, rows, lines }
  }

  it('says what the reply took in words, that Read adds up every call, and the memory the meter shows', async () => {
    const { c, rows, lines } = await openCard(31, twoCalls)

    expect(lines.slice(0, 3)).toEqual(['This reply', 'ollama-cloud/deepseek-v4.1-flash', 'Took 3.00 s, in 2 model calls.'])
    expect(rows.Read).toBe('54,581 tokens')
    expect(rows['reused from earlier']).toBe('40,448')
    expect(rows.Wrote).toBe('228 tokens')
    expect(rows['Memory after this reply']).toBe('28,204 tokens')
    expect(rows['Writing speed']).toBe('193.5 tokens/s')
    expect(rows['Model time']).toBe('2.05 s')
    expect(lines).toContain('Each model call rereads the conversation, so Read adds up all 2.')
    expect(lines).toContain('Reused tokens were read before, so they usually cost less.')
    expect(lines).toContain('The meter at the top shows this for the latest reply.')
    expect(lines).toContain('Reported by ollama-cloud')
    expect(c.text()).toContain('193.5 tok/s')
    c.unmount()
  })

  it('says a one-call reply made one call, with no reread or reuse note, and shows its thinking', async () => {
    const { c, rows, lines } = await openCard(32, {
      prompt: 900, completion: 300, total: 1_200, reasoning: 12, cached: 0, lastPrompt: 900, lastCompletion: 300,
      calls: 1, lastWriteMs: 1_000, durationMs: 1_200,
    })

    expect(lines).toContain('Took 1.20 s, in one model call.')
    expect(rows.Thinking).toBe('12 tokens')
    expect(rows['reused from earlier']).toBeUndefined()
    expect(lines.filter(l => l?.startsWith('Each model call') || l?.startsWith('Reused tokens'))).toEqual([])
    expect(rows['Writing speed']).toBe('300.0 tokens/s')
    c.unmount()
  })

  it('on a reply saved before calls were counted, says only how long it took and names the average speed', async () => {
    const { c, rows, lines } = await openCard(33, {
      prompt: 1_000, completion: 50, total: 1_050, reasoning: 0, cached: 0, durationMs: 2_000,
    })

    expect(lines).toContain('Took 2.00 s.')
    expect(rows['Average speed']).toBe('25.0 tokens/s')
    expect(rows['Writing speed']).toBeUndefined()
    expect(rows['Memory after this reply']).toBeUndefined()
    expect(lines).toContain('Output over the whole reply\'s time, the wait for the first word included.')
    expect(lines.some(l => l?.startsWith('Reported by'))).toBe(false)
    expect(c.text()).toContain('25.0 tok/s avg')
    c.unmount()
  })
})
