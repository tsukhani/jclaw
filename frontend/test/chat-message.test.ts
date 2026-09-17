import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatMessage from '~/components/chat/ChatMessage.vue'
import type { Message } from '~/types/api'

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

  it('renders a ChatToolCalls block when the message carries tool calls', async () => {
    const withTools = msg({ role: 'assistant', content: 'done', toolCalls: [{ id: 't1', name: 'web_search', icon: 'search', arguments: JSON.stringify({ query: 'cats' }) }] })
    const c = await mountSuspended(ChatMessage, { props: props(withTools) })
    expect(c.text()).toContain('1 tool call')
    expect(c.text()).toContain('Searched "cats"')
  })

  it('renders a ChatThinkingCard when the message carries reasoning', async () => {
    const c = await mountSuspended(ChatMessage, { props: props(msg({ role: 'assistant', content: 'answer', reasoning: 'let me think' })) })
    const reasoningBody = c.find('[data-reasoning-body]')
    expect(reasoningBody.exists()).toBe(true)
    expect(reasoningBody.text()).toContain('let me think')
  })

  it('names the model the router chose, and says when it failed over (JCLAW-1222)', async () => {
    const route = { class: 'reasoning', provider: 'openrouter', model: 'deepseek-v4-pro', reason: 'reasoning markers: prove', failover: true }
    const c = await mountSuspended(ChatMessage, { props: props(msg({ usage: { prompt: 1, completion: 1, total: 2, reasoning: 0, cached: 0, durationMs: 1, route } })) })
    const badge = c.find('[data-testid="route-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toContain('Reasoning')
    expect(badge.text()).toContain('deepseek-v4-pro')
    expect(badge.text()).toContain('failover')
    expect(badge.attributes('title')).toContain('openrouter/deepseek-v4-pro')
  })

  it('shows the live route before the usage record lands, and no badge off the router', async () => {
    const live = msg({ content: '', _route: { class: 'chat', provider: 'ollama-cloud', model: 'glm-5.3-flash', reason: 'no task markers' } })
    const c = await mountSuspended(ChatMessage, { props: props(live, { streaming: true }) })
    expect(c.find('[data-testid="route-badge"]').text()).toContain('glm-5.3-flash')

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
})
