import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-1449: Settings → LLM Providers shows the "Cloud usage" block on the Ollama Cloud card only,
 * and only once that provider has a key — /api/providers lists a provider only then.
 */
const ENTRIES = [
  { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
  { key: 'provider.ollama-cloud.apiKey', value: 'fake****' },
  { key: 'provider.openrouter.baseUrl', value: 'https://openrouter.ai/api/v1' },
  { key: 'provider.openrouter.apiKey', value: 'fake****' },
]

function provider(name: string) {
  return { name, paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 0, supportedModalities: [], local: false }
}

let usageReads = 0

function endpoints(registered: string[]) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => registered.map(provider))
  registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: ENTRIES }) })
  registerEndpoint('/api/router/usage/ollama-cloud', () => {
    usageReads++
    return {
      provider: 'ollama-cloud',
      range: '7d',
      total: 3,
      buckets: [{ from: '2026-10-14T00:00:00Z', until: '2026-10-15T00:00:00Z', requestCount: 3, partial: false }],
      fetchedAt: '2026-10-15T13:17:00Z',
    }
  })
}

async function mountProviders() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'providers'
  await flushPromises()
  await flushPromises()
  return component
}

enableAutoUnmount(afterEach)

describe('Cloud usage on the Ollama Cloud card', () => {
  beforeEach(() => {
    clearNuxtData()
    usageReads = 0
  })

  it('renders on the Ollama Cloud card when it has a key, and on no other card', async () => {
    endpoints(['ollama-cloud', 'openrouter'])
    const component = await mountProviders()
    const blocks = component.findAll('[data-testid="ollama-cloud-usage"]')
    expect(blocks).toHaveLength(1)
    const card = component.findAll('.bg-surface-elevated').find(c => c.text().startsWith('Ollama Cloud'))!
    expect(card.find('[data-testid="ollama-cloud-usage"]').exists()).toBe(true)
    await vi.waitFor(() => expect(card.text()).toContain('Total 3 requests'))
  })

  it('does not render, or read, while Ollama Cloud has no key', async () => {
    endpoints(['openrouter'])
    const component = await mountProviders()
    expect(component.find('[data-testid="ollama-cloud-usage"]').exists()).toBe(false)
    expect(usageReads).toBe(0)
  })
})
