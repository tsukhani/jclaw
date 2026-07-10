import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick, ref } from 'vue'
import type { Agent, Message } from '~/types/api'
import {
  useChatConversation,
  type ChatConversationLoadHooks,
  type UseChatConversation,
} from '~/composables/useChatConversation'

// Register per-test (a top-level registration is reset before these run).
beforeEach(() => {
  registerEndpoint('/api/conversations/5/messages', () => [
    { id: 10, role: 'user', content: 'hi' },
    { id: 11, role: 'assistant', content: 'yo' },
  ])
  registerEndpoint('/api/conversations/5', () => ({ id: 5, agentId: 1, agentName: 'main' }))
  registerEndpoint('/api/conversations/9', () => ({ id: 9, agentId: 99, agentName: 'researcher' }))
  registerEndpoint('/api/conversations/9/messages', () => [
    { id: 20, role: 'assistant', content: 'subagent output' },
  ])
})

const AGENTS = [{ id: 1, name: 'main', isMain: true }] as unknown as Agent[]

async function mountConversation(agentsList: Agent[] = AGENTS) {
  const agents = ref<Agent[] | null | undefined>(agentsList)
  const selectedAgentId = ref<number | null>(null)
  const hooks: ChatConversationLoadHooks = { beforeLoad: vi.fn(), afterLoad: vi.fn() }
  let api!: UseChatConversation
  const wrapper = await mountSuspended(
    defineComponent({
      setup() {
        api = useChatConversation({ agents, selectedAgentId, hooks })
        return () => h('div')
      },
    }),
  )
  return { wrapper, agents, selectedAgentId, hooks, api }
}

describe('useChatConversation', () => {
  it('auto-selects the main agent when none is chosen', async () => {
    const { selectedAgentId } = await mountConversation()
    expect(selectedAgentId.value).toBe(1)
  })

  it('loadConversation fetches messages, sets state, and fires both hooks', async () => {
    const { api, hooks } = await mountConversation()
    await api.loadConversation(5)
    expect(api.selectedConvoId.value).toBe(5)
    expect(api.messages.value).toHaveLength(2)
    expect(api.messages.value[0]!.content).toBe('hi')
    expect(hooks.beforeLoad).toHaveBeenCalledOnce()
    expect(hooks.afterLoad).toHaveBeenCalledWith(api.messages.value)
  })

  it('collapses tool-call blocks by default on load and pre-expands the last call', async () => {
    // A persisted assistant row with two tool calls — reload should collapse
    // the outer accordion and pre-expand only the last call.
    registerEndpoint('/api/conversations/7/messages', () => [
      { id: 30, role: 'assistant', content: 'done', toolCalls: [{ id: 'a' }, { id: 'b' }] },
    ])
    const { api } = await mountConversation()
    await api.loadConversation(7)
    const m = api.messages.value[0] as Message & { toolCallsCollapsed?: boolean }
    expect(m.toolCallsCollapsed).toBe(true)
    expect(m.toolCalls![0]!._expanded).toBe(false)
    expect(m.toolCalls![1]!._expanded).toBe(true)
  })

  it('resolveAndLoadConversation switches to a known owning agent and clears any transcript', async () => {
    const { api, selectedAgentId } = await mountConversation()
    api.subagentTranscript.value = { agentId: 42, agentName: 'stale' }
    const ok = await api.resolveAndLoadConversation(5)
    expect(ok).toBe(true)
    expect(selectedAgentId.value).toBe(1) // convo 5 is owned by agent 1
    expect(api.subagentTranscript.value).toBeNull()
    expect(api.selectedConvoId.value).toBe(5)
  })

  it('resolveAndLoadConversation enters read-only transcript mode for a non-dropdown agent', async () => {
    const { api, selectedAgentId } = await mountConversation()
    const ok = await api.resolveAndLoadConversation(9)
    expect(ok).toBe(true)
    // Agent 99 is not in the dropdown → transcript mode, dropdown unchanged.
    expect(api.subagentTranscript.value).toEqual({ agentId: 99, agentName: 'researcher' })
    expect(selectedAgentId.value).toBe(1)
    expect(api.effectiveDisplayAgentId.value).toBe(99) // transcript agent wins
  })

  it('resolveAndLoadConversation returns false when the conversation cannot be fetched', async () => {
    const { api } = await mountConversation()
    const ok = await api.resolveAndLoadConversation(404) // no endpoint registered → throws
    expect(ok).toBe(false)
  })

  it('reconcileMessageIds backfills server ids onto id-less local rows', async () => {
    const { api } = await mountConversation()
    api.selectedConvoId.value = 5
    api.messages.value = [
      { role: 'user', content: 'hi' } as Message,
      { role: 'assistant', content: 'yo' } as Message,
    ]
    await api.reconcileMessageIds()
    expect(api.messages.value[0]!.id).toBe(10)
    expect(api.messages.value[1]!.id).toBe(11)
  })

  it('clears the open conversation when the agent changes (after init)', async () => {
    const { api, selectedAgentId } = await mountConversation()
    api.initializing.value = false // the page flips this off once conversations load
    api.selectedConvoId.value = 5
    api.messages.value = [{ role: 'user', content: 'hi' } as Message]
    api.subagentTranscript.value = { agentId: 9, agentName: 'x' }

    selectedAgentId.value = 2
    await nextTick()

    expect(api.selectedConvoId.value).toBeNull()
    expect(api.messages.value).toEqual([])
    expect(api.subagentTranscript.value).toBeNull()
  })

  it('does NOT clear the conversation on agent change during init', async () => {
    const { api, selectedAgentId } = await mountConversation()
    // initializing stays true (no deep-link block in this unit test)
    api.selectedConvoId.value = 5
    api.messages.value = [{ role: 'user', content: 'hi' } as Message]

    selectedAgentId.value = 2
    await nextTick()

    expect(api.selectedConvoId.value).toBe(5) // untouched during setup
    expect(api.messages.value).toHaveLength(1)
  })
})
