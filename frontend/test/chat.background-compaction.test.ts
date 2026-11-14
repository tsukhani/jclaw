import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises, type VueWrapper } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'
import { serveConversation } from './conversation-endpoint'
import { fakeConversationStreams } from './conversation-stream'

// The SSE bus is mocked so a test delivers the server's events to the handlers the page registers.
const bus = vi.hoisted(() => ({
  handlers: [] as Array<{ type: string, handler: (data: unknown, type: string) => void }>,
}))

vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => {
    const on = (type: string, handler: (data: unknown, type: string) => void) => {
      bus.handlers.push({ type, handler })
    }
    return { on, off: () => {}, onEvent: on, onOpen: () => {} }
  },
}))

function emitBus(type: string, data: unknown) {
  for (const entry of bus.handlers) {
    if (entry.type === type) entry.handler(data, type)
  }
}

enableAutoUnmount(afterEach)
afterAll(() => {
  for (const level of ['error', 'warn', 'log'] as const) vi.spyOn(console, level).mockImplementation(() => {})
})

let listReads: number
let compactions: number

beforeEach(() => {
  fakeConversationStreams()
  bus.handlers.length = 0
  listReads = 0
  compactions = 0
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
  registerEndpoint('/api/conversations', () => {
    listReads++
    return [{ id: 5, agentId: 1, channelType: 'web', compactionCount: compactions, messageRowCount: 20, userPromptCount: 10 }]
  })
  registerEndpoint('/api/conversations/5/queue', () => ({ busy: false, queueSize: 0 }))
  registerEndpoint('/api/subagent-runs', () => [])
  registerEndpoint('/api/mcp-servers/elicitations', () => [])
  serveConversation(5, [
    { id: 1, role: 'user', content: 'plan the trip', createdAt: '2026-11-14T10:00:01Z' },
    { id: 2, role: 'assistant', content: 'Here is the plan.', createdAt: '2026-11-14T10:00:02Z' },
  ])
})

afterEach(() => {
  vi.restoreAllMocks()
})

async function openConversation(convoId: number): Promise<VueWrapper> {
  const component = await mountSuspended(Chat)
  await flushPromises()
  await (component.vm as unknown as { loadConversation: (id: number) => Promise<void> }).loadConversation(convoId)
  await flushPromises()
  return component
}

describe('a compaction stored after a reply', () => {
  it('shows the compacted card with the messages it summarized, and reads the conversation\'s counts again', async () => {
    const component = await openConversation(5)
    const readsBefore = listReads
    compactions = 1

    emitBus('conversation.compacted', { conversationId: 5, rows: 10, ms: 3200 })
    await flushPromises()

    const card = component.get('[data-testid="chat-compaction-card"]')
    expect(card.get('[data-testid="compaction-title"]').text()).toBe('Conversation compacted')
    expect(card.get('[data-testid="compaction-result"]').text()).toBe('10 earlier messages summarized')
    expect(card.get('[data-testid="compaction-time"]').text()).toBe('3.2 s')
    await vi.waitFor(() => expect(listReads).toBeGreaterThan(readsBefore))
  })

  it('changes nothing on the page for another conversation\'s compaction', async () => {
    const component = await openConversation(5)
    const readsBefore = listReads

    emitBus('conversation.compacted', { conversationId: 6, rows: 10, ms: 3200 })
    await flushPromises()

    expect(component.find('[data-testid="chat-compaction-card"]').exists()).toBe(false)
    expect(listReads).toBe(readsBefore)
  })
})

describe('a /compact whose request is lost while it runs (JF-429)', () => {
  it('follows the command on the bus and shows its stored reply after it, as the page read it', async () => {
    const rows = [
      { id: 1, role: 'user', content: 'plan the trip', createdAt: '2026-11-14T10:00:01Z' },
      { id: 2, role: 'assistant', content: 'Here is the plan.', createdAt: '2026-11-14T10:00:02Z' },
    ]
    serveConversation(5, rows)
    registerEndpoint('/api/chat/turn', { method: 'POST', handler: () => new Promise<object>(() => {}) })
    const component = await openConversation(5)
    await component.find<HTMLTextAreaElement>('textarea').setValue('/compact')
    await component.find('form').trigger('submit.prevent')
    await flushPromises()
    const card = () => component.get('[data-testid="chat-compaction-card"]')
    const line = () => card().get('[data-testid="compaction-summary"]').text()
    expect(line()).toBe('Summarizing earlier messages')

    emitBus('conversation.compaction', { conversationId: 5, compaction: { phase: 'start', tokensBefore: 40000, rows: 11 } })
    await flushPromises()
    expect(line()).toBe('Summarizing 11 earlier messages · 40k tokens')
    emitBus('conversation.compaction', { conversationId: 5, compaction: { phase: 'progress', stage: 'merging', summaries: 2 } })
    await flushPromises()
    expect(line()).toBe('Merging 2 earlier summaries into one')

    emitBus('conversation.compaction', { conversationId: 5,
      compaction: { phase: 'done', tokensBefore: 40000, tokensAfter: 9000, ms: 557786 } })
    rows.push({ id: 3, role: 'assistant', content: '> Conversation Compacted', createdAt: '2026-11-14T10:09:20Z' })
    emitBus('conversation.updated', { conversationId: 5 })
    await flushPromises()

    expect(card().get('[data-testid="compaction-title"]').text()).toBe('Conversation compacted')
    expect(card().get('[data-testid="compaction-result"]').text()).toBe('40k → 9.0k tokens · 77% freed')
    const vm = component.vm as unknown as { messages: Array<{ role: string, content: string }> }
    await vi.waitFor(() => expect(vm.messages.map(m => `${m.role}: ${m.content}`)).toEqual([
      'user: plan the trip', 'assistant: Here is the plan.', 'user: /compact', 'assistant: > Conversation Compacted']))
  })
})

describe('a summary stored after the reply the meter reads, on a load', () => {
  const REPLY_AT = '2026-11-14T10:00:02Z'

  async function meterAfter(summaries: Array<{ compactedAt: string, status: string }>) {
    serveConversation(5, [
      { id: 1, role: 'user', content: 'plan the trip', createdAt: '2026-11-14T10:00:01Z' },
      { id: 2, role: 'assistant', content: 'Here is the plan.', createdAt: REPLY_AT,
        usage: { prompt: 83465, completion: 120, lastPrompt: 83345, lastCompletion: 120 } },
    ])
    registerEndpoint('/api/conversations/5/compactions', () => summaries.map((s, i) => ({
      id: 40 + i, rows: 17, summaryTokens: 500, model: 'openrouter/m', summary: 'S', coversFrom: null,
      coversTo: '2026-11-14T10:00:01Z', mergedIntoId: null, ...s })))
    const component = await openConversation(5)
    return component.findComponent({ name: 'ChatContextMeter' }).props('summarizedSinceReply')
  }

  it('tells the meter its count was taken before a live summary written after that reply', async () => {
    expect(await meterAfter([{ compactedAt: '2026-11-14T10:00:40Z', status: 'live' }])).toBe(true)
  })

  it('leaves the meter as it is for a summary written before the reply, or one a /reset set aside', async () => {
    expect(await meterAfter([{ compactedAt: '2026-11-14T10:00:01Z', status: 'live' }])).toBe(false)
    clearNuxtData()
    expect(await meterAfter([{ compactedAt: '2026-11-14T10:00:40Z', status: 'reset' }])).toBe(false)
  })
})
