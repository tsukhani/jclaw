import { describe, it, expect, vi, beforeEach, afterEach, afterAll } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { setResponseStatus } from 'h3'
import { enableAutoUnmount, flushPromises, type VueWrapper } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'
import type { ConversationCompaction } from '~/types/api'
import { serveConversation } from './conversation-endpoint'
import { fakeConversationStreams, streamOn } from './conversation-stream'

/**
 * JF-422: Chat shows a lasting divider after the last message each summary the agent reads covers, numbered as the
 * conversation page numbers them and linking to that page's marker; it is read from the summaries endpoint, so it is
 * there after a reload and appears when a summary is stored while the chat is open.
 */

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

const at = (second: number) => `2026-11-14T10:00:${String(second).padStart(2, '0')}Z`

const MESSAGES = [
  { id: 1, role: 'user', content: 'm1 plan the trip', createdAt: at(1) },
  { id: 2, role: 'assistant', content: 'm2 here is the plan', createdAt: at(2) },
  { id: 3, role: 'user', content: 'm3 add a museum', createdAt: at(3) },
  { id: 4, role: 'assistant', content: 'm4 added the museum', createdAt: at(4) },
  { id: 5, role: 'user', content: 'm5 and a dinner', createdAt: at(5) },
  { id: 6, role: 'assistant', content: 'm6 booked dinner', createdAt: at(6) },
]

function summary(id: number, coversTo: string, over: Partial<ConversationCompaction> = {}): ConversationCompaction {
  return { id, rows: 2, summaryTokens: 40, model: 'ollama-cloud/kimi-k2.5', summary: `Summary ${id}.`,
    compactedAt: '2026-11-14T10:05:00Z', coversFrom: at(1), coversTo, mergedIntoId: null, status: 'live', ...over }
}

let summaries: ConversationCompaction[] | 'fail'
let summaryReads: number

beforeEach(() => {
  fakeConversationStreams()
  bus.handlers.length = 0
  summaries = []
  summaryReads = 0
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
  registerEndpoint('/api/conversations', () => [{ id: 5, agentId: 1, channelType: 'web', compactionCount: 0 }])
  registerEndpoint('/api/conversations/5/queue', () => ({ busy: false, queueSize: 0 }))
  registerEndpoint('/api/subagent-runs', () => [])
  registerEndpoint('/api/mcp-servers/elicitations', () => [])
  registerEndpoint('/api/conversations/5/compactions', (event) => {
    summaryReads++
    if (summaries !== 'fail') return summaries
    setResponseStatus(event, 500)
    return { error: 'down' }
  })
  serveConversation(5, MESSAGES)
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

/** The rail in order: each message by its content's first word, each divider by its link's text. */
function rail(component: VueWrapper): string[] {
  const out: string[] = []
  const column = component.get('[data-testid="chat-messages-scroll"] > div').element
  for (const el of [...column.children]) {
    let label: string | undefined
    if (el.getAttribute('data-testid') === 'chat-summary-divider') {
      label = el.querySelector('[data-testid="chat-summary-divider-link"]')?.textContent?.replace(/\s+/g, ' ').trim()
    }
    else {
      label = MESSAGES.find(m => el.textContent?.includes(m.content))?.content.split(' ')[0]
    }
    if (label && out.at(-1) !== label) out.push(label)
  }
  return out
}

describe('Chat — summary dividers', () => {
  it('places one after the last message each summary the agent reads covers, numbered as written, on a reload', async () => {
    summaries = [
      summary(11, at(2)),
      summary(12, at(3), { status: 'reset' }),
      summary(13, at(4), { rows: 24 }),
      summary(14, at(4), { status: 'merged', mergedIntoId: 13 }),
    ]

    const component = await openConversation(5)

    await vi.waitFor(() => expect(rail(component)).toEqual([
      'm1', 'm2',
      'Summary 1 · 2 earlier messages summarized, read it on the conversation page',
      'm3', 'm4',
      'Summary 3 · 24 earlier messages summarized, read it on the conversation page',
      'm5', 'm6',
    ]))
  })

  it('links each divider to that summary\'s marker on the conversation page', async () => {
    summaries = [summary(11, at(2)), summary(12, at(4))]

    const component = await openConversation(5)

    await vi.waitFor(() => expect(component.findAll('[data-testid="chat-summary-divider-link"]')
      .map(link => link.attributes('href'))).toEqual(['/conversations/5#summary-1', '/conversations/5#summary-2']))
  })

  it('places a summary that ends before every message above the first, when no earlier messages are left to load', async () => {
    summaries = [summary(11, '2026-11-14T09:59:00Z', { rows: 8 })]

    const component = await openConversation(5)

    await vi.waitFor(() => expect(rail(component)).toEqual([
      'Summary 1 · 8 earlier messages summarized, read it on the conversation page',
      'm1', 'm2', 'm3', 'm4', 'm5', 'm6',
    ]))
  })

  it('shows no divider for a conversation with no summary', async () => {
    const component = await openConversation(5)
    await vi.waitFor(() => expect(summaryReads).toBeGreaterThan(0))
    await flushPromises()

    expect(rail(component)).toEqual(['m1', 'm2', 'm3', 'm4', 'm5', 'm6'])
  })

  it('drops its dividers rather than keep stale ones when the summaries fail to load again', async () => {
    summaries = [summary(11, at(2))]
    const component = await openConversation(5)
    await vi.waitFor(() => expect(component.findAll('[data-testid="chat-summary-divider"]')).toHaveLength(1))
    const readsBefore = summaryReads
    summaries = 'fail'

    emitBus('conversation.compacted', { conversationId: 5, rows: 4, ms: 2100 })

    await vi.waitFor(() => expect(summaryReads).toBeGreaterThan(readsBefore))
    await vi.waitFor(() => expect(rail(component)).toEqual(['m1', 'm2', 'm3', 'm4', 'm5', 'm6']))
  })

  it('adds a divider when a summary is stored after a reply, while the chat is open', async () => {
    const component = await openConversation(5)
    await vi.waitFor(() => expect(summaryReads).toBeGreaterThan(0))
    expect(component.findAll('[data-testid="chat-summary-divider"]')).toHaveLength(0)
    summaries = [summary(11, at(4), { rows: 4 })]

    emitBus('conversation.compacted', { conversationId: 5, rows: 4, ms: 2100 })

    await vi.waitFor(() => expect(rail(component)).toEqual([
      'm1', 'm2', 'm3', 'm4',
      'Summary 1 · 4 earlier messages summarized, read it on the conversation page',
      'm5', 'm6',
    ]))
  })

  it('adds a divider when a turn\'s own compaction ends, and reads nothing more for one that stored no summary', async () => {
    const component = await openConversation(5)
    await vi.waitFor(() => expect(summaryReads).toBeGreaterThan(0))
    const readsBefore = summaryReads
    const stream = streamOn(5)
    stream.send({ type: 'status', content: JSON.stringify({ compaction: { phase: 'start', tokensBefore: 9000, rows: 4 } }) })
    stream.send({ type: 'status', content: JSON.stringify({ compaction: { phase: 'skipped', reason: 'empty summary' } }) })
    await flushPromises()
    expect(summaryReads).toBe(readsBefore)

    summaries = [summary(11, at(2))]
    stream.send({ type: 'status', content: JSON.stringify({ compaction: { phase: 'start', tokensBefore: 9000, rows: 4 } }) })
    stream.send({ type: 'status', content: JSON.stringify({ compaction: { phase: 'done', tokensBefore: 9000, tokensAfter: 3000, ms: 1500 } }) })

    await vi.waitFor(() => expect(rail(component)).toEqual([
      'm1', 'm2',
      'Summary 1 · 2 earlier messages summarized, read it on the conversation page',
      'm3', 'm4', 'm5', 'm6',
    ]))
    expect(summaryReads).toBe(readsBefore + 1)
  })
})

describe('Chat — a summary of messages older than the rows shown', () => {
  it('waits for Load earlier messages, then shows after the last message it covers', async () => {
    const rows = Array.from({ length: 150 }, (_, i) => ({
      id: i + 1, role: i % 2 === 0 ? 'user' : 'assistant', content: `row ${i + 1}`,
      createdAt: new Date(Date.parse('2026-11-14T09:00:00Z') + i * 1000).toISOString(),
    }))
    serveConversation(5, rows)
    summaries = [summary(11, rows[9]!.createdAt, { rows: 10 })]

    const component = await openConversation(5)
    await vi.waitFor(() => expect(summaryReads).toBeGreaterThan(0))
    await flushPromises()
    expect(component.find('[data-testid="chat-load-older"]').exists()).toBe(true)
    expect(component.findAll('[data-testid="chat-summary-divider"]')).toHaveLength(0)

    await component.get('[data-testid="chat-load-older"]').trigger('click')
    await flushPromises()

    await vi.waitFor(() => expect(component.findAll('[data-testid="chat-summary-divider"]')).toHaveLength(1))
    const divider = component.get('[data-testid="chat-summary-divider"]').element
    expect(divider.previousElementSibling?.textContent).toContain('row 10')
    expect(divider.nextElementSibling?.textContent).toContain('row 11')
  })
})
