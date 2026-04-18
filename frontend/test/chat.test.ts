import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'

function setupChatApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
    { id: 2, name: 'secondary', modelProvider: 'openai', modelId: 'gpt-4', enabled: true, isMain: false, thinkingMode: null, providerConfigured: true },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]' },
      { key: 'provider.openai.baseUrl', value: 'https://api.openai.com' },
      { key: 'provider.openai.apiKey', value: 'sk-xxxx****' },
      { key: 'provider.openai.models', value: '[{"id":"gpt-4","name":"GPT-4","supportsThinking":false}]' },
    ]
  }))
  registerEndpoint('/api/conversations', () => [
    { id: 10, agentId: 1, agentName: 'main-agent', channelType: 'web', peerId: 'admin', messageCount: 2, preview: 'Hello world', createdAt: '2026-04-07T10:00:00Z', updatedAt: '2026-04-07T10:00:00Z' }
  ])
}

describe('Chat page', () => {
  it('renders with agent selector', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    // Should have at least the agent select dropdown
    const selects = component.findAll('select')
    expect(selects.length).toBeGreaterThanOrEqual(1)

    // Agent label should be present
    expect(component.text()).toContain('Agent:')
  })

  it('shows agent options in the selector', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    expect(component.text()).toContain('main-agent')
    expect(component.text()).toContain('secondary')
  })

  it('renders model selector', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    expect(component.text()).toContain('Model:')
    // Model names from config should appear as options
    expect(component.text()).toContain('Kimi K2.5')
  })

  it('hides thinking selector for non-thinking models', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    // Both mock models have supportsThinking:false, so no Thinking: label should
    // render in the toolbar — the per-model selector is only shown when the
    // currently selected model advertises reasoning support.
    expect(component.text()).not.toContain('Thinking:')
  })

  it('renders conversation sidebar', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)
    await flushPromises()

    expect(component.text()).toContain('Conversations')
    expect(component.text()).toContain('Hello world')
  })

  it('has a resize handle between sidebar and chat area', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    // The resize handle is a div with cursor-col-resize class
    const resizeHandle = component.find('.cursor-col-resize')
    expect(resizeHandle.exists()).toBe(true)
  })

  it('has a chat input textarea', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    const textarea = component.find('textarea')
    expect(textarea.exists()).toBe(true)
  })

  it('has an export button', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    // Export button has title="Export as Markdown"
    const exportBtn = component.find('button[title="Export as Markdown"]')
    expect(exportBtn.exists()).toBe(true)
  })

  it('shows file attachment button', async () => {
    setupChatApi()
    const component = await mountSuspended(Chat)

    // There should be a hidden file input for attachments
    const fileInput = component.find('input[type="file"]')
    expect(fileInput.exists()).toBe(true)
  })

  // Kept LAST intentionally: registerEndpoint() persists across tests within a
  // file, and this case overrides /api/agents + /api/config + /api/conversations
  // with a thinking-capable fixture. Putting it at the end prevents leakage
  // into unrelated tests above that expect the default non-thinking fixture.
  it('renders thinking level selector for thinking-capable models', async () => {
    // Clear Nuxt's useFetch cache so re-registered endpoints take effect.
    // In Nuxt 4, useFetch shares data across calls with the same key (URL),
    // so stale data from prior tests would otherwise persist.
    clearNuxtData()

    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'reasoning-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true, thinkingMode: 'medium', providerConfigured: true },
    ])
    registerEndpoint('/api/config', () => ({
      entries: [
        { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
        { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
        // Explicit thinkingLevels populate the dropdown; supportsThinking gates its visibility.
        { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":true,"thinkingLevels":["low","medium","high"]}]' },
      ]
    }))
    registerEndpoint('/api/conversations', () => [])

    const component = await mountSuspended(Chat)
    await flushPromises()

    expect(component.text()).toContain('Thinking:')
    expect(component.text()).toContain('Low')
    expect(component.text()).toContain('Medium')
    expect(component.text()).toContain('High')
    // On/off is owned by the Think pill in the input footer, not by a dropdown
    // option — so the dropdown must NOT contain an "Off" entry any more.
    expect(component.text()).not.toContain('>Off<')
    // The pill itself is a button labelled "Think".
    expect(component.text()).toContain('Think')
  })
})
