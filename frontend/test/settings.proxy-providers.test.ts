import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Settings > Proxy Providers (JCLAW-1323): the DataImpulse and Manual cards compose the
 * web_scrape.proxy.* keys and read them back, write them in the order the backend's cross-key checks
 * accept, and test the saved proxy. DataImpulse keeps a login and password per plan (JCLAW-1334).
 */

const URL_KEY = 'web_scrape.proxy.url'
const USERNAME_KEY = 'web_scrape.proxy.username'
const PASSWORD_KEY = 'web_scrape.proxy.password'
const ENABLED_KEY = 'web_scrape.proxy.enabled'
const PLAN_KEY = 'web_scrape.proxy.dataimpulse.plan'
const TARGETING_KEY = 'web_scrape.proxy.dataimpulse.targeting'
const loginKey = (plan: string) => `web_scrape.proxy.dataimpulse.${plan}.login`
const passwordKey = (plan: string) => `web_scrape.proxy.dataimpulse.${plan}.password`

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
        key, value: key.endsWith('password') && value.length > 4 ? `${value.slice(0, 4)}****` : value, updatedAt: '2026-09-28T00:00:00Z',
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

function checked(component: Panel, selector: string): boolean {
  return (component.find(selector).element as HTMLInputElement).checked
}

function useDisabled(component: Panel, plan: string): boolean {
  return component.find(`#proxy-dataimpulse-use-${plan}`).attributes('disabled') !== undefined
}

/** A DataImpulse proxy on the gateway, as the backend stores it once a plan is chosen. */
function seedDataImpulse(url = 'http://gw.dataimpulse.com:823', targeting = '') {
  stored.set(URL_KEY, url)
  stored.set(loginKey('residential'), 'abc')
  stored.set(passwordKey('residential'), 'abcdef-secret')
  stored.set(PLAN_KEY, 'residential')
  if (targeting) stored.set(TARGETING_KEY, targeting)
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

  it('saves a rotating DataImpulse proxy for any country, plan credentials first', async () => {
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    expect(component.find('[data-testid="proxy-dataimpulse-dashboard"]').attributes('href')).toBe('https://app.dataimpulse.com/')
    expect(component.find('[data-testid="proxy-dataimpulse-plans"]').findAll('input[type="radio"]')).toHaveLength(4)
    await component.find('#proxy-dataimpulse-residential-login').setValue('abc')
    await component.find('#proxy-dataimpulse-residential-password').setValue('s3cret')
    await component.find('#proxy-dataimpulse-use-residential').setValue(true)
    await save(component)

    expect(posted).toEqual([
      { key: loginKey('residential'), value: 'abc' },
      { key: passwordKey('residential'), value: 's3cret' },
      { key: URL_KEY, value: 'http://gw.dataimpulse.com:823' },
      { key: PLAN_KEY, value: 'residential' },
    ])
    // The saved password shows as dots, not as an empty box that reads as lost.
    expect(component.find('#proxy-dataimpulse-residential-password').exists()).toBe(false)
    expect(component.find('[data-testid="proxy-dataimpulse-residential-password-field"]').text()).toBe('••••••••')
    expect(component.find('[aria-label="Edit DataImpulse Residential password"]').exists()).toBe(true)
    expect(component.find('[data-testid="proxy-saved-dataimpulse"]').exists()).toBe(true)
  })

  it('enables a plan\'s use radio only once it has both a login and a password', async () => {
    stored.set(loginKey('mobile'), 'saved-login')
    stored.set(passwordKey('premium-residential'), 'abcdef-secret')
    endpoints()
    const component = await mountPanel()
    await choose(component, 'dataimpulse')

    for (const plan of ['residential', 'premium-residential', 'mobile', 'datacenter']) {
      expect(useDisabled(component, plan), plan).toBe(true)
    }
    await component.find('#proxy-dataimpulse-datacenter-login').setValue('dc')
    expect(useDisabled(component, 'datacenter')).toBe(true)
    await component.find('#proxy-dataimpulse-datacenter-password').setValue('dc-pass')
    expect(useDisabled(component, 'datacenter')).toBe(false)
    // A saved password counts as much as a typed one.
    await component.find('#proxy-dataimpulse-premium-residential-login').setValue('pr')
    expect(useDisabled(component, 'premium-residential')).toBe(false)
    await component.find('#proxy-dataimpulse-datacenter-login').setValue('')
    expect(useDisabled(component, 'datacenter')).toBe(true)
  })

  it('composes countries and a sticky session into the shared targeting', async () => {
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    await component.find('#proxy-dataimpulse-residential-login').setValue('abc')
    await component.find('#proxy-dataimpulse-residential-password').setValue('s3cret')
    await component.find('#proxy-dataimpulse-use-residential').setValue(true)
    await component.find('#proxy-dataimpulse-countries').setValue('us')
    await component.find('#proxy-dataimpulse-sticky').setValue(true)
    await component.find('#proxy-dataimpulse-minutes').setValue('45')
    expect(component.find('[data-testid="proxy-dataimpulse-preview"]').text()).toContain('abc__cr.us;sessttl.45')
    await save(component)

    expect(stored.get(URL_KEY)).toBe('http://gw.dataimpulse.com:10000')
    expect(stored.get(TARGETING_KEY)).toBe('cr.us;sessttl.45')
    expect(stored.has(USERNAME_KEY)).toBe(false)
  })

  it('reads a saved DataImpulse proxy back into its card, without the password', async () => {
    seedDataImpulse('http://gw.dataimpulse.com:10000', 'cr.de,au;sessttl.45')
    stored.set(loginKey('mobile'), 'mob')
    endpoints()
    const component = await mountPanel()

    expect(checked(component, '#proxy-provider-dataimpulse')).toBe(true)
    expect(checked(component, '#proxy-dataimpulse-use-residential')).toBe(true)
    expect(inputValue(component, '#proxy-dataimpulse-residential-login')).toBe('abc')
    expect(inputValue(component, '#proxy-dataimpulse-mobile-login')).toBe('mob')
    expect(inputValue(component, '#proxy-dataimpulse-countries')).toBe('de,au')
    expect(checked(component, '#proxy-dataimpulse-sticky')).toBe(true)
    expect(inputValue(component, '#proxy-dataimpulse-minutes')).toBe('45')
    expect(component.find('[data-testid="proxy-dataimpulse-residential-password-field"]').text()).toBe('••••••••')
    expect(component.find('#proxy-dataimpulse-mobile-password').exists()).toBe(true)
    expect(component.html()).not.toContain('abcd')

    await component.find('[aria-label="Edit DataImpulse Residential password"]').trigger('click')
    const password = component.find('#proxy-dataimpulse-residential-password')
    expect(password.attributes('type')).toBe('password')
    expect((password.element as HTMLInputElement).value).toBe('')
  })

  it('changes a saved plan password through the pencil, and the X keeps the saved one', async () => {
    seedDataImpulse()
    endpoints()
    const component = await mountPanel()

    await component.find('[aria-label="Edit DataImpulse Residential password"]').trigger('click')
    await component.find('#proxy-dataimpulse-residential-password').setValue('typed')
    await component.find('[aria-label="Keep the saved DataImpulse Residential password"]').trigger('click')
    expect(component.find('[data-testid="proxy-dataimpulse-residential-password-field"]').text()).toBe('••••••••')
    await save(component)
    expect(posted).toEqual([])

    await component.find('[aria-label="Edit DataImpulse Residential password"]').trigger('click')
    await component.find('#proxy-dataimpulse-residential-password').setValue('new-secret')
    await save(component)
    expect(posted).toEqual([{ key: passwordKey('residential'), value: 'new-secret' }])
    expect(component.find('[data-testid="proxy-dataimpulse-residential-password-field"]').text()).toBe('••••••••')
  })

  it('keeps the saved password on a save that leaves it untouched', async () => {
    seedDataImpulse()
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-dataimpulse-countries').setValue('de')
    await save(component)

    expect(posted).toEqual([{ key: TARGETING_KEY, value: 'cr.de' }])
    expect(stored.get(passwordKey('residential'))).toBe('abcdef-secret')
  })

  it('switching the plan writes the plan and nothing else', async () => {
    seedDataImpulse('http://gw.dataimpulse.com:823', 'cr.de')
    stored.set(loginKey('mobile'), 'mob')
    stored.set(passwordKey('mobile'), 'mobile-secret')
    endpoints()
    const component = await mountPanel()

    expect(component.find('[data-testid="proxy-dataimpulse-preview"]').text()).toContain('abc__cr.de')
    await component.find('#proxy-dataimpulse-use-mobile').setValue(true)
    expect(component.find('[data-testid="proxy-dataimpulse-preview"]').text()).toContain('mob__cr.de')
    await save(component)

    expect(posted).toEqual([{ key: PLAN_KEY, value: 'mobile' }])
  })

  it('keeps targeting parameters it does not model', async () => {
    seedDataImpulse('http://gw.dataimpulse.com:823', 'cr.us;anon.1')
    endpoints()
    const component = await mountPanel()

    await component.find('#proxy-dataimpulse-countries').setValue('de')
    await save(component)

    expect(stored.get(TARGETING_KEY)).toBe('cr.de;anon.1')
  })

  it('shows any other proxy in the Manual card with its raw fields', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(USERNAME_KEY, 'scraper')
    endpoints()
    const component = await mountPanel()

    expect((component.find('#proxy-provider-manual').element as HTMLInputElement).checked).toBe(true)
    expect(inputValue(component, '#proxy-manual-url')).toBe('http://proxy.example:3128')
    expect(inputValue(component, '#proxy-manual-username')).toBe('scraper')
    expect(component.find('#proxy-dataimpulse-residential-login').exists()).toBe(false)
  })

  it('Manual keeps the plan credentials stored and saves the generic keys', async () => {
    seedDataImpulse()
    endpoints()
    const component = await mountPanel()

    await choose(component, 'manual')
    await component.find('#proxy-manual-url').setValue('http://proxy.example:3128')
    await component.find('#proxy-manual-username').setValue('scraper')
    await component.find('#proxy-manual-password').setValue('pw')
    await save(component)

    expect(posted).toEqual([
      { key: URL_KEY, value: 'http://proxy.example:3128' },
      { key: USERNAME_KEY, value: 'scraper' },
      { key: PASSWORD_KEY, value: 'pw' },
    ])
    expect(stored.get(PLAN_KEY)).toBe('residential')
    expect(stored.get(passwordKey('residential'))).toBe('abcdef-secret')
  })

  it('moving from Manual back to DataImpulse keeps the stored targeting', async () => {
    seedDataImpulse('http://proxy.example:3128', 'cr.de;anon.1')
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    expect(inputValue(component, '#proxy-dataimpulse-countries')).toBe('de')
    await save(component)

    expect(posted).toEqual([{ key: URL_KEY, value: 'http://gw.dataimpulse.com:823' }])
    expect(stored.get(TARGETING_KEY)).toBe('cr.de;anon.1')
  })

  it('None clears the URL and leaves the credentials stored', async () => {
    seedDataImpulse()
    endpoints()
    const component = await mountPanel()

    await choose(component, 'none')
    await save(component)

    expect(posted).toEqual([{ key: URL_KEY, value: '' }])
    expect(stored.get(loginKey('residential'))).toBe('abc')
    expect(stored.get(PLAN_KEY)).toBe('residential')
  })

  it('names each bad field inline and writes nothing', async () => {
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    await component.find('#proxy-dataimpulse-mobile-login').setValue('abc def')
    await component.find('#proxy-dataimpulse-countries').setValue('germany')
    await component.find('#proxy-dataimpulse-sticky').setValue(true)
    await component.find('#proxy-dataimpulse-minutes').setValue('200')
    await save(component)

    expect(posted).toEqual([])
    expect(component.find('[data-testid="proxy-dataimpulse-preview"]').exists()).toBe(false)
    expect(component.find('#proxy-dataimpulse-plan-error').text()).toBe('Choose the plan the proxy uses.')
    for (const id of ['mobile-login', 'countries', 'minutes']) {
      expect(component.find(`#proxy-dataimpulse-${id}-error`).exists(), id).toBe(true)
      expect(component.find(`#proxy-dataimpulse-${id}`).attributes('aria-invalid'), id).toBe('true')
    }
    expect(component.find('#proxy-dataimpulse-residential-login').attributes('aria-invalid')).toBe('false')

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

    await choose(component, 'manual')
    await component.find('#proxy-manual-url').setValue('http://proxy.example:3128')
    await component.find('#proxy-manual-username').setValue('scraper')
    expect(component.find('#proxy-manual-password').exists()).toBe(true)
    const hint = component.find('#proxy-manual-password-hint').text()
    expect(hint).toContain('gw.dataimpulse.com')
    expect(hint).toContain('saving another host clears it')
    await save(component)

    expect(posted).toEqual([
      { key: USERNAME_KEY, value: '' },
      { key: PASSWORD_KEY, value: '' },
      { key: URL_KEY, value: 'http://proxy.example:3128' },
      { key: USERNAME_KEY, value: 'scraper' },
    ])
  })

  it('moving from Manual to DataImpulse clears the Manual proxy\'s password and uses the plan\'s', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    stored.set(PASSWORD_KEY, 'abcdef-secret')
    endpoints()
    const component = await mountPanel()

    await choose(component, 'dataimpulse')
    await component.find('#proxy-dataimpulse-residential-login').setValue('abc')
    expect(useDisabled(component, 'residential')).toBe(true)
    await component.find('#proxy-dataimpulse-residential-password').setValue('res')
    await component.find('#proxy-dataimpulse-use-residential').setValue(true)
    await save(component)

    expect(posted).toEqual([
      { key: loginKey('residential'), value: 'abc' },
      { key: passwordKey('residential'), value: 'res' },
      { key: PASSWORD_KEY, value: '' },
      { key: URL_KEY, value: 'http://gw.dataimpulse.com:823' },
      { key: PLAN_KEY, value: 'residential' },
    ])
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

  it('says where the proxy\'s name resolved, so a filtering resolver\'s block page is recognizable', async () => {
    stored.set(URL_KEY, 'http://gw.dataimpulse.com:823')
    stored.set(USERNAME_KEY, 'abc')
    endpoints({ test: {
      ok: false, ip: null, ms: 30000, status: null, reason: null, error: 'Nothing answered through the proxy: timeout',
      proxy: { host: 'gw.dataimpulse.com', address: '146.112.61.106', reverseName: 'hit-adult.opendns.com' },
    } })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="proxy-test-resolution"]').text().replace(/\s+/g, ' '))
      .toBe('gw.dataimpulse.com resolved to 146.112.61.106 (hit-adult.opendns.com) on this machine.')
  })

  it('says when the proxy\'s name did not resolve, and nothing for an address', async () => {
    stored.set(URL_KEY, 'http://proxy.example:3128')
    endpoints({ test: {
      ok: false, ip: null, ms: 5, status: null, reason: null, error: 'Nothing answered through the proxy: proxy.example',
      proxy: { host: 'proxy.example', address: null, reverseName: null },
    } })
    const component = await mountPanel()

    await component.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    expect(component.find('[data-testid="proxy-test-resolution"]').text().replace(/\s+/g, ' '))
      .toBe('proxy.example did not resolve on this machine.')

    stored.set(URL_KEY, 'http://74.81.81.81:823')
    endpoints({ test: { ok: true, ip: '203.0.113.7', ms: 400, status: 200, reason: 'OK', error: null,
      proxy: { host: '74.81.81.81', address: '74.81.81.81', reverseName: null } } })
    const literal = await mountPanel()
    await literal.find('[data-testid="proxy-test"]').trigger('click')
    await flushPromises()

    expect(literal.find('[data-testid="proxy-test-result"]').text()).toContain('203.0.113.7')
    expect(literal.find('[data-testid="proxy-test-resolution"]').exists()).toBe(false)
  })

  it('reads a proxy on DataImpulse\'s IP gateway into its card, and saves the gateway chosen', async () => {
    seedDataImpulse('http://74.81.81.81:823')
    endpoints()
    const component = await mountPanel()

    expect(checked(component, '#proxy-provider-dataimpulse')).toBe(true)
    expect(checked(component, '#proxy-dataimpulse-gateway-ip')).toBe(true)

    await component.find('#proxy-dataimpulse-gateway-hostname').setValue(true)
    await save(component)

    // Plan passwords only go to DataImpulse, so the saved one follows the gateway's name.
    expect(posted).toEqual([{ key: URL_KEY, value: 'http://gw.dataimpulse.com:823' }])
    expect(stored.get(passwordKey('residential'))).toBe('abcdef-secret')
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
