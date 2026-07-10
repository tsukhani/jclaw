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
]

// Record PUT bodies so the write-path tests can assert what was sent.
const put = vi.hoisted(() => ({ agent: null as unknown, override: null as unknown }))

beforeEach(() => {
  put.agent = null
  put.override = null
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
    refreshAgents: vi.fn(),
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

  it('toggleThinkingPill turns thinking on with a level and PUTs the agent', async () => {
    const { api, deps } = await mountAgentModel({ agents: ref([agent({ thinkingMode: null })]) })
    api.toggleThinkingPill()
    await vi.waitFor(() => expect(deps.refreshAgents).toHaveBeenCalled())
    expect(put.agent).toEqual({ thinkingMode: 'medium' }) // session default
  })

  it('toggleThinkingPill turns thinking off when already active', async () => {
    const { api, deps } = await mountAgentModel({ agents: ref([agent({ thinkingMode: 'high' })]) })
    api.toggleThinkingPill()
    await vi.waitFor(() => expect(deps.refreshAgents).toHaveBeenCalled())
    expect(put.agent).toEqual({ thinkingMode: null })
  })

  it('setThinkingLevel writes the level and closes the menu', async () => {
    const { api, deps } = await mountAgentModel({ agents: ref([agent({ thinkingMode: 'low' })]) })
    api.setThinkingLevel('high')
    expect(api.thinkingMenuOpen.value).toBe(false)
    await vi.waitFor(() => expect(deps.refreshAgents).toHaveBeenCalled())
    expect(put.agent).toEqual({ thinkingMode: 'high' })
  })

  it('onModelKeyChange writes a conversation override when a conversation is open', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(5) })
    await api.onModelKeyChange('anthropic::opus')
    expect(put.override).toEqual({ modelProvider: 'anthropic', modelId: 'opus' })
    expect(deps.refreshConversations).toHaveBeenCalled()
    expect(put.agent).toBeNull() // agent default untouched
  })

  it('onModelKeyChange mutates the agent default when no conversation is open', async () => {
    const { api, deps } = await mountAgentModel({ selectedConvoId: ref(null) })
    api.onModelKeyChange('anthropic::opus')
    await vi.waitFor(() => expect(deps.refreshAgents).toHaveBeenCalled())
    expect(put.agent).toMatchObject({ modelProvider: 'anthropic', modelId: 'opus' })
    expect(put.override).toBeNull()
  })

  it('clears an incompatible thinking level when switching to a non-thinking model', async () => {
    // Agent on a thinking model at level "high"; switch to gpt-3 (no thinking).
    const { api } = await mountAgentModel({
      selectedConvoId: ref(null),
      agents: ref([agent({ thinkingMode: 'high' })]),
    })
    api.onModelKeyChange('openai::gpt-3')
    await vi.waitFor(() => expect(put.agent).not.toBeNull())
    expect(put.agent).toMatchObject({ modelProvider: 'openai', modelId: 'gpt-3', thinkingMode: null })
  })

  it('opens the thinking menu only when thinking is active and supported', async () => {
    const { api } = await mountAgentModel({ agents: ref([agent({ thinkingMode: null })]) })
    api.openThinkingMenu()
    expect(api.thinkingMenuOpen.value).toBe(false) // inactive → stays closed
  })
})
