import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsModelRouterPanel from '~/components/settings/SettingsModelRouterPanel.vue'
import { useProvideSettingsConfig } from '~/composables/useSettingsConfig'

/**
 * Settings > Model Router (JCLAW-1222): the per-class model lists are written as the JSON the
 * backend validates, an emptied list is deleted rather than stored empty, thresholds are entered as
 * percentages and stored as fractions, and each listed provider's quota usage is shown.
 */
const Harness = defineComponent({
  setup() {
    useProvideSettingsConfig()
    return () => h(SettingsModelRouterPanel)
  },
})

let entries: { key: string, value: string }[] = []
let posts: { key: string, value: string }[] = []
let deletes: string[] = []

registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries }) })
registerEndpoint('/api/config', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    posts.push(await readBody(event) as { key: string, value: string })
    return { status: 'ok' }
  },
})
registerEndpoint('/api/config/router.chat.models', {
  method: 'DELETE',
  handler: () => {
    deletes.push('router.chat.models')
    return { status: 'ok' }
  },
})
registerEndpoint('/api/providers', () => [])
registerEndpoint('/api/router/status', () => ({
  available: true,
  downshiftAt: 0.75,
  exhaustedAt: 0.95,
  providers: [
    { provider: 'ollama-cloud', prepaid: true, usageSource: true, windows: { session: 0.02, weekly: 0.81 }, fetchedAt: '2026-09-18T00:00:00Z' },
    { provider: 'openrouter', prepaid: false, usageSource: false, windows: {}, fetchedAt: null },
  ],
  unavailable: {},
}))

beforeEach(() => {
  clearNuxtData()
  posts = []
  deletes = []
  entries = [
    { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
    { key: 'provider.ollama-cloud.models', value: JSON.stringify([{ id: 'glm-5.3-flash' }, { id: 'kimi-k3' }]) },
    { key: 'provider.openrouter.baseUrl', value: 'https://openrouter.ai/api/v1' },
    { key: 'provider.openrouter.models', value: JSON.stringify([{ id: 'z-ai/glm-5.3-flash' }]) },
    { key: 'router.chat.models', value: JSON.stringify([{ provider: 'ollama-cloud', model: 'glm-5.3-flash' }]) },
  ]
})

describe('SettingsModelRouterPanel', () => {
  it('lists each class, with classes that have no list falling back to Chat', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    expect(c.find('[data-testid="router-availability"]').text()).toContain('offered')
    expect(c.find('[data-testid="router-class-chat"]').text()).toContain('ollama-cloud / glm-5.3-flash')
    expect(c.find('[data-testid="router-class-reasoning"]').text()).toContain('uses the Chat list')
  })

  it('adds a model by writing the whole ordered list', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    await c.find('[data-testid="router-class-chat"] select').setValue('openrouter::z-ai/glm-5.3-flash')
    await vi.waitFor(() => expect(posts.length).toBe(1))
    expect(posts).toContainEqual({
      key: 'router.chat.models',
      value: JSON.stringify([
        { provider: 'ollama-cloud', model: 'glm-5.3-flash' },
        { provider: 'openrouter', model: 'z-ai/glm-5.3-flash' },
      ]),
    })
  })

  it('deletes the key when its last model is removed', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    await c.find('button[aria-label="Remove ollama-cloud / glm-5.3-flash"]').trigger('click')
    await vi.waitFor(() => expect(deletes).toEqual(['router.chat.models']))
    expect(posts.filter(p => p.key === 'router.chat.models')).toHaveLength(0)
  })

  it('stores a threshold entered as a percentage as a fraction', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const editButtons = c.findAll('button[title="Edit"]')
    await editButtons[0]!.trigger('click')
    await c.find('input[aria-label="Downshift at (percent)"]').setValue('60')
    await c.find('button[title="Save"]').trigger('click')
    await vi.waitFor(() => expect(posts).toContainEqual({ key: 'router.budget.downshiftAt', value: '0.6' }))
  })

  it('shows each listed provider\'s quota windows, and says when a provider has none', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const usage = c.find('[data-testid="router-usage"]')
    expect(usage.text()).toContain('weekly 81%')
    expect(usage.text()).toContain('No usage API')
  })
})
