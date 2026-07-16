import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'

/**
 * The Apps "Create app" affordance hands off to Chat via ?compose=<request>.
 * chat.vue prefills the composer from it on mount (review-then-send). Inject the
 * query with a mocked useRoute; the real router handles the follow-up replace().
 */
const routeQuery = { value: {} as Record<string, string> }
mockNuxtImport('useRoute', () => () => ({
  query: routeQuery.value, path: '/chat', params: {}, fullPath: '/chat', hash: '', name: 'chat', meta: {}, matched: [],
}))

function setupChatApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]' },
    ],
  }))
  registerEndpoint('/api/conversations', () => [])
}

describe('Chat — app-creator hand-off prefill', () => {
  it('prefills the composer from ?compose=', async () => {
    routeQuery.value = { compose: 'Use the app-creator skill to build a hosted app.\n\nApp name: X\nWhat it should do: Y' }
    setupChatApi()
    const c = await mountSuspended(Chat)
    await flushPromises()

    const el = c.find('#chat-message-input').element as HTMLTextAreaElement
    expect(el.value).toContain('app-creator skill')
    expect(el.value).toContain('App name: X')
  })

  it('leaves the composer empty without ?compose=', async () => {
    routeQuery.value = {}
    setupChatApi()
    const c = await mountSuspended(Chat)
    await flushPromises()

    const el = c.find('#chat-message-input').element as HTMLTextAreaElement
    expect(el.value).toBe('')
  })
})
