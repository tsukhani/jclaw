import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * The Chrome settings panel: pairing, the per-agent site lists, and the two overrides only the main
 * agent has. Every value is an ordinary /api/config row under agent.<name>.chromeBridge.
 */

const HELPER_KEY = 'agent.helper.chromeBridge.allowedSites'

let stored: Map<string, string>
let posted: Array<{ key: string, value: string }>
let deleted: string[]
let paired: boolean
let pairPosts: number
let unpairs: number

function baseEndpoints(opts: { failSaves?: boolean } = {}) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', isMain: true, enabled: true, modelProvider: 'p', modelId: 'm', thinkingMode: null },
    { id: 2, name: 'helper', isMain: false, enabled: true, modelProvider: 'p', modelId: 'm', thinkingMode: null },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => ({
    entries: [...stored].map(([key, value]) => ({ key, value, updatedAt: '2026-11-08T00:00:00Z' })),
  }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as { key: string, value: string }
      posted.push(body)
      if (opts.failSaves) {
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      }
      stored.set(body.key, body.value)
      return { status: 'ok' }
    },
  })
  registerEndpoint(`/api/config/${HELPER_KEY}`, {
    method: 'DELETE',
    handler: () => {
      deleted.push(HELPER_KEY)
      stored.delete(HELPER_KEY)
      return { status: 'ok' }
    },
  })
  registerEndpoint('/api/chrome-bridge', () => ({ paired }))
  registerEndpoint('/api/chrome-bridge/pairing', {
    method: 'POST',
    handler: () => {
      pairPosts++
      paired = true
      return { token: 'jcl_once-only-token' }
    },
  })
  registerEndpoint('/api/chrome-bridge/pairing', {
    method: 'DELETE',
    handler: () => {
      unpairs++
      paired = false
      return { paired: false }
    },
  })
}

async function mountChrome() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'chrome'
  await flushPromises()
  await flushPromises()
  return component
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
function checked(component: any, id: string): boolean {
  return (component.find(id).element as HTMLInputElement).checked
}

describe('Settings page — Chrome', () => {
  beforeEach(() => {
    clearNuxtData()
    stored = new Map()
    posted = []
    deleted = []
    paired = false
    pairPosts = 0
    unpairs = 0
  })

  it('sits right after Browser under Agents & Automation', () => {
    const ids = sectionGroups.find(g => g.label === 'Agents & Automation')!.sections.map(s => s.id)
    expect(ids.indexOf('chrome')).toBe(ids.indexOf('browser') + 1)
  })

  it('starts unpaired, and pairing shows the token once and flips the status', async () => {
    baseEndpoints()
    const component = await mountChrome()
    expect(component.find('[data-testid="chrome-pair-status"]').text()).toBe('Not paired')
    expect(component.find('[data-testid="chrome-token"]').exists()).toBe(false)
    expect(component.find('[data-testid="chrome-unpair-button"]').exists()).toBe(false)

    await component.find('[data-testid="chrome-pair-button"]').trigger('click')
    await flushPromises()

    expect(pairPosts).toBe(1)
    expect(component.find('[data-testid="chrome-token"]').text()).toBe('jcl_once-only-token')
    expect(component.find('[data-testid="chrome-pair-status"]').text()).toBe('Paired')
  })

  it('unpairing sends the DELETE and drops the token from the page', async () => {
    paired = true
    baseEndpoints()
    const component = await mountChrome()
    await component.find('[data-testid="chrome-pair-button"]').trigger('click')
    await flushPromises()
    expect(component.find('[data-testid="chrome-token"]').exists()).toBe(true)

    await component.find('[data-testid="chrome-unpair-button"]').trigger('click')
    await flushPromises()

    expect(unpairs).toBe(1)
    expect(component.find('[data-testid="chrome-token"]').exists()).toBe(false)
    expect(component.find('[data-testid="chrome-pair-status"]').text()).toBe('Not paired')
  })

  it('lists every agent with its stored sites one per line', async () => {
    stored.set(HELPER_KEY, 'example.com *.example.org')
    baseEndpoints()
    const component = await mountChrome()

    const box = component.find('[data-testid="chrome-sites-helper"]').element as HTMLTextAreaElement
    expect(box.value).toBe('example.com\n*.example.org')
    expect((component.find('[data-testid="chrome-sites-main"]').element as HTMLTextAreaElement).value).toBe('')
  })

  it('saves an edited list as one space-separated value under that agent\'s own key', async () => {
    baseEndpoints()
    const component = await mountChrome()
    const save = component.find('[data-testid="chrome-sites-save-helper"]')
    expect(save.attributes('disabled')).toBeDefined()

    await component.find('[data-testid="chrome-sites-helper"]').setValue('example.com\n*.example.org\n')
    expect(save.attributes('disabled')).toBeUndefined()
    await save.trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: HELPER_KEY, value: 'example.com *.example.org' }])
  })

  it('clearing a list deletes the key instead of storing a blank', async () => {
    stored.set(HELPER_KEY, 'example.com')
    baseEndpoints()
    const component = await mountChrome()

    await component.find('[data-testid="chrome-sites-helper"]').setValue('')
    await component.find('[data-testid="chrome-sites-save-helper"]').trigger('click')
    await flushPromises()

    expect(deleted).toEqual([HELPER_KEY])
    expect(posted).toEqual([])
  })

  it('a refused save keeps the draft, shows an alert and leaves Save enabled to retry', async () => {
    baseEndpoints({ failSaves: true })
    const component = await mountChrome()

    await component.find('[data-testid="chrome-sites-helper"]').setValue('https://example.com')
    await component.find('[data-testid="chrome-sites-save-helper"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))

    expect(component.find('[data-testid="api-error"]').text()).toContain('/api/config')
    expect((component.find('[data-testid="chrome-sites-helper"]').element as HTMLTextAreaElement).value)
      .toBe('https://example.com')
    expect(component.find('[data-testid="chrome-sites-save-helper"]').attributes('disabled')).toBeUndefined()
  })

  it('offers the three overrides for the main agent only, all off by default', async () => {
    baseEndpoints()
    const component = await mountChrome()

    expect(component.html()).toContain('Main agent only')
    expect(checked(component, '[data-testid="chrome-allow-all"]')).toBe(false)
    expect(checked(component, '[data-testid="chrome-click-free"]')).toBe(false)
    expect(checked(component, '[data-testid="chrome-allow-script"]')).toBe(false)
    expect(component.findAll('[data-testid="chrome-allow-all"]')).toHaveLength(1)
    expect(component.findAll('[data-testid="chrome-allow-script"]')).toHaveLength(1)
  })

  it('turning an override on saves it under the main agent\'s key', async () => {
    baseEndpoints()
    const component = await mountChrome()

    await component.find('[data-testid="chrome-allow-all"]').setValue(true)
    await vi.waitFor(() =>
      expect(component.find('[data-testid="chrome-click-free"]').attributes('disabled')).toBeUndefined())
    await component.find('[data-testid="chrome-click-free"]').setValue(true)
    await vi.waitFor(() => expect(posted).toHaveLength(2))
    await vi.waitFor(() =>
      expect(component.find('[data-testid="chrome-allow-script"]').attributes('disabled')).toBeUndefined())
    await component.find('[data-testid="chrome-allow-script"]').setValue(true)
    await vi.waitFor(() => expect(posted).toHaveLength(3))

    expect(posted).toEqual([
      { key: 'agent.main.chromeBridge.allowAllSites', value: 'true' },
      { key: 'agent.main.chromeBridge.clickWithoutApproval', value: 'true' },
      { key: 'agent.main.chromeBridge.allowScript', value: 'true' },
    ])
    expect(checked(component, '[data-testid="chrome-allow-all"]')).toBe(true)
  })
})
