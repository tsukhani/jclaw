import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/** The llm.breaker.* rows at the foot of Settings > LLM Providers. */

let posted: { key?: string, value?: string }[] = []

async function mountProviders() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'providers'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — circuit breaker tuning', () => {
  beforeEach(() => {
    clearNuxtData()
    posted = []
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/channels', () => [])
    registerEndpoint('/api/providers', () => [])
    registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: [] }) })
    registerEndpoint('/api/config', {
      method: 'POST',
      handler: async (event) => {
        posted.push(await readBody(event) as { key?: string, value?: string })
        return { ok: true }
      },
    })
  })

  it('shows every breaker setting with the default the backend applies', async () => {
    const component = await mountProviders()

    const block = component.find('[data-testid="llm-breaker-settings"]')
    expect(block.findAll('[data-testid^="config-field-llm.breaker."]')).toHaveLength(10)
    expect(block.find('[data-testid="config-field-llm.breaker.wait-seconds"]').text()).toContain('60')
    expect(block.find('[data-testid="config-field-llm.breaker.first-chunk-seconds"]').text()).toContain('600')
  })

  it('saves an edited threshold', async () => {
    const component = await mountProviders()

    const row = component.find('[data-testid="config-field-llm.breaker.consecutive-failures"]')
    await row.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    await row.find('input').setValue('5')
    await row.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: 'llm.breaker.consecutive-failures', value: '5' }])
  })
})

describe('Settings page — a provider card shows its own breaker (JCLAW-1301)', () => {
  beforeEach(() => {
    clearNuxtData()
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/channels', () => [])
    registerEndpoint('/api/providers', () => [])
    registerEndpoint('/api/config', {
      method: 'GET',
      handler: () => ({
        entries: [
          { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1' },
          { key: 'provider.openrouter.baseUrl', value: 'https://openrouter.ai/api/v1' },
        ],
      }),
    })
    registerEndpoint('/api/breakers', () => [
      { name: 'llm:openai', subsystem: 'llm', target: 'openai', state: 'OPEN', samples: 12, failures: 9, slowCalls: 0, reason: 'FAILURE_RATE', manual: false },
      { name: 'mcp:openrouter', subsystem: 'mcp', target: 'openrouter', state: 'OPEN', samples: 0, failures: 0, slowCalls: 0, reason: 'MANUAL_TRIP', manual: true },
    ])
  })

  it('on the card of the provider it guards, and on no other', async () => {
    const component = await mountProviders()

    // The breaker read is lazy, so the card renders before its breaker does.
    await vi.waitFor(() => expect(component.find('[data-testid="provider-breaker-openai"]').exists()).toBe(true))
    const row = component.find('[data-testid="provider-breaker-openai"]')
    expect(row.text()).toContain('OPEN')
    expect(row.text()).toContain('9/12 failed')
    expect(row.find('button[aria-label="Restore openai"]').exists()).toBe(true)
    // openrouter was never called, so it has no llm breaker; an MCP server's breaker of the same name is not its.
    expect(component.find('[data-testid="provider-breaker-openrouter"]').exists()).toBe(false)
  })
})
