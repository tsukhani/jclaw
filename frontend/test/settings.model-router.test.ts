import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsModelRouterPanel from '~/components/settings/SettingsModelRouterPanel.vue'
import { sectionGroups } from '~/components/settings/sections'
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
let ollama: Record<string, unknown>
// While set, GET /api/decision/ollama answers only once it settles.
let ollamaRead: Promise<void> | null = null
let ollamaReadFails = false
let laya: Record<string, unknown>
// A POST this answers true for is refused with 403, as ConfigService refuses a write, and stores nothing.
let refused: (body: { key: string, value: string }) => boolean = () => false

registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries }) })
registerEndpoint('/api/config', {
  method: 'POST',
  handler: async (event) => {
    const { readBody, setResponseStatus } = await import('h3')
    const body = await readBody(event) as { key: string, value: string }
    posts.push(body)
    ops.push(`POST ${body.key}=${body.value}`)
    if (refused(body)) {
      setResponseStatus(event, 403)
      return { type: 'error', code: 'forbidden', message: `${body.key} refused` }
    }
    entries = [...entries.filter(e => e.key !== body.key), body]
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
      entries = entries.filter(e => e.key !== key)
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
registerEndpoint('/api/decision/ollama', async (event) => {
  if (ollamaRead) await ollamaRead
  if (ollamaReadFails) {
    const { setResponseStatus } = await import('h3')
    setResponseStatus(event, 500)
    return { type: 'error', code: 'internal', message: 'boom' }
  }
  return ollama
})
registerEndpoint('/api/decision/laya', () => laya)
registerEndpoint('/api/router/status', () => ({
  available: true,
  downshiftAt: 0.75,
  exhaustedAt: 0.95,
  providers: [
    { provider: 'ollama-cloud', prepaid: true, usageSource: true, windows: { session: 0.02, weekly: 0.81 }, fetchedAt: '2026-09-18T00:00:00Z' },
    { provider: 'openrouter', prepaid: false, usageSource: false, windows: {}, fetchedAt: null },
    // JCLAW-1449: ollama.com answered, but with request counts and no quota windows.
    { provider: 'ollama-cloud-b', prepaid: true, usageSource: true, windows: {}, fetchedAt: '2026-10-15T13:17:00Z' },
  ],
  unavailable: {},
}))

beforeEach(() => {
  clearNuxtData()
  posts = []
  deletes = []
  ops = []
  ollamaRead = null
  ollamaReadFails = false
  ollama = { baseUrl: 'http://localhost:11434', customized: false, reachable: true, error: null, models: [] }
  laya = { baseUrl: 'http://localhost:8000', reachable: true, error: null, loaded: [], device: 'cpu' }
  refused = () => false
  entries = [
    { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
    { key: 'provider.ollama-cloud.models', value: JSON.stringify([{ id: 'glm-5.3-flash' }, { id: 'kimi-k3' }]) },
    { key: 'provider.openrouter.baseUrl', value: 'https://openrouter.ai/api/v1' },
    { key: 'provider.openrouter.models', value: JSON.stringify([{ id: 'z-ai/glm-5.3-flash' }]) },
    { key: 'router.chat.models', value: JSON.stringify([{ provider: 'ollama-cloud', model: 'glm-5.3-flash' }]) },
  ]
})

/** An Ollama decision model as GET /api/decision/ollama lists it, stating nothing else. */
function ollamaModel(name: string) {
  return { name, contextTokens: null, parameterSize: null, sizeBytes: null }
}

describe('SettingsModelRouterPanel', () => {
  it('sits after Chat under Agents & Automation', () => {
    const ids = sectionGroups.find(g => g.label === 'Agents & Automation')!.sections.map(s => s.id)
    expect(ids.indexOf('model-router')).toBe(ids.indexOf('chat') + 1)
  })

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
    expect(hint.text()).toContain('Settings → Decision Providers')
    expect(hint.find('a').attributes('href')).toBe('/settings?section=decision-providers')

    entries = [...entries, { key: 'decision.jev.apiKey', value: 'ts-s****' }]
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
      { key: 'decision.jev.apiKey', value: 'ts-s****' },
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
      { key: 'decision.jev.apiKey', value: 'ts-s****' },
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

  it('groups every decision model under its provider, in order, each label naming the provider, with nothing ticked', async () => {
    ollama = { ...ollama, models: [ollamaModel('tev1:latest'), ollamaModel('clef:cloud')] }
    entries = [...entries, { key: 'decision.jev.apiKey', value: 'ts-s****' }, { key: 'decision.baseten.apiKey', value: 'bt-s****' }]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    await vi.waitFor(() => expect(select.findAll('optgroup')).toHaveLength(5))
    // The decision groups run in the Decision Providers cards' order, and the LLM providers' models follow in their own.
    expect(select.findAll('optgroup').map(g => [g.attributes('label'), g.findAll('option').map(o => o.text())])).toEqual([
      ['JEV (TypeSafe AI)', ['JEV (TypeSafe AI)']],
      ['Laya', ['Laya / english (512 tokens)', 'Laya / multilingual (1,024 tokens)', 'Laya / typed-decisions (1,024 tokens)']],
      ['Ollama', ['Ollama decision / tev1:latest', 'Ollama decision / clef:cloud']],
      ['Baseten', ['Baseten / elephant-1 (16,384 tokens)', 'Baseten / mammoth-1 (16,384 tokens)']],
      ['Language models', ['ollama-cloud / glm-5.3-flash', 'ollama-cloud / kimi-k3', 'openrouter / z-ai/glm-5.3-flash']],
    ])
    expect(select.findAll('optgroup option').map(o => (o.element as HTMLOptionElement).disabled)).toEqual(Array(11).fill(false))
    expect(select.findAll(':scope > option').map(o => o.text())).toEqual(['Keyword rules (no model call)'])
  })

  it('offers each decision model the Ollama server lists, and writes the pair', async () => {
    ollama = { ...ollama, models: [ollamaModel('tev1:latest'), ollamaModel('nimble:latest')] }
    const c = await mountSuspended(Harness)
    await flushPromises()
    await vi.waitFor(() => expect(c.findAll('[data-testid="router-ollama-decision-option"]')).toHaveLength(2))
    expect(c.findAll('[data-testid="router-ollama-decision-option"]').map(o => [o.attributes('value'), o.text()])).toEqual([
      ['ollama-decision::tev1:latest', 'Ollama decision / tev1:latest'],
      ['ollama-decision::nimble:latest', 'Ollama decision / nimble:latest'],
    ])

    await c.find('select[aria-label="Prompt classifier model"]').setValue('ollama-decision::tev1:latest')
    await vi.waitFor(() => expect(ops).toHaveLength(3), { timeout: 5000 })
    expect(ops).toEqual([
      'DELETE router.classifier.model',
      'POST router.classifier.provider=ollama-decision',
      'POST router.classifier.model=tev1:latest',
    ])
  })

  it('an Ollama server that lists no decision model adds no Ollama group', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    await vi.waitFor(() => expect(c.findAll('optgroup')).toHaveLength(4))
    expect(c.findAll('optgroup').map(g => g.attributes('label'))).toEqual(['JEV (TypeSafe AI)', 'Laya', 'Baseten', 'Language models'])
  })

  it('an unreachable Ollama server is one disabled option', async () => {
    ollama = { baseUrl: 'http://192.168.1.20:11434', customized: true, reachable: false, error: 'not reachable (ConnectException)', models: [] }
    const c = await mountSuspended(Harness)
    await flushPromises()
    const group = () => c.find('optgroup[label="Ollama"]')
    await vi.waitFor(() => expect(group().exists()).toBe(true))
    const options = group().findAll('option')
    expect(options.map(o => [o.text(), (o.element as HTMLOptionElement).disabled])).toEqual([
      ['Ollama decision — server not reachable', true],
    ])
    expect(c.findAll('[data-testid="router-ollama-decision-option"]')).toHaveLength(0)
  })

  it('an Ollama read JClaw could not answer reads as an unreachable server, not as no group', async () => {
    ollamaReadFails = true
    const c = await mountSuspended(Harness)
    await flushPromises()
    const group = () => c.find('optgroup[label="Ollama"]')
    await vi.waitFor(() => expect(group().exists()).toBe(true))
    expect(group().findAll('option').map(o => [o.text(), (o.element as HTMLOptionElement).disabled])).toEqual([
      ['Ollama decision — server not reachable', true],
    ])
  })

  it('an unreachable Laya server disables its checkpoints, and a stored Laya classifier stays selected', async () => {
    laya = { baseUrl: 'http://localhost:8000', reachable: false, error: 'not reachable (ConnectException)', loaded: [], device: null }
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'laya' },
      { key: 'router.classifier.model', value: 'english' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const options = () => c.findAll('[data-testid="router-laya-option"]').map(o => [o.text(), (o.element as HTMLOptionElement).disabled])
    await vi.waitFor(() => expect(options()).toEqual([
      ['Laya / english (512 tokens) — server not reachable', true],
      ['Laya / multilingual (1,024 tokens) — server not reachable', true],
      ['Laya / typed-decisions (1,024 tokens) — server not reachable', true],
    ]))
    expect((c.find('select[aria-label="Prompt classifier model"]').element as HTMLSelectElement).value).toBe('laya::english')
  })

  it('a refused model write puts the previous classifier back and keeps the refusal on the panel', async () => {
    ollama = { ...ollama, models: [ollamaModel('tev1:latest')] }
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'laya' },
      { key: 'router.classifier.model', value: 'english' },
    ]
    refused = body => body.key === 'router.classifier.model' && body.value === 'tev1:latest'
    const c = await mountSuspended(Harness)
    await flushPromises()
    await vi.waitFor(() => expect(c.findAll('[data-testid="router-ollama-decision-option"]')).toHaveLength(1))

    await c.find('select[aria-label="Prompt classifier model"]').setValue('ollama-decision::tev1:latest')
    await vi.waitFor(() => expect(ops).toHaveLength(5), { timeout: 5000 })
    expect(ops).toEqual([
      'DELETE router.classifier.model',
      'POST router.classifier.provider=ollama-decision',
      'POST router.classifier.model=tev1:latest',
      'POST router.classifier.provider=laya',
      'POST router.classifier.model=english',
    ])
    expect(entries.filter(e => e.key.startsWith('router.classifier.'))).toEqual([
      { key: 'router.classifier.provider', value: 'laya' },
      { key: 'router.classifier.model', value: 'english' },
    ])
    await vi.waitFor(() => expect(c.find('[data-testid="api-error"]').text()).toContain('router.classifier.model refused'))
    expect((c.find('select[aria-label="Prompt classifier model"]').element as HTMLSelectElement).value).toBe('laya::english')
  })

  it('a stored Ollama classifier the server no longer lists shows as itself, not available', async () => {
    ollama = { ...ollama, models: [ollamaModel('tev1:latest')] }
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'ollama-decision' },
      { key: 'router.classifier.model', value: 'tev1:0.8b' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    const stranded = () => select.find('option[value="ollama-decision::tev1:0.8b"]')
    await vi.waitFor(() => expect(stranded().text()).toBe('Ollama decision / tev1:0.8b — not available'))
    expect((stranded().element as HTMLOptionElement).disabled).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('ollama-decision::tev1:0.8b')
    expect(c.find('optgroup[label="Ollama"]').findAll('option').map(o => o.text()))
      .toEqual(['Ollama decision / tev1:latest', 'Ollama decision / tev1:0.8b — not available'])
  })

  it('while the Ollama read is pending, a stored Ollama classifier stays selected and is not called unavailable', async () => {
    let answer!: () => void
    ollamaRead = new Promise<void>((resolve) => {
      answer = resolve
    })
    ollama = { ...ollama, models: [ollamaModel('nimble:latest'), ollamaModel('tev1:latest')] }
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'ollama-decision' },
      { key: 'router.classifier.model', value: 'tev1:latest' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    const options = () => c.findAll('[data-testid="router-ollama-decision-option"]').map(o => o.text())
    expect(options()).toEqual(['Ollama decision / tev1:latest'])
    expect((select.element as HTMLSelectElement).value).toBe('ollama-decision::tev1:latest')

    answer()
    await vi.waitFor(() => expect(options()).toEqual(['Ollama decision / nimble:latest', 'Ollama decision / tev1:latest']))
    expect((select.element as HTMLSelectElement).value).toBe('ollama-decision::tev1:latest')
    expect(c.text()).not.toContain('not available')
  })

  it('a stored classifier on a model no provider registers shows as itself, not available', async () => {
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'openrouter' },
      { key: 'router.classifier.model', value: 'z-ai/glm-4.5' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const select = c.find('select[aria-label="Prompt classifier model"]')
    const stranded = c.find('[data-testid="router-stranded-classifier-option"]')
    expect(stranded.text()).toBe('openrouter / z-ai/glm-4.5 — not available')
    expect((stranded.element as HTMLOptionElement).disabled).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('openrouter::z-ai/glm-4.5')
  })

  it('names no classifier as not available once it is one the lists hold', async () => {
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'laya' },
      { key: 'router.classifier.model', value: 'typed-decisions' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    expect((c.find('select[aria-label="Prompt classifier model"]').element as HTMLSelectElement).value).toBe('laya::typed-decisions')
    expect(c.find('[data-testid="router-stranded-classifier-option"]').exists()).toBe(false)
    expect(c.text()).not.toContain('not available')
  })

  it('offers every Laya checkpoint with its context, and writes the pair', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const options = c.findAll('[data-testid="router-laya-option"]')
    expect(options.map(o => [o.attributes('value'), o.text()])).toEqual([
      ['laya::english', 'Laya / english (512 tokens)'],
      ['laya::multilingual', 'Laya / multilingual (1,024 tokens)'],
      ['laya::typed-decisions', 'Laya / typed-decisions (1,024 tokens)'],
    ])
    expect(c.find('[data-testid="router-laya-limits"]').exists()).toBe(false)

    await c.find('select[aria-label="Prompt classifier model"]').setValue('laya::english')
    await vi.waitFor(() => expect(ops).toHaveLength(3), { timeout: 5000 })
    expect(ops).toEqual([
      'DELETE router.classifier.model',
      'POST router.classifier.provider=laya',
      'POST router.classifier.model=english',
    ])
  })

  it('states the chosen Laya checkpoint\'s context and limits, each fact with its source', async () => {
    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'laya' },
      { key: 'router.classifier.model', value: 'english' },
    ]
    const english = await mountSuspended(Harness)
    await flushPromises()
    expect((english.find('select[aria-label="Prompt classifier model"]').element as HTMLSelectElement).value).toBe('laya::english')
    const hint = english.find('[data-testid="router-laya-limits"]')
    const text = hint.text().replace(/\s+/g, ' ')
    expect(text).toContain('Laya\'s english checkpoint reads 512 tokens of each question; a longer prompt is cut, not refused (Laya\'s model card).')
    expect(hint.findAll('li').map(li => li.text().split('.')[0])).toEqual(['Context', 'Accuracy', 'Confidence', 'Language'])
    expect(text).toContain('english read 343 of the 619 tokens in a 3,100-character prompt, and the 1,024-token checkpoints read all of it. '
      + '(measured 2026-10-13 on one written test prompt)')
    expect(text).toContain('english chose the right class for 33 of 40 prompts, the same as the keyword rules; typed-decisions for 31; '
      + 'multilingual for 23, wrong at probabilities up to 0.96. (measured 2026-10-13 on 40 written test prompts, not live traffic)')
    expect(text).toContain('At a minimum confidence of 0.50, multilingual answered 34 of the 40 prompts and was right on 20 of them.')
    expect(text).toContain('(Laya\'s model card)')
    // The limits of running the server are in the user guide, where the hint links.
    expect(text).not.toContain('LAYA_')
    for (const li of hint.findAll('li')) expect(li.text().trim()).toMatch(/\)$/)
    expect(hint.find('a').attributes('href')).toBe('/guide#settings-laya-limits')

    entries = entries.map(e => e.key === 'router.classifier.model' ? { key: e.key, value: 'multilingual' } : e)
    clearNuxtData()
    const multilingual = await mountSuspended(Harness)
    await flushPromises()
    expect(multilingual.find('[data-testid="router-laya-limits"]').text().replace(/\s+/g, ' '))
      .toContain('Laya\'s multilingual checkpoint reads 1,024 tokens of each question;')
  })

  it('shows no Laya limits while another classifier is chosen', async () => {
    entries = [
      ...entries,
      { key: 'decision.jev.apiKey', value: 'ts-s****' },
      { key: 'router.classifier.provider', value: 'jev' },
      { key: 'router.classifier.model', value: 'jev-latest' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    expect((c.find('select[aria-label="Prompt classifier model"]').element as HTMLSelectElement).value).toBe('jev::jev-latest')
    expect(c.find('[data-testid="router-laya-limits"]').exists()).toBe(false)
  })

  it('offers both Baseten models with their context, disabled with a hint until a key is set', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const options = c.findAll('[data-testid="router-baseten-option"]')
    expect(options.map(o => [o.attributes('value'), o.text(), (o.element as HTMLOptionElement).disabled])).toEqual([
      ['baseten-decision::decision-models/baseten-elephant-9b-latest', 'Baseten / elephant-1 (16,384 tokens) — needs a Baseten API key', true],
      ['baseten-decision::decision-models/baseten-mammoth-27b-latest', 'Baseten / mammoth-1 (16,384 tokens) — needs a Baseten API key', true],
    ])
    const hint = c.find('[data-testid="router-baseten-key-hint"]')
    expect(hint.text().replace(/\s+/g, ' ')).toBe('To classify with Baseten, set a Baseten API key in Settings → Decision Providers.')
    expect(hint.find('a').attributes('href')).toBe('/settings?section=decision-providers')

    entries = [...entries, { key: 'decision.baseten.apiKey', value: 'bt-s****' }]
    clearNuxtData()
    const keyed = await mountSuspended(Harness)
    await flushPromises()
    const enabled = keyed.findAll('[data-testid="router-baseten-option"]')
    expect(enabled.map(o => [o.text(), (o.element as HTMLOptionElement).disabled])).toEqual([
      ['Baseten / elephant-1 (16,384 tokens)', false],
      ['Baseten / mammoth-1 (16,384 tokens)', false],
    ])
    expect(keyed.find('[data-testid="router-baseten-key-hint"]').exists()).toBe(false)
  })

  it('picking a Baseten model writes the pair with the model\'s ID', async () => {
    entries = [...entries, { key: 'decision.baseten.apiKey', value: 'bt-s****' }]
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('select[aria-label="Prompt classifier model"]').setValue('baseten-decision::decision-models/baseten-mammoth-27b-latest')
    await vi.waitFor(() => expect(ops).toHaveLength(3), { timeout: 5000 })
    expect(ops).toEqual([
      'DELETE router.classifier.model',
      'POST router.classifier.provider=baseten-decision',
      'POST router.classifier.model=decision-models/baseten-mammoth-27b-latest',
    ])
  })

  it('says a Baseten model sends the prompt to Baseten, and an Ollama cloud model to ollama.com', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const text = c.text().replace(/\s+/g, ' ')
    expect(text).toContain(
      'JEV sends them to TypeSafe AI, which may record or retain them, and a Baseten model sends them to Baseten.')
    expect(text).toContain('An Ollama cloud model, such as clef:cloud, sends them to ollama.com: read Ollama\'s cloud models '
      + 'in the user guide before you choose one.')
    expect(c.find('[data-testid="router-ollama-cloud-link"]').attributes('href')).toBe('/guide#settings-ollama-cloud')
  })

  it('shows the classifier timeout only once a classifier is picked, at the default until one is stored', async () => {
    const rules = await mountSuspended(Harness)
    await flushPromises()
    expect(rules.find('[data-testid="router-classifier-timeout"]').exists()).toBe(false)

    entries = [
      ...entries,
      { key: 'router.classifier.provider', value: 'ollama-cloud' },
      { key: 'router.classifier.model', value: 'glm-5.3-flash' },
    ]
    clearNuxtData()
    const picked = await mountSuspended(Harness)
    await flushPromises()
    expect(picked.find('[data-testid="router-classifier-timeout"]').text()).toContain('8 s')

    entries = [...entries, { key: 'router.classifier.timeoutSeconds', value: '3' }]
    clearNuxtData()
    const stored = await mountSuspended(Harness)
    await flushPromises()
    expect(stored.find('[data-testid="router-classifier-timeout"]').text()).toContain('3 s')
  })

  it('saves the classifier timeout in seconds', async () => {
    entries = [
      ...entries,
      { key: 'decision.jev.apiKey', value: 'ts-s****' },
      { key: 'router.classifier.provider', value: 'jev' },
      { key: 'router.classifier.model', value: 'jev-latest' },
    ]
    const c = await mountSuspended(Harness)
    await flushPromises()
    const row = c.find('[data-testid="router-classifier-timeout"]')
    await row.find('button[aria-label="Edit classifier timeout"]').trigger('click')
    await c.find('input[aria-label="Classifier timeout (seconds)"]').setValue('3')
    await c.find('[data-testid="router-classifier-timeout"] button[title="Save"]').trigger('click')
    await vi.waitFor(() => expect(posts).toContainEqual({ key: 'router.classifier.timeoutSeconds', value: '3' }), { timeout: 5000 })
  })

  it('shows each listed provider\'s quota windows, and says when a provider has none', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    const usage = c.find('[data-testid="router-usage"]')
    expect(usage.text()).toContain('weekly 81%')
    expect(usage.text()).toContain('No usage API')
    expect(usage.text()).toContain('No quota windows reported: benched only after an out-of-credit reply')
    expect(usage.text()).not.toContain('Usage not read yet')
  })
})
