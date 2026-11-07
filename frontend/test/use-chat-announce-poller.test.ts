import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { Message } from '~/types/api'
import { serveConversation } from './conversation-endpoint'
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

  it('pollForAnnounce appends newly-arrived rows by id', async () => {
    const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'hi' })])
    const { api } = await mountPoller({ messages, selectedConvoId: ref(5) })
    await api.pollForAnnounce()
    expect(messages.value).toHaveLength(2) // id 2 appended
    expect(messages.value[1]).toMatchObject({ id: 2, messageKind: 'subagent_announce' })
  })

  it('applies the subagent-run default to the rows of a run the merge adds, and leaves a run already shown alone', async () => {
    const at = '2026-11-06T10:00:00Z'
    serveConversation(7, [
      { id: 1, role: 'user', content: 'go', createdAt: at },
      { id: 2, role: 'assistant', content: 'Spawning subagent: scout', subagentRunId: 9, createdAt: at },
      { id: 3, role: 'assistant', content: 'Subagent completed', subagentRunId: 9, createdAt: at },
      { id: 4, role: 'assistant', content: 'Spawning subagent: fetch', subagentRunId: 10, createdAt: at },
    ])
    const messages = shallowRef<Message[]>([
      msg({ id: 1, role: 'user', content: 'go', createdAt: at }),
      msg({ id: 2, role: 'assistant', content: 'Spawning subagent: scout', subagentRunId: 9, createdAt: at }),
    ])
    const initSubagentCollapsedState = vi.fn()
    const { api } = await mountPoller({ messages, selectedConvoId: ref(7), initSubagentCollapsedState })

    await api.pollForAnnounce()

    expect(messages.value.map(m => m.id)).toEqual([1, 2, 3, 4])
    expect(initSubagentCollapsedState.mock.calls.map(([rows]) => (rows as Message[]).map(m => m.id))).toEqual([[4]])
  })

  describe('a shown row the window hydrates differently', () => {
    const at = '2026-11-06T10:00:00Z'

    function callRow(id: number, callId: string) {
      return { id, role: 'assistant', content: null, createdAt: at,
        toolCalls: [{ id: callId, type: 'function', function: { name: 'exec', arguments: '{}' } }] }
    }

    function resultRow(id: number, callId: string) {
      return { id, role: 'tool', content: `${callId} done`, createdAt: at, toolResults: callId }
    }

    function shownCall(callId: string, resultText: string | null) {
      return { id: callId, name: 'exec', icon: 'wrench', arguments: '{}', resultText, resultStructured: null }
    }

    /** Each row that holds tool calls, as its id and its calls' ids and results. */
    function owners(messages: Message[]) {
      return messages.filter(m => m.toolCalls?.length).map(m => [m.id, m.toolCalls!.map(tc => `${tc.id}: ${tc.resultText}`)])
    }

    it('takes a call\'s result that committed after the call, keeping the row and its open block', async () => {
      serveConversation(7, [{ id: 1, role: 'user', content: 'go', createdAt: at }, callRow(2, 'a'), resultRow(3, 'a')])
      const owner = msg({ id: 2, role: 'assistant', content: null, createdAt: at, toolCalls: [shownCall('a', null)], toolCallsCollapsed: false })
      const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'go', createdAt: at }), owner])
      const { api } = await mountPoller({ messages, selectedConvoId: ref(7) })

      await api.pollForAnnounce()

      expect(messages.value.map(m => m.id)).toEqual([1, 2, 3])
      expect(messages.value[1]).toBe(owner)
      expect(owners(messages.value)).toEqual([[2, ['a: a done']]])
      expect(owner.toolCallsCollapsed).toBe(false)
      expect(owner.toolCalls!.map(tc => tc._expanded)).toEqual([true])
    })

    it('puts a round that committed late in server order, gives the shown row its call, and shows no call twice', async () => {
      serveConversation(7, [
        { id: 1, role: 'user', content: 'go', createdAt: at },
        callRow(2, 'a'), resultRow(3, 'a'),
        callRow(4, 'b'), resultRow(5, 'b'),
        callRow(6, 'c'), resultRow(7, 'c'),
      ])
      // As the page holds them once rounds a and c have merged: both calls hang on row 6.
      const messages = shallowRef<Message[]>([
        msg({ id: 1, role: 'user', content: 'go', createdAt: at }),
        msg({ id: 2, role: 'assistant', content: null, createdAt: at }),
        msg({ id: 3, role: 'tool', content: 'a done', createdAt: at }),
        msg({ id: 6, role: 'assistant', content: null, createdAt: at,
          toolCalls: [shownCall('a', 'a done'), shownCall('c', 'c done')], toolCallsCollapsed: true }),
        msg({ id: 7, role: 'tool', content: 'c done', createdAt: at }),
      ])
      const { api } = await mountPoller({ messages, selectedConvoId: ref(7) })

      await api.pollForAnnounce()

      expect(messages.value.map(m => m.id)).toEqual([1, 2, 3, 4, 5, 6, 7])
      expect(owners(messages.value)).toEqual([[6, ['a: a done', 'b: b done', 'c: c done']]])
    })

    it('drops the older of two overlapping polls when it lands last, so its window changes nothing', async () => {
      const older = [{ id: 1, role: 'user', content: 'go', createdAt: at }, callRow(2, 'a'), resultRow(3, 'a')]
      const newer = [...older, callRow(4, 'b'), resultRow(5, 'b')]
      let reads = 0
      let releaseOlder!: () => void
      const olderHeld = new Promise<void>((resolve) => {
        releaseOlder = resolve
      })
      registerEndpoint('/api/conversations/8/messages', async () => {
        reads += 1
        if (reads === 1) {
          await olderHeld
          return structuredClone(older)
        }
        return structuredClone(newer)
      })
      const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'go', createdAt: at })])
      const { api } = await mountPoller({ messages, selectedConvoId: ref(8) })

      const first = api.pollForAnnounce()
      await vi.waitFor(() => expect(reads).toBe(1))
      await api.pollForAnnounce()
      const merged = messages.value
      expect(owners(merged)).toEqual([[4, ['a: a done', 'b: b done']]])

      releaseOlder()
      await first

      expect(messages.value).toBe(merged)
      expect(owners(messages.value)).toEqual([[4, ['a: a done', 'b: b done']]])
    })

    it('changes nothing and merges nothing when the window holds what the page shows', async () => {
      serveConversation(7, [{ id: 1, role: 'user', content: 'go', createdAt: at }, callRow(2, 'a'), resultRow(3, 'a')])
      const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'go', createdAt: at })])
      const appendRows = vi.fn((append: () => void) => append())
      const { api } = await mountPoller({ messages, selectedConvoId: ref(7), appendRows })

      await api.pollForAnnounce()
      const merged = messages.value
      await api.pollForAnnounce()

      expect(appendRows).toHaveBeenCalledTimes(1)
      expect(messages.value).toBe(merged)
      expect(owners(messages.value)).toEqual([[2, ['a: a done']]])
    })
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

  it('fetches a resync\'s full reload from just before the oldest shown row, not from the newest settled one', async () => {
    const at = (s: number) => new Date(Date.parse('2026-11-07T10:00:00Z') + s * 1000).toISOString()
    const rows = [
      { id: 10, role: 'user', content: 'first', createdAt: at(0) },
      { id: 11, role: 'assistant', content: 'reply', createdAt: at(1) },
      { id: 12, role: 'user', content: 'much later', createdAt: at(300) },
    ]
    const asked = serveConversation(9, rows)
    const messages = shallowRef<Message[]>(rows.map(r => msg({ ...r } as Partial<Message>)))
    const { api } = await mountPoller({ messages, selectedConvoId: ref(9), streaming: ref(true) })

    await api.mergeSavedRows()
    await api.mergeSavedRows({ full: true })

    expect(asked.map(q => q.after)).toEqual(['11', '9'])
  })

  it('keeps a reply\'s calls through a resync\'s reload after a tool-only round and a user row saved mid-turn', async () => {
    const at = '2026-11-07T10:00:00Z'
    serveConversation(9, [
      { id: 1, role: 'user', content: 'go', createdAt: at },
      { id: 2, role: 'assistant', content: 'Sure, checking.', createdAt: at },
      { id: 3, role: 'assistant', content: null, createdAt: at,
        toolCalls: [{ id: 'c1', type: 'function', function: { name: 'exec', arguments: '{}' } }] },
      { id: 4, role: 'tool', content: 'c1 done', createdAt: at, toolResults: 'c1' },
      { id: 5, role: 'user', content: 'a note saved mid-turn', createdAt: at },
      { id: 6, role: 'assistant', content: 'Here it is.', createdAt: at },
    ])
    const messages = shallowRef<Message[]>([])
    const { api } = await mountPoller({ messages, selectedConvoId: ref(9), streaming: ref(true) })
    await api.mergeSavedRows()
    const reply = () => messages.value.find(m => m.id === 6)!
    expect(reply().toolCalls!.map(tc => tc.id)).toEqual(['c1'])

    await api.mergeSavedRows({ full: true })

    expect(reply().toolCalls!.map(tc => tc.id)).toEqual(['c1'])
  })

  it('merges the rows a streaming turn asks for, leaving its bubble unpaired and last', async () => {
    const bubble = msg({ _key: 'bubble', role: 'assistant', content: 'Working on it' })
    const messages = shallowRef<Message[]>([msg({ id: 1, role: 'user', content: 'hi' }), bubble])
    const { api } = await mountPoller({ messages, selectedConvoId: ref(5), streaming: ref(true) })

    await api.pollForAnnounce()
    expect(messageReads).toBe(1)
    expect(messages.value.map(m => m.id ?? m._key)).toEqual([1, 'bubble'])

    await api.mergeSavedRows({ pairable: m => m !== bubble })
    expect(messages.value.map(m => m.id ?? m._key)).toEqual([1, 2, 'bubble'])
    expect(bubble.id).toBeUndefined()
  })
})
