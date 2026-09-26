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
// Every write in the order it arrived, where the order is what the backend's validation depends on.
let ops: string[] = []

registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries }) })
registerEndpoint('/api/config', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    const body = await readBody(event) as { key: string, value: string }
    posts.push(body)
    ops.push(`POST ${body.key}=${body.value}`)
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
for (const key of ['router.classifier.provider', 'router.classifier.model']) {
  registerEndpoint(`/api/config/${key}`, {
    method: 'DELETE',
    handler: () => {
      deletes.push(key)
      ops.push(`DELETE ${key}`)
      return { status: 'ok' }
    },
  })
}
registerEndpoint('/api/providers', () => [
  { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100, supportedModalities: ['SUBSCRIPTION'], local: false },
  { name: 'openrouter', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0, supportedModalities: ['PER_TOKEN'], local: false },
])
registerEndpoint('/api/config/router.preferPrepaid', {
  method: 'DELETE',
  handler: () => {
    deletes.push('router.preferPrepaid')
    return { status: 'ok' }
  },
})
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
  ops = []
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
    await vi.waitFor(() => expect(posts).toHaveLength(1), { timeout: 5000 })
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
    await vi.waitFor(() => expect(deletes).toEqual(['router.chat.models']), { timeout: 5000 })
    expect(posts.filter(p => p.key === 'router.chat.models')).toHaveLength(0)
  })

  it('stores a threshold entered as a percentage as a fraction', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const editButtons = c.findAll('button[title="Edit"]')
    await editButtons[0]!.trigger('click')
    await c.find('input[aria-label="Downshift at (percent)"]').setValue('60')
    await c.find('button[title="Save"]').trigger('click')
    await vi.waitFor(() => expect(posts).toContainEqual({ key: 'router.budget.downshiftAt', value: '0.6' }), { timeout: 5000 })
  })

  it('prefers prepaid by default and marks the per-token rows it will pass over', async () => {
    // The operator's own ranking: a per-token model first, a prepaid one second.
    entries = entries.map(e => e.key === 'router.chat.models'
      ? {
          key: e.key,
          value: JSON.stringify([
            { provider: 'openrouter', model: 'z-ai/glm-5.3-flash' },
            { provider: 'ollama-cloud', model: 'glm-5.3-flash' },
          ]),
        }
      : e)
    const c = await mountSuspended(Harness)
    await flushPromises()
    const box = c.find('input#router-prefer-prepaid')
    expect((box.element as HTMLInputElement).checked).toBe(true)

    // The operator ranked a per-token model first; the row says the router will not honour that.
    const chat = c.find('[data-testid="router-class-chat"]')
    expect(chat.text()).toContain('fallback only')

    await box.setValue(false)
    await vi.waitFor(() => expect(posts).toContainEqual({ key: 'router.preferPrepaid', value: 'false' }), { timeout: 5000 })
    await flushPromises()
  })

  it('drops the marking and clears the key when the operator turns the preference off', async () => {
    entries = [...entries, { key: 'router.preferPrepaid', value: 'false' }]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const box = c.find('input#router-prefer-prepaid')
    expect((box.element as HTMLInputElement).checked).toBe(false)
    expect(c.find('[data-testid="router-class-chat"]').text()).not.toContain('fallback only')

    await box.setValue(true)
    await vi.waitFor(() => expect(deletes).toContain('router.preferPrepaid'), { timeout: 5000 })
    await flushPromises()
  })

  it('defaults the classifier to the keyword rules and writes both keys when a model is picked', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    expect((select.element as HTMLSelectElement).value).toBe('')
    expect(select.text()).toContain('Keyword rules (no model call)')

    await select.setValue('ollama-cloud::glm-5.3-flash')
    await vi.waitFor(() => expect(posts).toHaveLength(2), { timeout: 5000 })
    expect(posts).toContainEqual({ key: 'router.classifier.provider', value: 'ollama-cloud' })
    expect(posts).toContainEqual({ key: 'router.classifier.model', value: 'glm-5.3-flash' })
  })

  it('clears both classifier keys when the operator goes back to the keyword rules', async () => {
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'ollama-cloud' },
      { key: 'router.classifier.model', value: 'glm-5.3-flash' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    expect((select.element as HTMLSelectElement).value).toBe('ollama-cloud::glm-5.3-flash')

    await select.setValue('')
    await vi.waitFor(() => expect(deletes).toEqual(['router.classifier.provider', 'router.classifier.model']), { timeout: 5000 })
  })

  it('offers JEV only once a TypeSafe key is set, and points to where it is set', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const jev = c.find('select[aria-label="Prompt classifier model"] option[value="jev::jev-latest"]')
    expect(jev.text()).toContain('JEV (TypeSafe AI)')
    expect((jev.element as HTMLOptionElement).disabled).toBe(true)
    const hint = c.find('[data-testid="router-jev-key-hint"]')
    expect(hint.text()).toContain('Settings → Browser')
    expect(hint.find('a').attributes('href')).toBe('/settings?section=browser')

    entries = [...entries, { key: 'browser.jev.apiKey', value: 'ts-s****' }]
    clearNuxtData()
    const keyed = await mountSuspended(Harness)
    await flushPromises()
    const enabled = keyed.find('select[aria-label="Prompt classifier model"] option[value="jev::jev-latest"]')
    expect((enabled.element as HTMLOptionElement).disabled).toBe(false)
    expect(keyed.find('[data-testid="router-jev-key-hint"]').exists()).toBe(false)
  })

  it('picking JEV clears the stored model before writing the pair', async () => {
    entries = [
      ...entries,
      { key: 'browser.jev.apiKey', value: 'ts-s****' },
      { key: 'router.classifier.provider', value: 'ollama-cloud' },
      { key: 'router.classifier.model', value: 'glm-5.3-flash' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    await c.find('select[aria-label="Prompt classifier model"]').setValue('jev::jev-latest')
    await vi.waitFor(() => expect(ops).toHaveLength(3), { timeout: 5000 })
    expect(ops).toEqual([
      'DELETE router.classifier.model',
      'POST router.classifier.provider=jev',
      'POST router.classifier.model=jev-latest',
    ])
  })

  it('leaving JEV for a model clears jev-latest first, so the provider write is not refused', async () => {
    entries = [
      ...entries,
      { key: 'browser.jev.apiKey', value: 'ts-s****' },
      { key: 'router.classifier.provider', value: 'jev' },
      { key: 'router.classifier.model', value: 'jev-latest' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    expect((select.element as HTMLSelectElement).value).toBe('jev::jev-latest')

    await select.setValue('ollama-cloud::glm-5.3-flash')
    await vi.waitFor(() => expect(ops).toHaveLength(3), { timeout: 5000 })
    expect(ops).toEqual([
      'DELETE router.classifier.model',
      'POST router.classifier.provider=ollama-cloud',
      'POST router.classifier.model=glm-5.3-flash',
    ])
  })

  it('shows each listed provider\'s quota windows, and says when a provider has none', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const usage = c.find('[data-testid="router-usage"]')
    expect(usage.text()).toContain('weekly 81%')
    expect(usage.text()).toContain('No usage API')
  })
})
