import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * The Browser settings panel (JCLAW-1274): the engine radios, the TypeSafe warning that only Jev
 * shows, the key field shown whatever the engine because the router's JEV classifier uses it too
 * (JCLAW-1300), and that every write is an ordinary /api/config row — switching back to
 * Playwright keeps the stored key rather than deleting it.
 */

let stored: Map<string, string>
let posted: Array<{ key: string, value: string }>
let deleted: string[]
let reads: number
// /api/browser/setup answers, one per GET (the last repeats), and the POSTs it received.
let setupScript: Array<Record<string, unknown>>
let setupGets: number
let setupPosts: number

function setupStatus(o: Record<string, unknown> = {}) {
  return {
    active: false, step: null, percent: null, error: null,
    driverSource: 'bundled', platform: 'mac-arm64', nodeVersion: '24.21.0', chromiumInstalled: true, ...o,
  }
}

function baseEndpoints(opts: { failSaves?: boolean, holdSaves?: Promise<void> } = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => {
    reads++
    return {
      entries: [...stored].map(([key, value]) => ({ key, value, updatedAt: '2026-09-22T00:00:00Z' })),
    }
  })
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as { key: string, value: string }
      posted.push(body)
      if (opts.holdSaves) await opts.holdSaves
      if (opts.failSaves) {
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      }
      // The API masks a key on read, as ConfigService.maskValue does.
      stored.set(body.key, body.key.endsWith('apiKey') ? `${body.value.slice(0, 4)}****` : body.value)
      return { status: 'ok' }
    },
  })
  registerEndpoint('/api/browser/setup', () => setupScript[Math.min(setupGets++, setupScript.length - 1)])
  registerEndpoint('/api/browser/setup', {
    method: 'POST',
    handler: () => {
      setupPosts++
      return setupStatus({ active: true, driverSource: 'missing', chromiumInstalled: false })
    },
  })
  registerEndpoint('/api/config/browser.jev.apiKey', {
    method: 'DELETE',
    handler: () => {
      deleted.push('browser.jev.apiKey')
      return { status: 'ok' }
    },
  })
}

async function mountBrowser() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'browser'
  await flushPromises()
  await flushPromises()
  return component
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
function checked(component: any, id: string): boolean {
  return (component.find(id).element as HTMLInputElement).checked
}

describe('Settings page — Browser', () => {
  beforeEach(() => {
    clearNuxtData()
    stored = new Map()
    posted = []
    deleted = []
    reads = 0
    setupScript = [setupStatus()]
    setupGets = 0
    setupPosts = 0
  })

  it('sits after Web Scraping under Agents & Automation', () => {
    const ids = sectionGroups.find(g => g.label === 'Agents & Automation')!.sections.map(s => s.id)
    expect(ids.indexOf('browser')).toBe(ids.indexOf('web-scraping') + 1)
  })

  it('defaults to Playwright, with no warning but the key field the router also uses', async () => {
    baseEndpoints()
    const component = await mountBrowser()

    expect(component.html()).toMatch(/<h2[^>]*>\s*Browser\s*</)
    expect(checked(component, '#browser-engine-playwright')).toBe(true)
    expect(component.find('[data-testid="browser-jev-warning"]').exists()).toBe(false)
    expect(component.find('[data-testid="browser-jev-key"]').text()).toBe('(not set)')
    const use = component.find('[data-testid="browser-jev-key-use"]').text()
    expect(use).toContain('Jev engine')
    expect(use).toContain('JEV classifier')
    expect(use).not.toContain('Until a key is set')
  })

  it('selecting Jev saves the engine and reveals the warning and an unset key', async () => {
    baseEndpoints()
    const component = await mountBrowser()

    await component.find('#browser-engine-jev').setValue(true)
    await flushPromises()

    expect(posted).toEqual([{ key: 'browser.engine', value: 'jev' }])
    const warning = component.find('[data-testid="browser-jev-warning"]')
    expect(warning.exists()).toBe(true)
    expect(warning.text()).toContain('TypeSafe AI')
    expect(warning.text()).toContain('address and title')
    expect(warning.text()).toContain('not hidden password fields')
    expect(warning.text()).toContain('text typed earlier in the run')
    expect(warning.text()).toContain('record or retain')
    expect(component.find('[data-testid="browser-jev-key"]').text()).toBe('(not set)')
    expect(component.find('[data-testid="browser-jev-key-use"]').text()).toContain('Until a key is set')
  })

  it('sets the key through the masked editor, which starts blank', async () => {
    baseEndpoints()
    stored.set('browser.engine', 'jev')
    const component = await mountBrowser()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    const input = component.find('input[aria-label="TypeSafe API key"]')
    expect((input.element as HTMLInputElement).value).toBe('')
    expect(input.attributes('autocomplete')).toBe('new-password')
    await input.setValue('ts-secret-123')
    await component.find('button[title="Save"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(posted).toEqual([{ key: 'browser.jev.apiKey', value: 'ts-secret-123' }])
    // updateEntry refreshes the config without awaiting it.
    await vi.waitFor(() => expect(component.find('[data-testid="browser-jev-key"]').text()).toBe('••••••••'))
  })

  it('switching back to Playwright hides the warning but keeps the key field and the stored key', async () => {
    baseEndpoints()
    stored.set('browser.engine', 'jev')
    stored.set('browser.jev.apiKey', 'ts-s****')
    const component = await mountBrowser()
    expect(component.find('[data-testid="browser-jev-key"]').text()).toBe('••••••••')

    await component.find('#browser-engine-playwright').setValue(true)
    await flushPromises()

    expect(posted).toEqual([{ key: 'browser.engine', value: 'playwright' }])
    expect(deleted).toEqual([])
    expect(stored.get('browser.jev.apiKey')).toBe('ts-s****')
    expect(component.find('[data-testid="browser-jev-warning"]').exists()).toBe(false)
    expect(component.find('[data-testid="browser-jev-key"]').text()).toBe('••••••••')
  })

  it('a failed save puts the saved engine back, names the request and re-reads the settings', async () => {
    baseEndpoints({ failSaves: true })
    const component = await mountBrowser()
    const readsBefore = reads

    await component.find('#browser-engine-jev').setValue(true)
    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))

    expect(component.find('[data-testid="api-error"]').text()).toContain('/api/config')
    expect(checked(component, '#browser-engine-playwright')).toBe(true)
    expect(checked(component, '#browser-engine-jev')).toBe(false)
    expect(component.find('[data-testid="browser-jev-warning"]').exists()).toBe(false)
    await vi.waitFor(() => expect(reads).toBeGreaterThan(readsBefore))
  })

  it('saving the key editor untouched cancels rather than storing a blank key', async () => {
    baseEndpoints()
    stored.set('browser.engine', 'jev')
    stored.set('browser.jev.apiKey', 'ts-s****')
    const component = await mountBrowser()

    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([])
    expect(component.find('input[aria-label="TypeSafe API key"]').exists()).toBe(false)
    expect(component.find('[data-testid="browser-jev-key"]').text()).toBe('••••••••')
  })

  it('the key cannot be saved while the engine save is in flight', async () => {
    let release!: () => void
    baseEndpoints({ holdSaves: new Promise<void>((resolve) => {
      release = resolve
    }) })
    const component = await mountBrowser()

    await component.find('#browser-engine-jev').setValue(true)
    await component.find('button[aria-label="Edit TypeSafe API key"]').trigger('click')
    await component.find('input[aria-label="TypeSafe API key"]').setValue('ts-secret-123')

    expect(component.find('button[title="Save"]').attributes('disabled')).toBeDefined()
    release()
    await vi.waitFor(() => expect(component.find('button[title="Save"]').attributes('disabled')).toBeUndefined())
  })

  describe('browser components', () => {
    it('reports what this install already has, with nothing to download', async () => {
      baseEndpoints()
      const component = await mountBrowser()
      expect(component.find('[data-testid="browser-driver-state"]').text()).toBe('Included with this install')
      expect(component.find('[data-testid="browser-chromium-state"]').text()).toBe('Installed')
      expect(component.find('[data-testid="browser-setup-download"]').exists()).toBe(false)
    })

    it('downloads now on request, shows its progress, then what it installed', async () => {
      setupScript = [
        setupStatus({ driverSource: 'missing', chromiumInstalled: false }),
        setupStatus({ active: true, driverSource: 'missing', chromiumInstalled: false,
          step: 'Downloading the browser driver (Node.js 24.21.0)', percent: 55 }),
        setupStatus({ driverSource: 'downloaded', chromiumInstalled: true }),
      ]
      baseEndpoints()
      const component = await mountBrowser()
      expect(component.find('[data-testid="browser-driver-state"]').text()).toBe('Not downloaded yet')
      expect(component.find('[data-testid="browser-chromium-state"]').text()).toBe('Not downloaded yet')

      await component.find('[data-testid="browser-setup-download"]').trigger('click')
      await flushPromises()
      expect(setupPosts).toBe(1)
      // The POST's own answer already reads as in flight, so the bar replaces the button at once.
      expect(component.find('[data-testid="browser-setup-progress"]').exists()).toBe(true)
      expect(component.find('[data-testid="browser-setup-download"]').exists()).toBe(false)

      await vi.waitFor(() => {
        expect(component.find('[data-testid="browser-driver-state"]').text()).toBe('Downloaded')
      }, { timeout: 5000 })
      expect(component.find('[data-testid="browser-chromium-state"]').text()).toBe('Installed')
      expect(component.find('[data-testid="browser-setup-progress"]').exists()).toBe(false)
    })

    it('shows why the last setup failed, with the button to try again', async () => {
      setupScript = [setupStatus({ driverSource: 'missing', chromiumInstalled: false,
        error: 'The browser driver could not be downloaded: HTTP 503' })]
      baseEndpoints()
      const component = await mountBrowser()
      expect(component.find('[data-testid="browser-setup-error"]').text()).toContain('HTTP 503')
      expect(component.find('[data-testid="browser-setup-download"]').exists()).toBe(true)
    })

    it('offers no download where Playwright has no driver build', async () => {
      setupScript = [setupStatus({ driverSource: 'unsupported', chromiumInstalled: false, platform: null })]
      baseEndpoints()
      const component = await mountBrowser()
      expect(component.find('[data-testid="browser-driver-state"]').text()).toBe('Not available on this platform')
      expect(component.find('[data-testid="browser-setup-download"]').exists()).toBe(false)
    })
  })
})
