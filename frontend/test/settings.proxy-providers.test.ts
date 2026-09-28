import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Settings > Proxy Providers (JCLAW-1323): the DataImpulse and Manual cards compose the four
 * web_scrape.proxy.* keys and read them back, write them in the order the backend's cross-key checks
 * accept, and test the saved proxy.
 */

const URL_KEY = 'web_scrape.proxy.url'
const USERNAME_KEY = 'web_scrape.proxy.username'
const PASSWORD_KEY = 'web_scrape.proxy.password'
const ENABLED_KEY = 'web_scrape.proxy.enabled'

let stored: Map<string, string>
let posted: Array<{ key: string, value: string }>
let testCalls: number

function endpoints(opts: { refuse?: string, test?: Record<string, unknown>, testFails?: boolean, holdTest?: Promise<void> } = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', {
    method: 'GET',
    // The API masks a secret on every read, as ConfigService.maskValue does.
    handler: () => ({
      entries: [...stored].map(([key, value]) => ({
        key, value: key === PASSWORD_KEY && value.length > 4 ? `${value.slice(0, 4)}****` : value, updatedAt: '2026-09-28T00:00:00Z',
      })),
    }),
  })
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as { key: string, value: string }
      posted.push(body)
      if (opts.refuse === body.key) {
        setResponseStatus(event, 403)
        return { type: 'error', code: 'forbidden', message: 'SOCKS5 proxies are used without credentials.' }
      }
      stored.set(body.key, body.value)
      return { status: 'ok' }
    },
  })
  registerEndpoint('/api/scrape/proxy/test', {
    method: 'POST',
    handler: async (event) => {
      testCalls++
      if (opts.holdTest) await opts.holdTest
      if (opts.testFails) {
        setResponseStatus(event, 500)
        return { type: 'error', code: 'internal_error', message: 'The proxy test could not run.' }
      }
      return opts.test ?? { ok: true, ip: '203.0.113.7', ms: 412, status: 200, reason: 'OK', error: null }
    },
  })
}

async function mountPanel() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'proxy-providers'
  await flushPromises()
  await flushPromises()
  return component
}

type Panel = Awaited<ReturnType<typeof mountPanel>>

async function choose(component: Panel, provider: 'none' | 'dataimpulse' | 'manual') {
  await component.find(`#proxy-provider-${provider}`).setValue(true)
}

// Each write is its own POST, so a save is done when the button is enabled again.
async function save(component: Panel) {
  await component.find('[data-testid="proxy-save"]').trigger('click')
  await vi.waitFor(() => expect(component.find('[data-testid="proxy-save"]').attributes('disabled')).toBeUndefined())
  await flushPromises()
}

function inputValue(component: Panel, selector: string): string {
  return (component.find(selector).element as HTMLInputElement).value
}

describe('Settings page — Proxy Providers', () => {
  beforeEach(() => {
    clearNuxtData()
    stored = new Map()
    posted = []
    testCalls = 0
  })

  it('starts on None with nothing to test and no enabled switch', async () => {
    endpoints()
    const component = await mountPanel()

    expect(component.html()).toMatch(/<h2[^>]*>\s*Proxy Providers\s*</)
    expect((component.find('#proxy-provider-none').element as HTMLInputElement).checked).toBe(true)
    expect(component.find('[data-testid="proxy-saved-none"]').exists()).toBe(true)
    expect(component.find('[data-testid="proxy-test"]').attributes('disabled')).toBeDefined()
    expect(component.find('[data-testid="proxy-enabled"]').exists()).toBe(false)
  })

  it('saves a rotating DataImpulse proxy for any country, URL first', async () => {
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    expect(component.find('[data-testid="proxy-dataimpulse-dashboard"]').attributes('href')).toBe('https://app.dataimpulse.com/')
    await component.find('#proxy-dataimpulse-login').setValue('abc')
    await component.find('#proxy-dataimpulse-password').setValue('s3cret')
    await save(component)

    expect(posted).toEqual([
      { key: URL_KEY, value: 'http://gw.dataimpulse.com:823' },
      { key: USERNAME_KEY, value: 'abc' },
      { key: PASSWORD_KEY, value: 's3cret' },
    ])
    expect(inputValue(component, '#proxy-dataimpulse-password')).toBe('')
    expect(component.find('[data-testid="proxy-saved-dataimpulse"]').exists()).toBe(true)
  })

  it('composes countries and a sticky session into the login', async () => {
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    await component.find('#proxy-dataimpulse-login').setValue('abc')
    await component.find('#proxy-dataimpulse-password').setValue('s3cret')
    await component.find('#proxy-dataimpulse-countries').setValue('us')
    await component.find('#proxy-dataimpulse-sticky').setValue(true)
    await component.find('#proxy-dataimpulse-minutes').setValue('45')
    expect(component.find('[data-testid="proxy-dataimpulse-preview"]').text()).toContain('abc__cr.us;sessttl.45')
    await save(component)

    expect(stored.get(URL_KEY)).toBe('http://gw.dataimpulse.com:10000')
    expect(stored.get(USERNAME_KEY)).toBe('abc__cr.us;sessttl.45')
  })

  it('reads a saved DataImpulse proxy back into its card, without the password', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:10000')
    stored.set(USERNAME_KEY, 'abc__cr.de,au;sessttl.45')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    expect((component.find('#proxy-provider-dataimpulse').element as HTMLInputElement).checked).toBe(true)
    expect(inputValue(component, '#proxy-dataimpulse-login')).toBe('abc')
    expect(inputValue(component, '#proxy-dataimpulse-countries')).toBe('de,au')
    expect((component.find('#proxy-dataimpulse-sticky').element as HTMLInputElement).checked).toBe(true)
    expect(inputValue(component, '#proxy-dataimpulse-minutes')).toBe('45')
    const password = component.find('#proxy-dataimpulse-password')
    expect(password.attributes('type')).toBe('password')
    expect((password.element as HTMLInputElement).value).toBe('')
    expect(component.html()).not.toContain('abcd')
  })

  it('keeps the saved password when the field is left blank', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-dataimpulse-countries').setValue('de')
    await save(component)

    expect(posted).toEqual([{ key: USERNAME_KEY, value: 'abc__cr.de' }])
  })

  it('keeps username parameters it does not model', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc__cr.us;anon.1')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-dataimpulse-countries').setValue('de')
    await save(component)

    expect(stored.get(USERNAME_KEY)).toBe('abc__cr.de;anon.1')
  })

  it('shows any other proxy in the Manual card with its raw fields', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(USERNAME_KEY, 'scraper')
    endpoints()
    const component = await mountPanel()

    expect((component.find('#proxy-provider-manual').element as HTMLInputElement).checked).toBe(true)
    expect(inputValue(component, '#proxy-manual-url')).toBe('http://proxy.example:3128')
    expect(inputValue(component, '#proxy-manual-username')).toBe('scraper')
    expect(component.find('#proxy-dataimpulse-login').exists()).toBe(false)
  })

  it('None clears the URL and leaves the credentials stored', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    await choose(component, 'none')
    await save(component)

    expect(posted).toEqual([{ key: URL_KEY, value: '' }])
    expect(stored.get(USERNAME_KEY)).toBe('abc')
  })

  it('names each bad field inline and writes nothing', async () => {
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    await component.find('#proxy-dataimpulse-countries').setValue('germany')
    await component.find('#proxy-dataimpulse-sticky').setValue(true)
    await component.find('#proxy-dataimpulse-minutes').setValue('200')
    await save(component)

    expect(posted).toEqual([])
    expect(component.find('[data-testid="proxy-dataimpulse-preview"]').exists()).toBe(false)
    for (const id of ['login', 'password', 'countries', 'minutes']) {
      expect(component.find(`#proxy-dataimpulse-${id}-error`).exists(), id).toBe(true)
      expect(component.find(`#proxy-dataimpulse-${id}`).attributes('aria-invalid'), id).toBe('true')
    }

    await component.find('#proxy-dataimpulse-minutes').setValue('0')
    await save(component)
    expect(component.find('#proxy-dataimpulse-minutes-error').exists()).toBe(true)
    expect(posted).toEqual([])
  })

  it('clears the credentials before a SOCKS5 URL, which the backend refuses while any are stored', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(USERNAME_KEY, 'scraper')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-manual-url').setValue('socks5://127.0.0.1:1080')
    expect(component.find('#proxy-manual-username').attributes('disabled')).toBeDefined()
    await save(component)

    expect(posted).toEqual([
      { key: USERNAME_KEY, value: '' },
      { key: PASSWORD_KEY, value: '' },
      { key: URL_KEY, value: 'socks5://127.0.0.1:1080' },
    ])
  })

  it('shows a refused write and what landed before it', async () => {
    endpoints({ refuse: USERNAME_KEY })
    const component = await mountPanel()

    await choose(component, 'manual')
    await component.find('#proxy-manual-url').setValue('http://proxy.example:3128')
    await component.find('#proxy-manual-username').setValue('scraper')
    await save(component)

    expect(posted.map(p => p.key)).toEqual([URL_KEY, USERNAME_KEY])
    expect(component.find('[role="alert"]').text()).toContain('SOCKS5 proxies are used without credentials.')
    expect(component.find('[data-testid="proxy-saved-manual"]').exists()).toBe(true)
  })

  it('switches a saved proxy off without clearing it', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints()
    const component = await mountPanel()

    const toggle = component.find('[data-testid="proxy-enabled"]')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    await toggle.trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: ENABLED_KEY, value: 'false' }])
    await vi.waitFor(() => expect(component.find('[data-testid="proxy-enabled"]').attributes('aria-pressed')).toBe('false'))
    expect(component.find('[data-testid="proxy-test"]').attributes('disabled')).toBeDefined()
  })

  it('tests the saved proxy and shows the egress IP and the time taken', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc')
    endpoints()
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    expect(testCalls).toBe(1)
    const result = component.find('[data-testid="proxy-test-result"]').text()
    expect(result).toContain('203.0.113.7')
    expect(result).toContain('412 ms')
  })

  it('shows the provider\'s own refusal, not a generic failure', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc')
    endpoints({ test: { ok: false, ip: null, ms: 1250, status: 407, reason: 'TRAFFIC_EXHAUSTED', error: null } })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    const result = component.find('[data-testid="proxy-test-result"]').text()
    expect(result).toContain('407 TRAFFIC_EXHAUSTED')
    expect(result).toContain('1.3 s')
  })

  it('cannot test a proxy that is saved but switched off', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(ENABLED_KEY, 'false')
    endpoints()
    const component = await mountPanel()

    const button = component.find('[data-testid="proxy-test"]')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(testCalls).toBe(0)
    expect(component.find('[data-testid="proxy-saved-dataimpulse"]').exists()).toBe(true)
    expect(component.find('[data-testid="proxy-switched-off-dataimpulse"]').text()).toBe('switched off')
  })

  it('a save switches a proxy that was off back on, after its credentials', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(ENABLED_KEY, 'false')
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-manual-username').setValue('scraper')
    await save(component)

    expect(posted).toEqual([
      { key: USERNAME_KEY, value: 'scraper' },
      { key: ENABLED_KEY, value: 'true' },
    ])
  })

  it('clears the saved credentials before moving to another host, never carrying the password there', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    expect(component.find('#proxy-dataimpulse-password-hint').text()).toContain('gw.dataimpulse.com')
    await choose(component, 'manual')
    await component.find('#proxy-manual-url').setValue('http://proxy.example:3128')
    await component.find('#proxy-manual-username').setValue('scraper')
    expect(component.find('#proxy-manual-password-hint').text()).toContain('saving another host clears it')
    await save(component)

    expect(posted).toEqual([
      { key: USERNAME_KEY, value: '' },
      { key: PASSWORD_KEY, value: '' },
      { key: URL_KEY, value: 'http://proxy.example:3128' },
      { key: USERNAME_KEY, value: 'scraper' },
    ])
  })

  it('asks for a DataImpulse password when the saved one belongs to another host', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    await component.find('#proxy-dataimpulse-login').setValue('abc')
    await save(component)

    expect(component.find('#proxy-dataimpulse-password-error').exists()).toBe(true)
    expect(posted).toEqual([])
  })

  it('refuses a Manual URL the backend would refuse, including an empty one, before writing anything', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(USERNAME_KEY, 'scraper')
    endpoints()
    const component = await mountPanel()

    for (const url of ['', 'socks5://proxy.example', 'http://u:p@proxy.example:8080']) {
      await component.find('#proxy-manual-url').setValue(url)
      await save(component)
      expect(component.find('#proxy-manual-url-error').exists(), url).toBe(true)
    }
    expect(posted).toEqual([])
    expect(component.find('[data-testid="proxy-saved-manual"]').exists()).toBe(true)
  })

  it('keeps a half-typed card when the enabled switch is flipped', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-manual-url').setValue('http://other.example:8080')
    await component.find('[data-testid="proxy-enabled"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[data-testid="proxy-enabled"]').attributes('aria-pressed')).toBe('false'))

    expect((component.find('#proxy-provider-manual').element as HTMLInputElement).checked).toBe(true)
    expect(inputValue(component, '#proxy-manual-url')).toBe('http://other.example:8080')
  })

  it('shows an error-only result, when nothing answered through the proxy', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints({ test: { ok: false, ip: null, ms: 30000, status: null, reason: null, error: 'Nothing answered through the proxy: timeout' } })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="proxy-test-result"]').text()).toBe('Nothing answered through the proxy: timeout')
  })

  it('shows only the error when the echo answered 2xx with something other than an address', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints({ test: { ok: false, ip: null, ms: 80, status: 200, reason: 'OK', error: 'The IP echo answered with something that is not an address.' } })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="proxy-test-result"]').text()).toBe('The IP echo answered with something that is not an address.')
  })

  it('shows why the test itself failed, and clears it on the next save', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints({ testFails: true })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('[data-testid="api-error"]').text()).toContain('The proxy test could not run.')

    await component.find('#proxy-manual-username').setValue('scraper')
    await save(component)
    expect(component.find('[data-testid="api-error"]').exists()).toBe(false)
  })

  it('holds Save and the switch while a test is in flight', async () => {
    let release!: () => void
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints({ holdTest: new Promise<void>((resolve) => {
      release = resolve
    }) })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[data-testid="proxy-save"]').attributes('disabled')).toBeDefined())
    expect(component.find('[data-testid="proxy-enabled"]').attributes('disabled')).toBeDefined()
    release()
    await vi.waitFor(() => expect(component.find('[data-testid="proxy-save"]').attributes('disabled')).toBeUndefined())
  })
})
