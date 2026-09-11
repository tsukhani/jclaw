import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { defineComponent, h, ref } from 'vue'
import type { Agent, Conversation } from '~/types/api'
import type { Provider } from '~/composables/useProviders'
import { useAgentModel, type UseAgentModel, type UseAgentModelDeps } from '~/composables/useAgentModel'

const PROVIDERS: Provider[] = [
  {
    name: 'openai',
    models: [
      { id: 'gpt-4', name: 'GPT-4', supportsThinking: true, thinkingLevels: ['low', 'medium', 'high'], supportsVision: true },
      { id: 'gpt-3', name: 'GPT-3', supportsThinking: false },
    ],
  },
  { name: 'anthropic', models: [{ id: 'opus', name: 'Opus', supportsThinking: true, supportsVision: true, supportsAudio: true }] },
  {
    name: 'ollama-cloud',
    models: [
      // Always thinks AND exposes an effort ladder — the two are independent axes.
      { id: 'glm-5.3-flash', name: 'GLM 5.3 Flash', supportsThinking: true, alwaysThinks: true, thinkingLevels: ['low', 'high', 'max'] },
      // Always thinks with nothing to choose between: genuinely inoperable.
      { id: 'one-rung', name: 'One Rung', supportsThinking: true, alwaysThinks: true, thinkingLevels: ['high'] },
    ],
  },
]

// Record PUT bodies so the write-path tests can assert what was sent. `agent` must stay
// null in every case: JCLAW-1196 moved every pick off the agent row.
const put = vi.hoisted(() => ({ agent: null as unknown, override: null as unknown, thinking: null as unknown }))

beforeEach(() => {
  put.agent = null
  put.override = null
  put.thinking = null
  registerEndpoint('/api/conversations/5/thinking-override', {
    method: 'PUT',
    handler: async (event) => {
      const { readBody } = await import('h3')
      put.thinking = await readBody(event)
      return {}
    },
  })
  registerEndpoint('/api/agents/1', {
    method: 'PUT',
    handler: async (event) => {
      const { readBody } = await import('h3')
      put.agent = await readBody(event)
      return {}
    },
  })
  registerEndpoint('/api/conversations/5/model-override', {
    method: 'PUT',
    handler: async (event) => {
      const { readBody } = await import('h3')
      put.override = await readBody(event)
      return {}
    },
  })
})

function agent(over: Partial<Agent> = {}): Agent {
  return { id: 1, name: 'main', isMain: true, modelProvider: 'openai', modelId: 'gpt-4', ...over } as Agent
}

async function mountAgentModel(over: Partial<UseAgentModelDeps> = {}) {
  const deps: UseAgentModelDeps = {
    agents: ref<Agent[]>([agent()]),
    selectedAgentId: ref<number | null>(1),
    selectedConvoId: ref<number | null>(null),
    conversations: ref<Conversation[]>([]),
    providers: ref<Provider[]>(PROVIDERS),
    refreshConversations: vi.fn(),
    ...over,
  }
  let api!: UseAgentModel
  const wrapper = await mountSuspended(
    defineComponent({
      setup() {
        api = useAgentModel(deps)
        return () => h('div')
      },
    }),
  )
  return { wrapper, deps, api }
}

describe('useAgentModel', () => {
  it('resolves the selected model and its capability pills from the agent default', async () => {
    const { api } = await mountAgentModel()
    expect(api.selectedModelInfo.value?.id).toBe('gpt-4')
    expect(api.selectedModelKey.value).toBe('openai::gpt-4')
    expect(api.thinkingSupported.value).toBe(true)
    expect(api.visionSupported.value).toBe(true)
    expect(api.audioSupported.value).toBe(false)
    expect(api.thinkingLevels.value).toEqual(['low', 'medium', 'high'])
  })

  it('honors a JCLAW-108 per-conversation model override', async () => {
    const conversations = ref<Conversation[]>([
      { id: 5, modelProviderOverride: 'anthropic', modelIdOverride: 'opus' } as unknown as Conversation,
    ])
    const { api } = await mountAgentModel({ conversations, selectedConvoId: ref(5) })
    expect(api.selectedModelKey.value).toBe('anthropic::opus') // override wins
    expect(api.selectedModelInfo.value?.id).toBe('opus')
    expect(api.audioSupported.value).toBe(true) // opus caps, not gpt-4's
  })

  it('reflects the agent thinkingMode in thinkingActive', async () => {
    const { api, deps } = await mountAgentModel({ agents: ref([agent({ thinkingMode: 'high' })]) })
    expect(api.thinkingActive.value).toBe(true)
    deps.agents.value = [agent({ thinkingMode: null })]
    await Promise.resolve()
    expect(api.thinkingActive.value).toBe(false)
  })

  it('toggleThinkingPill turns thinking on for the open conversation, not the agent', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(5), agents: ref([agent({ thinkingMode: null })]) })
    api.toggleThinkingPill()
    await vi.waitFor(() => expect(deps.refreshConversations).toHaveBeenCalled())
    expect(put.thinking).toEqual({ thinkingMode: 'medium' }) // session default
    expect(put.agent).toBeNull()
  })

  it('toggleThinkingPill turns thinking off for the open conversation when active', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(5), agents: ref([agent({ thinkingMode: 'high' })]) })
    api.toggleThinkingPill()
    await vi.waitFor(() => expect(deps.refreshConversations).toHaveBeenCalled())
    expect(put.thinking).toEqual({ thinkingMode: 'off' })
    expect(put.agent).toBeNull()
  })

  it('setThinkingLevel writes the level to the open conversation and closes the menu', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(5), agents: ref([agent({ thinkingMode: 'low' })]) })
    api.setThinkingLevel('high')
    expect(api.thinkingMenuOpen.value).toBe(false)
    await vi.waitFor(() => expect(deps.refreshConversations).toHaveBeenCalled())
    expect(put.thinking).toEqual({ thinkingMode: 'high' })
    expect(put.agent).toBeNull()
  })

  it('on a fresh chat a thinking pick is held for the first message and shown as active', async () => {
    const { api } = await mountAgentModel({ selectedConvoId: ref(null), agents: ref([agent({ thinkingMode: null })]) })
    api.setThinkingLevel('high')
    expect(api.pendingOverrides.value).toEqual({ thinkingMode: 'high' })
    expect(api.thinkingActive.value).toBe(true)
    expect(api.currentThinkingLevel.value).toBe('high')
    expect(put.agent).toBeNull()
    expect(put.thinking).toBeNull()
  })

  it('a conversation thinking override beats the agent default, and off means off', async () => {
    const conv = { id: 5, thinkingModeOverride: 'off' } as unknown as Conversation
    const { api } = await mountAgentModel({ selectedConvoId: ref(5), conversations: ref([conv]), agents: ref([agent({ thinkingMode: 'high' })]) })
    expect(api.thinkingActive.value).toBe(false)
  })

  it('onModelKeyChange writes a conversation override when a conversation is open', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(5) })
    await api.onModelKeyChange('anthropic::opus')
    expect(put.override).toEqual({ modelProvider: 'anthropic', modelId: 'opus' })
    expect(deps.refreshConversations).toHaveBeenCalled()
    expect(put.agent).toBeNull() // agent default untouched
  })

  it('onModelKeyChange on a fresh chat holds the pick for the first message and leaves the agent alone', async () => {
    const { api } = await mountAgentModel({ selectedConvoId: ref(null) })
    await api.onModelKeyChange('anthropic::opus')
    expect(api.pendingOverrides.value).toEqual({ modelProvider: 'anthropic', modelId: 'opus' })
    expect(api.selectedModelKey.value).toBe('anthropic::opus') // the header follows the pick
    expect(put.agent).toBeNull()
    expect(put.override).toBeNull()
  })

  it('a fresh-chat pick whose model lacks the current level makes the off explicit', async () => {
    // Agent at "medium"; glm-5.3-flash advertises low/high/max only.
    const { api } = await mountAgentModel({ selectedConvoId: ref(null), agents: ref([agent({ thinkingMode: 'medium' })]) })
    await api.onModelKeyChange('ollama-cloud::glm-5.3-flash')
    expect(api.pendingOverrides.value).toEqual({ modelProvider: 'ollama-cloud', modelId: 'glm-5.3-flash', thinkingMode: 'off' })
    expect(put.agent).toBeNull()
  })

  it('a fresh-chat pick of a non-thinking model needs no thinking override at all', async () => {
    const { api } = await mountAgentModel({ selectedConvoId: ref(null), agents: ref([agent({ thinkingMode: 'high' })]) })
    await api.onModelKeyChange('openai::gpt-3')
    expect(api.pendingOverrides.value).toEqual({ modelProvider: 'openai', modelId: 'gpt-3' })
    expect(put.agent).toBeNull()
  })

  it('pending picks keep speaking for the header until the new conversation\'s row arrives', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(null) })
    await api.onModelKeyChange('anthropic::opus')
    expect(api.pendingOverrides.value).not.toBeNull()
    // The init frame assigns the id first; the list refresh with the persisted override lands later.
    deps.selectedConvoId.value = 5
    await vi.waitFor(() => expect(api.pendingOverrides.value).toBeNull()) // nothing more to send
    expect(api.selectedModelKey.value).toBe('anthropic::opus') // but the header still shows the pick
    deps.conversations.value = [{ id: 5, modelProviderOverride: 'anthropic', modelIdOverride: 'opus' } as unknown as Conversation]
    await vi.waitFor(() => expect(api.selectedModelKey.value).toBe('anthropic::opus'))
  })

  it('pending picks are dropped when the agent changes', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(null) })
    await api.onModelKeyChange('anthropic::opus')
    deps.selectedAgentId.value = 2
    await vi.waitFor(() => expect(api.pendingOverrides.value).toBeNull())
  })

  it('opens the thinking menu only when thinking is active and supported', async () => {
    const { api } = await mountAgentModel({ agents: ref([agent({ thinkingMode: null })]) })
    api.openThinkingMenu()
    expect(api.thinkingMenuOpen.value).toBe(false) // inactive → stays closed
  })

  // === always-thinking models: the lock covers on/off, not effort ===

  it('reports thinking as active on a locked model even with no level stored', async () => {
    // The model reasons regardless of what we persist, so reporting "off" would
    // contradict both the pill and the request the backend actually sends.
    const { api } = await mountAgentModel({
      agents: ref([agent({ modelProvider: 'ollama-cloud', modelId: 'glm-5.3-flash', thinkingMode: null })]),
    })
    expect(api.thinkingLock.value.locked).toBe(true)
    expect(api.thinkingActive.value).toBe(true)
  })

  it('leaves the level menu reachable on a locked model that advertises a ladder', async () => {
    const { api } = await mountAgentModel({
      agents: ref([agent({ modelProvider: 'ollama-cloud', modelId: 'glm-5.3-flash', thinkingMode: null })]),
    })
    expect(api.thinkingLevels.value).toEqual(['low', 'high', 'max'])
    expect(api.thinkingPillInert.value).toBe(false)
    expect(api.thinkingPillTitle.value).toContain('Hover to pick an effort level.')
    api.openThinkingMenu()
    expect(api.thinkingMenuOpen.value).toBe(true)
  })

  it('treats a locked model with a single rung as genuinely inoperable', async () => {
    // Nothing to pick between, so the pill really is inert and must say so —
    // aria-disabled on a menu trigger with a menu would be a lie either way.
    const { api } = await mountAgentModel({
      agents: ref([agent({ modelProvider: 'ollama-cloud', modelId: 'one-rung', thinkingMode: null })]),
    })
    expect(api.thinkingPillInert.value).toBe(true)
    expect(api.thinkingPillTitle.value).toBe(api.thinkingLock.value.reason)
  })

  it('setThinkingLevel writes the chosen effort on a locked model', async () => {
    // The regression this guards: the lock used to bar setThinkingLevel outright,
    // which left these models pinned to the vendor default with no way down.
    const { api, deps } = await mountAgentModel({
      selectedConvoId: ref(5),
      agents: ref([agent({ modelProvider: 'ollama-cloud', modelId: 'glm-5.3-flash', thinkingMode: null })]),
    })
    api.setThinkingLevel('max')
    await vi.waitFor(() => expect(deps.refreshConversations).toHaveBeenCalled())
    expect(put.thinking).toEqual({ thinkingMode: 'max' })
    expect(put.agent).toBeNull()
  })

  it('still refuses to toggle thinking off on a locked model', async () => {
    const { api } = await mountAgentModel({
      agents: ref([agent({ modelProvider: 'ollama-cloud', modelId: 'glm-5.3-flash', thinkingMode: 'high' })]),
    })
    api.toggleThinkingPill()
    expect(put.agent).toBeNull()
  })
})
