import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * Settings > Decision Providers (JCLAW-1302): JEV's card holds the TypeSafe key both consumers
 * send, the retention note, which consumers have chosen JEV, and the circuit breaker they share.
 * The key editor keeps the JCLAW-1274 behaviours it had in the Browser panel.
 */

let stored: Map<string, string>
let posted: Array<{ key: string, value: string }>
let breakers: Array<Record<string, unknown>>

function jevBreaker(over: Record<string, unknown> = {}) {
  return {
    name: 'decision:jev', subsystem: 'decision', target: 'jev', state: 'CLOSED',
    samples: 4, failures: 0, slowCalls: 0, failureRate: 0, slowCallRate: 0, reason: null, manual: false, ...over,
  }
}

function baseEndpoints(opts: { failSaves?: boolean, holdSaves?: Promise<void> } = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/breakers', () => breakers)
  registerEndpoint('/api/config', () => ({
    entries: [...stored].map(([key, value]) => ({ key, value, updatedAt: '2026-09-26T00:00:00Z' })),
  }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as { key: string, value: string }
      posted.push(body)
      if (opts.holdSaves) await opts.holdSaves
      if (opts.failSaves) {
        setResponseStatus(event, 403)
        return { error: 'invalid_request', message: 'decision.jev.apiKey must be printable ASCII with no spaces.' }
      }
      // The API masks a key on read, as ConfigService.maskValue does.
      stored.set(body.key, body.key.endsWith('apiKey') ? `${body.value.slice(0, 4)}****` : body.value)
      return { status: 'ok' }
    },
  })
}

async function mountDecisionProviders() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'decision-providers'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Decision Providers', () => {
  beforeEach(() => {
    clearNuxtData()
    stored = new Map()
    posted = []
    breakers = []
  })

  it('sits between LLM Providers and Search Providers in the Providers group', () => {
    const ids = sectionGroups.find(g => g.label === 'Providers')!.sections.map(s => s.id)
    expect(ids).toEqual(['providers', 'decision-providers', 'search'])
  })

  it('shows an unkeyed JEV card with the retention note and neither consumer in use', async () => {
    baseEndpoints()
    const component = await mountDecisionProviders()

    expect(component.html()).toMatch(/<h2[^>]*>\s*Decision Providers\s*</)
    expect(component.find('[data-testid="decision-jev-status"]').text()).toBe('needs API key')
    expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('(not set)')
    const note = component.find('[data-testid="decision-jev-retention"]').text()
    expect(note).toContain('record or retain')
    expect(note).toContain('page content')
    expect(note).toContain('first 4000 characters of each prompt')
    const browser = component.find('[data-testid="decision-jev-consumer-browser"]')
    expect(browser.find('a').attributes('href')).toBe('/settings?section=browser')
    expect(browser.text()).toContain('not in use')
    const router = component.find('[data-testid="decision-jev-consumer-model-router"]')
    expect(router.find('a').attributes('href')).toBe('/settings?section=model-router')
    expect(router.text()).toContain('not in use')
    expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(false)
  })

  it('marks each consumer that has chosen JEV as in use', async () => {
    baseEndpoints()
    stored.set('browser.engine', 'jev')
    stored.set('router.classifier.provider', 'jev')
    stored.set('router.classifier.model', 'jev-latest')
    const component = await mountDecisionProviders()

    for (const id of ['browser', 'model-router']) {
      const consumer = component.find(`[data-testid="decision-jev-consumer-${id}"]`).text()
      expect(consumer).toContain('in use')
      expect(consumer).not.toContain('not in use')
    }
  })

  it('counts the router as a JEV consumer only once both halves of the classifier pair are stored', async () => {
    baseEndpoints()
    stored.set('router.classifier.provider', ' jev ')
    const providerOnly = await mountDecisionProviders()
    expect(providerOnly.find('[data-testid="decision-jev-consumer-model-router"]').text()).toContain('not in use')

    stored.set('router.classifier.model', 'jev-latest')
    clearNuxtData()
    const paired = await mountDecisionProviders()
    expect(paired.find('[data-testid="decision-jev-consumer-model-router"]').text()).not.toContain('not in use')
  })

  it('sets the key through the masked editor, which starts blank', async () => {
    baseEndpoints()
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    const input = component.find('input[aria-label="TypeSafe API key"]')
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(input.attributes('type')).toBe('password')
    expect(input.attributes('autocomplete')).toBe('new-password')
    await input.setValue('ts-secret-123')
    await component.find('button[title="Save"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(posted).toEqual([{ key: 'decision.jev.apiKey', value: 'ts-secret-123' }])
    // updateEntry refreshes the config without awaiting it.
    await vi.waitFor(() => expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('••••••••'))
    expect(component.find('[data-testid="decision-jev-status"]').text()).toBe('configured')
  })

  it('saving the key editor untouched cancels rather than storing a blank key', async () => {
    baseEndpoints()
    stored.set('decision.jev.apiKey', 'ts-s****')
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([])
    expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(false)
    expect(component.find('[data-testid="decision-jev-key"]').text()).toBe('••••••••')
  })

  it('the key cannot be saved again while its save is in flight', async () => {
    let release!: () => void
    baseEndpoints({ holdSaves: new Promise<void>((resolve) => {
      release = resolve
    }) })
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('input[aria-label="TypeSafe API key"]').setValue('ts-secret-123')
    await component.find('button[title="Save"]').trigger('click')

    await vi.waitFor(() => expect(component.find('button[title="Save"]').attributes('disabled')).toBeDefined())
    release()
    await vi.waitFor(() => expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(false))
    expect(posted).toHaveLength(1)
  })

  it('a refused key keeps the editor open with the reason beside it', async () => {
    baseEndpoints({ failSaves: true })
    const component = await mountDecisionProviders()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('input[aria-label="TypeSafe API key"]').setValue('ts key')
    await component.find('button[title="Save"]').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="decision-provider-jev"] [data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(true)
  })

  it('shows the shared breaker once JEV has been called, with Isolate', async () => {
    breakers = [jevBreaker()]
    baseEndpoints()
    const component = await mountDecisionProviders()

    await vi.waitFor(() => expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(true))
    const row = component.find('[data-testid="decision-jev-breaker"]')
    expect(row.find('[data-testid="breaker-state"]').text()).toBe('CLOSED')
    expect(row.find('button[aria-label="Isolate jev"]').exists()).toBe(true)
  })

  it('an isolated breaker reads as the operator\'s decision, with Restore', async () => {
    breakers = [jevBreaker({ state: 'OPEN', reason: 'MANUAL_TRIP', manual: true })]
    baseEndpoints()
    const component = await mountDecisionProviders()

    await vi.waitFor(() => expect(component.find('[data-testid="decision-jev-breaker"]').exists()).toBe(true))
    const row = component.find('[data-testid="decision-jev-breaker"]')
    expect(row.text()).toContain('isolated by you')
    expect(row.find('button[aria-label="Restore jev"]').exists()).toBe(true)
  })
})
