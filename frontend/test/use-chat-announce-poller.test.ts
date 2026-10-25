import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { Message } from '~/types/api'
import {
  useChatAnnouncePoller,
  type UseChatAnnouncePoller,
  type UseChatAnnouncePollerDeps,
} from '~/composables/useChatAnnouncePoller'

// The SSE bus is mocked so a test delivers an event to the handler the poller registers.
const { busHandlers, emitBus } = vi.hoisted(() => {
  const busHandlers: Array<{ type: string, handler: (data: unknown, type: string) => void }> = []
  function emitBus(type: string, data: unknown) {
    for (const h of busHandlers) if (h.type === type) h.handler(data, type)
  }
  return { busHandlers, emitBus }
})

mockNuxtImport('useEventBus', () => {
  const on = (type: string, handler: (data: unknown, type: string) => void) => {
    busHandlers.push({ type, handler })
  }
  return () => ({ on, off: () => {}, onEvent: on, onOpen: () => {} })
})

let messageReads = 0

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

afterEach(() => {
  busHandlers.length = 0
  vi.useRealTimers()
})

beforeEach(() => {
  messageReads = 0
  registerEndpoint('/api/conversations/5/messages', () => {
    messageReads += 1
    return [
      { id: 1, role: 'user', content: 'hi' },
      { id: 2, role: 'assistant', content: 'reply', messageKind: 'subagent_announce', metadata: { runId: 9 } },
    ]
  })
})

function msg(over: Partial<Message>): Message {
  return { role: 'user', content: '', ...over } as Message
}

async function mountPoller(over: Partial<UseChatAnnouncePollerDeps> = {}) {
  const deps: UseChatAnnouncePollerDeps = {
    messages: shallowRef<Message[]>([]),
    selectedConvoId: ref<number | null>(null),
    streaming: ref(false),
    initSubagentCollapsedState: vi.fn(),
    ...over,
  }
  let api!: UseChatAnnouncePoller
  const wrapper = await mountSuspended(
    defineComponent({
      setup() {
        api = useChatAnnouncePoller(deps)
        return () => h('div')
      },
    }),
  )
  return { wrapper, deps, api }
}

describe('useChatAnnouncePoller', () => {
  it('detects a pending async announce from a tool-role row, cleared once announced', async () => {
    const messages = shallowRef<Message[]>([
      msg({ role: 'tool', content: '{"status":"RUNNING","run_id":"r1"}' }),
    ])
    const { api } = await mountPoller({ messages })
    expect(api.hasPendingAsyncAnnounce()).toBe(true)
    // Announce row for r1 lands → no longer pending.
    messages.value = [...messages.value, msg({ messageKind: 'subagent_announce', metadata: { runId: 'r1' } } as Partial<Message>)]
    expect(api.hasPendingAsyncAnnounce()).toBe(false)
  })

  it('detects a pending async announce from an assistant toolCall resultText', async () => {
    const { api } = await mountPoller({
      messages: shallowRef([
        msg({ role: 'assistant', toolCalls: [{ id: 't', name: 'spawn', resultText: '{"status":"RUNNING","run_id":"r2"}' }] } as Partial<Message>),
      ]),
    })
    expect(api.hasPendingAsyncAnnounce()).toBe(true)
  })

  it('hasRecentTaskCreate is true for a fresh task_manager call and false once stale', async () => {
    const recent = await mountPoller({
      messages: shallowRef([
        msg({ role: 'assistant', toolCalls: [{ id: 't', name: 'task_manager' }] } as Partial<Message>), // no createdAt → "now"
      ]),
    })
    expect(recent.api.hasRecentTaskCreate()).toBe(true)

    const stale = await mountPoller({
      messages: shallowRef([
        msg({
          role: 'assistant',
          createdAt: new Date(Date.now() - 40 * 60_000).toISOString(), // 40 min ago > 30 min grace
          toolCalls: [{ id: 't', name: 'task_manager' }],
        } as Partial<Message>),
      ]),
    })
    expect(stale.api.hasRecentTaskCreate()).toBe(false)
  })

  it('pollForAnnounce appends newly-arrived rows by id and re-runs the collapse init', async () => {
    const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'hi' })])
    const initSubagentCollapsedState = vi.fn()
    const { api } = await mountPoller({ messages, selectedConvoId: ref(5), initSubagentCollapsedState })
    await api.pollForAnnounce()
    expect(messages.value).toHaveLength(2) // id 2 appended
    expect(messages.value[1]).toMatchObject({ id: 2, messageKind: 'subagent_announce' })
    expect(initSubagentCollapsedState).toHaveBeenCalled()
  })

  it('hands each merge of new rows to appendRows, so the page can follow them down', async () => {
    const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'hi' })])
    const seen: number[][] = []
    const appendRows = vi.fn((append: () => void) => {
      seen.push(messages.value.map(m => m.id as number))
      append()
      seen.push(messages.value.map(m => m.id as number))
    })
    const { api } = await mountPoller({ messages, selectedConvoId: ref(5), appendRows })

    await api.pollForAnnounce()
    await api.pollForAnnounce()

    expect(seen).toEqual([[1], [1, 2]])
    expect(appendRows).toHaveBeenCalledTimes(1)
  })

  it('pollForAnnounce is a no-op with no open conversation', async () => {
    const messages = shallowRef<Message[]>([msg({ role: 'user', content: 'hi' })])
    const { api } = await mountPoller({ messages, selectedConvoId: ref(null) })
    await api.pollForAnnounce()
    expect(messages.value).toHaveLength(1)
  })

  it('reloads the open conversation when the server says it changed, and ignores another one', async () => {
    const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'hi' })])
    await mountPoller({ messages, selectedConvoId: ref(5) })

    emitBus('conversation.updated', { conversationId: 6 })
    await settle()
    expect(messageReads).toBe(0)

    emitBus('conversation.updated', { conversationId: 5 })
    await settle()
    expect(messageReads).toBe(1)
    expect(messages.value.map(m => m.id)).toEqual([1, 2])
  })

  it('leaves a change that lands mid-stream to the poll once the stream ends', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'hi' })])
    const streaming = ref(true)
    await mountPoller({ messages, selectedConvoId: ref(5), streaming })

    emitBus('conversation.updated', { conversationId: 5 })
    await settle()
    expect(messageReads).toBe(0)

    streaming.value = false
    vi.advanceTimersByTime(5000)
    await settle()
    expect(messageReads).toBe(1)
    expect(messages.value.map(m => m.id)).toEqual([1, 2])
  })
})
