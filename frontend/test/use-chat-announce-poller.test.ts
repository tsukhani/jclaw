import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { Message } from '~/types/api'
import {
  useChatAnnouncePoller,
  type UseChatAnnouncePoller,
  type UseChatAnnouncePollerDeps,
} from '~/composables/useChatAnnouncePoller'

beforeEach(() => {
  registerEndpoint('/api/conversations/5/messages', () => [
    { id: 1, role: 'user', content: 'hi' },
    { id: 2, role: 'assistant', content: 'reply', messageKind: 'subagent_announce', metadata: { runId: 9 } },
  ])
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
  it('counts unique announced subagent run-ids', async () => {
    const { api } = await mountPoller({
      messages: shallowRef([
        msg({ messageKind: 'subagent_announce', metadata: { runId: 1 } } as Partial<Message>),
        msg({ messageKind: 'subagent_announce', metadata: { runId: 2 } } as Partial<Message>),
        msg({ messageKind: 'subagent_announce', metadata: { runId: 1 } } as Partial<Message>), // dup run-id
      ]),
    })
    expect(api.announcedSubagentCount.value).toBe(2)
  })

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

  it('pollForAnnounce is a no-op with no open conversation', async () => {
    const messages = shallowRef<Message[]>([msg({ role: 'user', content: 'hi' })])
    const { api } = await mountPoller({ messages, selectedConvoId: ref(null) })
    await api.pollForAnnounce()
    expect(messages.value).toHaveLength(1)
  })
})
