import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatMessage from '~/components/chat/ChatMessage.vue'
import type { Message } from '~/types/api'
import type { MessageUsage } from '~/utils/usage-cost'
import { renderMarkdownStreaming } from '~/utils/chat-markdown'

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

  it('adds a YouTube player to a reply only once it has finished streaming', async () => {
    const content = '[Talk](https://youtu.be/dQw4w9WgXcQ)'
    const streaming = msg({ role: 'assistant', content, _key: 'k1' } as Partial<Message>)
    const c = await mountSuspended(ChatMessage, {
      props: props(streaming, { streaming: true, streamingMessageKey: 'k1', streamContent: content, streamContentHtml: renderMarkdownStreaming(content) }),
    })
    expect(c.find('.prose-chat a').attributes('href')).toBe('https://youtu.be/dQw4w9WgXcQ')
    expect(c.findAll('.youtube-embed')).toHaveLength(0)
    await c.setProps({ streaming: false, streamingMessageKey: null })
    expect(c.findAll('.youtube-embed').map(p => p.attributes('data-video-id'))).toEqual(['dQw4w9WgXcQ'])
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

  it('heads each group, puts its help under its rows, and names a unit once', async () => {
    const { c, rows, lines } = await openCard(31, twoCalls)

    expect(lines).toEqual([
      'This reply',
      'Took 3.00 s',
      'ollama-cloud/deepseek-v4.1-flash',
      '2 model calls',
      'Tokens',
      'Each model call rereads the conversation, so Read adds up all 2. Reused tokens were read before, so they usually cost less.',
      'Memory',
      'The meter at the top shows this for the latest reply.',
      'Speed',
      'Reported by ollama-cloud',
    ])
    expect(rows).toEqual({
      'Read': '54,581',
      'Reused from earlier': '40,448',
      'Wrote': '228',
      'After this reply': '28,204 tokens',
      'Writing speed': '193.5 tokens/s',
      'Model time': '2.05 s',
    })
    expect(c.text()).toContain('193.5 tok/s')
    c.unmount()
  })

  it('says a one-call reply made one call, with no reread or reuse note, and heads a priced reply\'s speed with its cost', async () => {
    const { c, rows, lines } = await openCard(32, {
      prompt: 900, completion: 300, total: 1_200, reasoning: 12, cached: 0, lastPrompt: 900, lastCompletion: 300,
      calls: 1, lastWriteMs: 1_000, durationMs: 1_200, promptPrice: 1, completionPrice: 2,
    })

    expect(lines).toEqual([
      'This reply',
      'Took 1.20 s',
      'One model call',
      'Tokens',
      'Memory',
      'The meter at the top shows this for the latest reply.',
      'Speed and cost',
    ])
    expect(rows).toEqual({
      'Read': '900',
      'Wrote': '300',
      'Thinking': '12',
      'After this reply': '1,200 tokens',
      'Writing speed': '300.0 tokens/s',
      'Cost': '$0.0015',
    })
    c.unmount()
  })

  it('on a reply saved before calls were counted, says only how long it took and names the average speed', async () => {
    const { c, rows, lines } = await openCard(33, {
      prompt: 1_000, completion: 50, total: 1_050, reasoning: 0, cached: 0, durationMs: 2_000,
    })

    expect(lines).toEqual([
      'This reply',
      'Took 2.00 s',
      'Tokens',
      'Speed',
      'Output over the whole reply\'s time, the wait for the first word included.',
    ])
    expect(rows).toEqual({ 'Read': '1,000', 'Wrote': '50', 'Average speed': '25.0 tokens/s' })
    expect(c.text()).toContain('25.0 tok/s avg')
    c.unmount()
  })

  it('names the router\'s choice beside the call count, with its reason on hover', async () => {
    const route = { class: 'agentic', provider: 'ollama-cloud', model: 'deepseek-v4.1-flash', reason: 'tool markers', failover: true }
    const { c } = await openCard(35, { ...twoCalls, route })
    const card = document.body.querySelector('[data-testid="usage-card"]')!
    const meta = [...card.querySelectorAll('p')].find(p => p.textContent?.includes('model calls'))!
    expect(meta.textContent?.trim()).toBe('2 model calls · Chosen by Auto · Agent work (failover)')
    expect(meta.getAttribute('title')).toBe('tool markers')
    c.unmount()
  })

  it('opens from the keyboard as it does on hover: focus asks for the card, leaving lets it go', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ id: 34, usage: twoCalls })) })
    const trigger = c.findAll('button').find(b => b.text() === '193.5 tok/s')!

    expect(trigger.attributes('type')).toBe('button')
    await trigger.trigger('focus')
    await trigger.trigger('blur')
    expect(c.emitted('set-tok-stats-hover-key')).toEqual([[34], [null]])
    c.unmount()
  })

  it('closes when the pointer leaves and leaves focus in the message box it was in', async () => {
    const composer = document.createElement('textarea')
    document.body.appendChild(composer)
    composer.focus()
    // The page owns the hover key: feed each request back in as chat.vue does.
    const c = await mountSuspended(ChatMessage, {
      props: props(msg({ id: 36, usage: twoCalls }), {
        'onSet-tok-stats-hover-key': (key: number | null) => { void c.setProps({ tokStatsHoverKey: key }) },
      }),
      attachTo: document.body,
    })
    const trigger = c.findAll('button').find(b => b.text() === '193.5 tok/s')!
    const card = () => document.body.querySelector('[data-testid="usage-card"]')
    try {
      await trigger.trigger('mouseenter')
      expect(card()).not.toBeNull()

      await trigger.trigger('mouseleave')
      await new Promise(resolve => setTimeout(resolve, 300))

      expect(document.activeElement).toBe(composer)
      expect(card()).toBeNull()
    }
    finally {
      c.unmount()
      composer.remove()
    }
  })

  it('shows the card on a click and never hides it on one: hovered, clicked, dismissed by Escape, clicked again', async () => {
    const c = await mountSuspended(ChatMessage, {
      props: props(msg({ id: 37, usage: twoCalls }), {
        'onSet-tok-stats-hover-key': (key: number | null) => { void c.setProps({ tokStatsHoverKey: key }) },
      }),
      attachTo: document.body,
    })
    const trigger = c.findAll('button').find(b => b.text() === '193.5 tok/s')!
    const card = () => document.body.querySelector('[data-testid="usage-card"]')
    try {
      await trigger.trigger('mouseenter')
      await trigger.trigger('click')
      await new Promise(resolve => setTimeout(resolve, 50))
      expect(card()).not.toBeNull()

      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
      await new Promise(resolve => setTimeout(resolve, 50))
      expect(card()).toBeNull()

      await trigger.trigger('click')
      await new Promise(resolve => setTimeout(resolve, 50))
      expect(card()).not.toBeNull()
    }
    finally {
      c.unmount()
    }
  })

  it('stays open while the pointer crosses from the pill into the card, and closes once it has left both', async () => {
    const c = await mountSuspended(ChatMessage, {
      props: props(msg({ id: 38, usage: twoCalls }), {
        'onSet-tok-stats-hover-key': (key: number | null) => { void c.setProps({ tokStatsHoverKey: key }) },
      }),
      attachTo: document.body,
    })
    const trigger = c.findAll('button').find(b => b.text() === '193.5 tok/s')!
    const card = () => document.body.querySelector('[data-testid="usage-card"]')
    try {
      await trigger.trigger('mouseenter')
      await trigger.trigger('mouseleave')
      const content = document.body.querySelector('[data-slot="popover-content"]')
      expect(content).not.toBeNull()

      content!.dispatchEvent(new MouseEvent('mouseenter'))
      await new Promise(resolve => setTimeout(resolve, 300))
      expect(card()).not.toBeNull()

      content!.dispatchEvent(new MouseEvent('mouseleave'))
      await new Promise(resolve => setTimeout(resolve, 300))
      expect(card()).toBeNull()
    }
    finally {
      c.unmount()
    }
  })

  it('keeps the card another reply\'s pill opened when the pointer moves on to it from this one', async () => {
    // One hover key for the transcript, as chat.vue holds it.
    function setKey(key: number | null) {
      for (const row of rows) void row.setProps({ tokStatsHoverKey: key })
    }
    const mountRow = (id: number) => mountSuspended(ChatMessage, {
      props: props(msg({ id, usage: twoCalls }), { 'onSet-tok-stats-hover-key': setKey }),
      attachTo: document.body,
    })
    const rows = [await mountRow(39), await mountRow(40)]
    const [first, second] = rows.map(row => row.findAll('button').find(b => b.text() === '193.5 tok/s')!)
    try {
      await first!.trigger('mouseenter')
      await first!.trigger('mouseleave')
      await second!.trigger('mouseenter')
      await new Promise(resolve => setTimeout(resolve, 300))

      expect(rows.map(row => row.props('tokStatsHoverKey'))).toEqual([40, 40])
      expect(document.body.querySelectorAll('[data-testid="usage-card"]')).toHaveLength(1)
    }
    finally {
      for (const row of rows) row.unmount()
    }
  })
})
