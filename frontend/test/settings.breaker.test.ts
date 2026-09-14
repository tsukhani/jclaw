import { describe, it, expect, beforeEach } from 'vitest'
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
