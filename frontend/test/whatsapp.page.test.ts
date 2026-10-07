import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData, refreshNuxtData } from '#app'
import { nextTick } from 'vue'
import type { H3Event } from 'h3'
import WhatsApp from '~/pages/channels/whatsapp.vue'

// JCLAW-444: per-agent WhatsApp bindings with a per-binding transport choice —
// CLOUD_API (official Cloud API, credential fields) or WHATSAPP_WEB (unofficial
// QR-paired Cobalt, ban-warned, no credentials here).
// JCLAW-445: Cloud-API verification UX (verified name/number, template fields,
// 422-on-save error). JCLAW-448: WhatsApp-Web QR pairing UI.

// jsdom has no real canvas; stub the qrcode render so toDataURL
// returns a deterministic data URL without touching a canvas.
vi.mock('qrcode', () => ({
  default: { toDataURL: vi.fn(async () => 'data:image/png;base64,STUBQR') },
}))

const AGENT = { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }

function binding(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    agentId: 1,
    agentName: 'main',
    transport: 'CLOUD_API',
    phoneNumberId: 'phone-123',
    hasAccessToken: true,
    hasAppSecret: true,
    hasVerifyToken: true,
    verifiedName: null,
    displayPhoneNumber: null,
    templateName: null,
    templateLanguage: null,
    enabled: true,
    createdAt: null,
    updatedAt: null,
    lastDeliveryFailureAt: null,
    lastDeliveryFailureCode: null,
    lastDeliveryFailureTitle: null,
    ...overrides,
  }
}

let bindingsResponse: unknown[] = []
let qrResponse: Record<string, unknown> = { bindingId: 7, transport: 'WHATSAPP_WEB', paired: false, qr: 'pair-string-abc' }
// Counts QR poll hits so the "stops once paired" test can prove the interval
// was torn down (no further polls after paired=true).
let qrPollCount = 0

registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/channels/whatsapp/bindings', () => bindingsResponse)
// JCLAW-448: the QR-pairing poll endpoint. Path id varies; match the suffix.
registerEndpoint('/api/channels/whatsapp/bindings/7/qr', () => {
  qrPollCount++
  return qrResponse
})

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  bindingsResponse = []
  qrResponse = { bindingId: 7, transport: 'WHATSAPP_WEB', paired: false, qr: 'pair-string-abc' }
  qrPollCount = 0
})

describe('whatsapp bindings page — transport choice + cards (JCLAW-444)', () => {
  it('shows the Cloud API transport + phone number id on a saved binding card', async () => {
    bindingsResponse = [binding()]
    const c = await mountSuspended(WhatsApp)
    const text = c.text()
    expect(text).toContain('Cloud API')
    expect(text).toContain('phone-123')
  })

  it('shows the WhatsApp-Web transport + not-yet-paired on a web binding card', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null })]
    const c = await mountSuspended(WhatsApp)
    const text = c.text()
    expect(text).toContain('WhatsApp-Web')
    expect(text).toContain('not yet paired')
  })

  it('renders the Cloud API credential fields + setup guidance on create', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    // Cloud API is the default transport: all four credential fields present.
    expect(c.find('#binding-phone-number-id').exists()).toBe(true)
    expect(c.find('#binding-access-token').exists()).toBe(true)
    expect(c.find('#binding-app-secret').exists()).toBe(true)
    expect(c.find('#binding-verify-token').exists()).toBe(true)
    expect(c.text()).toContain('Phone number ID')
  })

  it('switching to WhatsApp-Web hides the Cloud fields and shows the ban-risk warning', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    expect(c.find('#binding-phone-number-id').exists()).toBe(true)
    // Switch transport to the unofficial WhatsApp-Web stack.
    await c.find('#binding-transport').setValue('WHATSAPP_WEB')
    await nextTick()
    // Cloud-API credential fields are gone…
    expect(c.find('#binding-phone-number-id').exists()).toBe(false)
    expect(c.find('#binding-access-token').exists()).toBe(false)
    // …and the prominent ban-risk warning is shown before save.
    const text = c.text()
    expect(text).toContain('unofficial client')
    expect(text).toContain('banned')
    expect(text).toContain('dedicated secondary number')
  })

  it('blocks save on a Cloud API binding until phoneNumberId + accessToken are set', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    // Pick the only available agent from the searchable dropdown.
    await c.find('#binding-agent').setValue('main')
    await c.find('#binding-agent').trigger('input')
    await nextTick()
    await c.findAll('button').find(b => b.text().includes('gpt-4.1'))!.trigger('mousedown')
    await nextTick()
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')
    // Agent picked but no credentials yet → Save disabled.
    expect(saveBtn!.attributes('disabled')).toBeDefined()
    await c.find('#binding-phone-number-id').setValue('phone-xyz')
    await c.find('#binding-access-token').setValue('tok')
    await nextTick()
    expect(saveBtn!.attributes('disabled')).toBeUndefined()
  })

  it('allows save on a WhatsApp-Web binding with only an agent (no credentials)', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    await c.find('#binding-transport').setValue('WHATSAPP_WEB')
    await nextTick()
    // Pick the only available agent from the searchable dropdown.
    await c.find('#binding-agent').setValue('main')
    await c.find('#binding-agent').trigger('input')
    await nextTick()
    await c.findAll('button').find(b => b.text().includes('gpt-4.1'))!.trigger('mousedown')
    await nextTick()
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')
    // WhatsApp-Web carries no credentials here (paired later) → agent is enough.
    expect(saveBtn!.attributes('disabled')).toBeUndefined()
  })

  it('shows saved secrets as dots when editing, and opens an empty input to change one', async () => {
    bindingsResponse = [binding({ id: 7, hasAppSecret: false })]
    const c = await mountSuspended(WhatsApp)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    // phoneNumberId (an identifier) is pre-filled; a saved secret is never shown back.
    const phone = c.find('#binding-phone-number-id').element as HTMLInputElement
    expect(phone.value).toBe('phone-123')
    expect(c.find('#binding-access-token').exists()).toBe(false)
    expect(c.html()).toContain('••••••••')
    // One that was never saved is an input straight away.
    expect(c.find('#binding-app-secret').exists()).toBe(true)

    await c.find('[aria-label="Edit accessToken"]').trigger('click')
    const token = c.find('#binding-access-token').element as HTMLInputElement
    expect(token.type).toBe('password')
    expect(token.value).toBe('')
  })
})

describe('whatsapp Cloud-API verification UX (JCLAW-445)', () => {
  it('shows the verified business name + display number on a Cloud-API card', async () => {
    bindingsResponse = [binding({ verifiedName: 'Acme Bot', displayPhoneNumber: '+1 555-0100' })]
    const c = await mountSuspended(WhatsApp)
    const text = c.text()
    expect(text).toContain('Verified')
    expect(text).toContain('Acme Bot')
    expect(text).toContain('+1 555-0100')
  })

  it('omits the verified row when verifiedName is null', async () => {
    bindingsResponse = [binding({ verifiedName: null })]
    const c = await mountSuspended(WhatsApp)
    expect(c.text()).not.toContain('Verified')
  })

  it('exposes templateName + templateLanguage fields when transport is Cloud-API', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    expect(c.find('#binding-template-name').exists()).toBe(true)
    expect(c.find('#binding-template-language').exists()).toBe(true)
    expect(c.text()).toContain('24-hour')
  })

  it('hides the template fields on WhatsApp-Web', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    await c.find('#binding-transport').setValue('WHATSAPP_WEB')
    await nextTick()
    expect(c.find('#binding-template-name').exists()).toBe(false)
  })

  it('pre-fills template fields when editing a Cloud-API binding', async () => {
    bindingsResponse = [binding({ templateName: 'assistant_reply', templateLanguage: 'en_US' })]
    const c = await mountSuspended(WhatsApp)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    expect((c.find('#binding-template-name').element as HTMLInputElement).value).toBe('assistant_reply')
    expect((c.find('#binding-template-language').element as HTMLInputElement).value).toBe('en_US')
  })
})

describe('whatsapp WhatsApp-Web QR pairing (JCLAW-448)', () => {
  it('exposes a Pair control on a WhatsApp-Web card', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null })]
    const c = await mountSuspended(WhatsApp)
    expect(c.find('[aria-label="Pair binding"]').exists()).toBe(true)
  })

  it('does not show a Pair control on a Cloud-API card', async () => {
    bindingsResponse = [binding({ transport: 'CLOUD_API' })]
    const c = await mountSuspended(WhatsApp)
    expect(c.find('[aria-label="Pair binding"]').exists()).toBe(false)
  })

  it('opens the pairing panel and renders the polled QR string as a local image', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null })]
    const c = await mountSuspended(WhatsApp)
    await c.find('[aria-label="Pair binding"]').trigger('click')
    // Wait on the end state, not a fixed delay: the immediate poll's $fetch and
    // the async QR render settle over several microtasks, so vi.waitFor retries
    // until the rendered image reflects the polled QR string.
    await vi.waitFor(() => {
      expect(c.find('[role="dialog"]').exists()).toBe(true)
      const img = c.find('img[alt="WhatsApp-Web pairing QR code"]')
      expect(img.exists()).toBe(true)
      expect(img.attributes('src')).toBe('data:image/png;base64,STUBQR')
    })
    // An unpaired panel never clears its poll interval on its own. Unmount so
    // onBeforeUnmount → stopPoll tears down the real 2s interval; otherwise it
    // keeps polling into a torn-down component across later tests.
    c.unmount()
  })

  it('shows a Connected state and stops once paired=true', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null })]
    qrResponse = { bindingId: 7, transport: 'WHATSAPP_WEB', paired: true, qr: null }
    const c = await mountSuspended(WhatsApp)
    // Fake only the poll interval so we can prove polling stops; setTimeout and
    // microtasks stay real, so the immediate first poll's $fetch chain still
    // settles (and vi.waitFor — which needs a real interval — isn't usable here).
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      await c.find('[aria-label="Pair binding"]').trigger('click')
      // The immediate first poll is a plain promise, not gated by the interval.
      // Flush real macrotasks until it lands, then let paired→render commit.
      for (let i = 0; i < 50 && qrPollCount < 1; i++) {
        await flushPromises()
      }
      await flushPromises()
      await nextTick()
      expect(c.text()).toContain('Connected')
      // No QR image while paired.
      expect(c.find('img[alt="WhatsApp-Web pairing QR code"]').exists()).toBe(false)
      // Headline behavior: paired=true clears the 2s interval. Advancing three
      // more ticks (then draining any fetch they'd trigger) must not re-poll.
      await vi.advanceTimersByTimeAsync(6000)
      await flushPromises()
      expect(qrPollCount).toBe(1)
    }
    finally {
      vi.useRealTimers()
      c.unmount()
    }
  })

  it('closes the pairing panel on Cancel', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null })]
    const c = await mountSuspended(WhatsApp)
    await c.find('[aria-label="Pair binding"]').trigger('click')
    await vi.waitFor(() => expect(c.find('[role="dialog"]').exists()).toBe(true))
    await c.findAll('button').find(b => b.text() === 'Cancel' || b.text() === 'Close')!.trigger('click')
    await nextTick()
    expect(c.find('[role="dialog"]').exists()).toBe(false)
    c.unmount()
  })
})

describe('WhatsApp page — a failed binding switch (JCLAW-1221)', () => {
  it('says why the binding did not change', async () => {
    bindingsResponse = [binding({ id: 9, enabled: false })]
    const off = registerEndpoint('/api/channels/whatsapp/bindings/9', {
      method: 'PUT',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const c = await mountSuspended(WhatsApp)
      await flushPromises()
      await c.find('button[aria-label="Enable binding"]').trigger('click')
      await vi.waitFor(() => expect(c.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(c.find('[data-testid="api-error"]').text()).toContain('/api/channels/whatsapp/bindings/9')
    }
    finally {
      off()
    }
  })
})

describe('WhatsApp-Web owner number (JCLAW-1408)', () => {
  it('shows the owner field on WhatsApp-Web only', async () => {
    const c = await mountSuspended(WhatsApp)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    expect(c.find('#binding-owner-number').exists()).toBe(false)
    await c.find('#binding-transport').setValue('WHATSAPP_WEB')
    await nextTick()
    expect(c.find('#binding-owner-number').exists()).toBe(true)
    expect(c.text()).toContain('Main Agent binding')
  })

  it('pre-fills the owner number on edit and sends it on save', async () => {
    bindingsResponse = [binding({ id: 11, transport: 'WHATSAPP_WEB', phoneNumberId: null, ownerNumber: '+15551234567' })]
    let sent: Record<string, unknown> | null = null
    const off = registerEndpoint('/api/channels/whatsapp/bindings/11', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event)
        return binding({ id: 11, transport: 'WHATSAPP_WEB', ownerNumber: sent!.ownerNumber })
      },
    })
    try {
      const c = await mountSuspended(WhatsApp)
      await flushPromises()
      await c.find('[aria-label="Edit binding"]').trigger('click')
      await nextTick()
      const owner = c.find('#binding-owner-number')
      expect((owner.element as HTMLInputElement).value).toBe('+15551234567')
      await owner.setValue(' +15559990000 ')
      await c.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
      await vi.waitFor(() => expect(sent).not.toBeNull())
      expect(sent!.ownerNumber).toBe('+15559990000')
    }
    finally {
      off()
    }
  })

  it('sends null to clear the owner number', async () => {
    bindingsResponse = [binding({ id: 12, transport: 'WHATSAPP_WEB', phoneNumberId: null, ownerNumber: '+15551234567' })]
    let sent: Record<string, unknown> | null = null
    const off = registerEndpoint('/api/channels/whatsapp/bindings/12', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        sent = await readBody(event)
        return binding({ id: 12, transport: 'WHATSAPP_WEB', ownerNumber: null })
      },
    })
    try {
      const c = await mountSuspended(WhatsApp)
      await flushPromises()
      await c.find('[aria-label="Edit binding"]').trigger('click')
      await nextTick()
      await c.find('#binding-owner-number').setValue('')
      await c.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
      await vi.waitFor(() => expect(sent).not.toBeNull())
      expect(sent).toHaveProperty('ownerNumber', null)
    }
    finally {
      off()
    }
  })
})

describe('Meta app subscription (JCLAW-1410)', () => {
  const URL = '/api/channels/whatsapp/bindings/7/subscription'
  let state: Record<string, unknown> = {}
  let getCount = 0
  let getHeld: Promise<void> | null = null
  let getServed = 0
  let postHandler: (event: H3Event) => unknown = () => ({})

  function sub(overrides: Record<string, unknown>) {
    return { bindingId: 7, wabaId: null, appId: null, reason: null, ...overrides }
  }

  registerEndpoint(URL, {
    method: 'GET',
    handler: async () => {
      getCount++
      const answer = state
      if (getHeld) await getHeld
      getServed++
      return answer
    },
  })
  registerEndpoint(URL, {
    method: 'POST',
    handler: event => postHandler(event),
  })

  beforeEach(() => {
    state = sub({ state: 'NOT_SUBSCRIBED', wabaId: '222', appId: '444' })
    getCount = 0
    getHeld = null
    getServed = 0
    postHandler = () => sub({ state: 'SUBSCRIBED', wabaId: '222', appId: '444' })
  })

  // Unmounted so a page left over from this block cannot fire GETs into another test's count.
  const mounted: Awaited<ReturnType<typeof mountSuspended>>[] = []
  async function mount() {
    const c = await mountSuspended(WhatsApp)
    mounted.push(c)
    return c
  }
  afterEach(() => {
    mounted.splice(0).forEach(c => c.unmount())
  })

  function subscribeButton(c: Awaited<ReturnType<typeof mount>>) {
    return c.find('[data-testid="subscription-warning"] button')
  }

  it('warns and offers Subscribe when the app is not subscribed', async () => {
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(true))
    const warning = c.find('[data-testid="subscription-warning"]').text()
    expect(warning).toContain('Meta will not deliver')
    expect(warning).toContain('222')
    const button = subscribeButton(c)
    expect(button.text()).toBe('Subscribe')
    expect(button.attributes('disabled')).toBeUndefined()
  })

  it('shows one muted line with the reason when the check is unknown', async () => {
    state = sub({ state: 'UNKNOWN', reason: '(#200) Permissions error' })
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-unknown"]').exists()).toBe(true))
    expect(c.find('[data-testid="subscription-unknown"]').text()).toContain('(#200) Permissions error')
    expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false)
  })

  it('shows nothing when the app is subscribed', async () => {
    state = sub({ state: 'SUBSCRIBED', wabaId: '222', appId: '444' })
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(getCount).toBeGreaterThan(0))
    await flushPromises()
    expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false)
    expect(c.find('[data-testid="subscription-unknown"]').exists()).toBe(false)
  })

  it('never asks about a WhatsApp-Web binding', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null })]
    const c = await mount()
    await flushPromises()
    expect(getCount).toBe(0)
    expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false)
  })

  it('never asks about a disabled binding', async () => {
    bindingsResponse = [binding({ enabled: false })]
    const c = await mount()
    await flushPromises()
    expect(getCount).toBe(0)
    expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false)
  })

  it('drops the warning when a refresh shows the binding disabled', async () => {
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(true))
    bindingsResponse = [binding({ enabled: false })]
    await refreshNuxtData()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false))
  })

  it('ignores a read that lands after a refresh showed the binding disabled', async () => {
    let release: () => void = () => {}
    getHeld = new Promise<void>((resolve) => {
      release = resolve
    })
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(getCount).toBeGreaterThan(0))
    bindingsResponse = [binding({ enabled: false })]
    await refreshNuxtData()
    await flushPromises()
    release()
    await vi.waitFor(() => expect(getServed).toBeGreaterThan(0))
    await new Promise(resolve => setTimeout(resolve, 50))
    await flushPromises()
    expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false)
  })

  it('clears the warning after a successful Subscribe', async () => {
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(true))
    await subscribeButton(c).trigger('click')
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false))
  })

  it('shows Meta\'s message and keeps the button after a refused Subscribe', async () => {
    postHandler = async (event) => {
      const { setResponseStatus } = await import('h3')
      setResponseStatus(event, 422)
      return { type: 'error', code: 'cloud_api_subscribe_failed', message: 'Meta refused the subscription: (#200) Permissions error' }
    }
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(true))
    await subscribeButton(c).trigger('click')
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-error"]').exists()).toBe(true))
    expect(c.find('[data-testid="subscription-error"]').text()).toContain('Meta refused the subscription: (#200) Permissions error')
    expect(subscribeButton(c).exists()).toBe(true)
  })

  it('disables the button while the Subscribe request is in flight', async () => {
    let release: () => void = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    postHandler = async () => {
      await held
      return sub({ state: 'SUBSCRIBED', wabaId: '222', appId: '444' })
    }
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(true))
    await subscribeButton(c).trigger('click')
    await nextTick()
    expect(subscribeButton(c).attributes('disabled')).toBeDefined()
    release()
    await vi.waitFor(() => expect(c.find('[data-testid="subscription-warning"]').exists()).toBe(false))
  })
})

describe('whatsapp bindings page — delivery failure warning (JCLAW-1411)', () => {
  const MINUTE = 60_000
  const failedAgo = (ms: number) => new Date(Date.now() - ms).toISOString()
  const warning = '[data-testid="delivery-failure-warning"]'

  it('shows the age and Meta\'s code and title for a failure an hour old', async () => {
    bindingsResponse = [binding({
      lastDeliveryFailureAt: failedAgo(60 * MINUTE),
      lastDeliveryFailureCode: 131049,
      lastDeliveryFailureTitle: 'This message was not delivered to maintain healthy ecosystem engagement.',
    })]
    const c = await mountSuspended(WhatsApp)
    const text = c.find(warning).text()
    expect(text).toContain('A reply failed to reach a customer 1h ago')
    expect(text).toContain('Meta error 131049')
    expect(text).toContain('This message was not delivered to maintain healthy ecosystem engagement.')
  })

  it('still shows at 23h59m', async () => {
    bindingsResponse = [binding({ lastDeliveryFailureAt: failedAgo(24 * 60 * MINUTE - MINUTE), lastDeliveryFailureCode: 131042 })]
    const c = await mountSuspended(WhatsApp)
    expect(c.find(warning).exists()).toBe(true)
    expect(c.find(warning).text()).toContain('23h ago')
    expect(c.find(warning).text()).toContain('Meta error 131042')
  })

  it('shows a title alone when Meta gave no code', async () => {
    bindingsResponse = [binding({ lastDeliveryFailureAt: failedAgo(60 * MINUTE), lastDeliveryFailureTitle: 'Payment issue' })]
    const c = await mountSuspended(WhatsApp)
    const text = c.find(warning).text()
    expect(text).toContain('— Payment issue')
    expect(text).not.toContain('Meta error')
  })

  it('ends at the age when Meta gave neither code nor title', async () => {
    bindingsResponse = [binding({ lastDeliveryFailureAt: failedAgo(2 * MINUTE) })]
    const c = await mountSuspended(WhatsApp)
    expect(c.find(warning).text()).toMatch(/customer 2m ago$/)
  })

  it('counts a recent failure in minutes', async () => {
    bindingsResponse = [binding({ lastDeliveryFailureAt: failedAgo(5 * MINUTE), lastDeliveryFailureCode: 131042 })]
    const c = await mountSuspended(WhatsApp)
    expect(c.find(warning).text()).toContain('5m ago')
  })

  it('is gone at 24h01m', async () => {
    bindingsResponse = [binding({ lastDeliveryFailureAt: failedAgo(24 * 60 * MINUTE + MINUTE), lastDeliveryFailureCode: 131042 })]
    const c = await mountSuspended(WhatsApp)
    expect(c.find(warning).exists()).toBe(false)
  })

  it('is absent when the binding has no recorded failure', async () => {
    bindingsResponse = [binding()]
    const c = await mountSuspended(WhatsApp)
    expect(c.find(warning).exists()).toBe(false)
  })
})

describe('this month\'s replies against Meta\'s free allowance (JCLAW-1412)', () => {
  let usage: Record<string, unknown> = {}
  let usageGets = 0

  function counted(replies: number, billed = 0) {
    return { bindingId: 7, state: 'COUNTED', year: 2026, month: 10, replies, billed, allowance: 1000, reason: null }
  }

  registerEndpoint('/api/channels/whatsapp/bindings/7/usage', () => {
    usageGets++
    return usage
  })

  beforeEach(() => {
    usage = counted(412)
    usageGets = 0
  })

  const mounted: Awaited<ReturnType<typeof mountSuspended>>[] = []
  async function mount() {
    const c = await mountSuspended(WhatsApp)
    mounted.push(c)
    return c
  }
  afterEach(() => {
    mounted.splice(0).forEach(c => c.unmount())
  })

  async function bar() {
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="usage"]').exists()).toBe(true))
    return {
      text: c.find('[data-testid="usage"]').text(),
      progress: c.find('[data-testid="usage"] [role="progressbar"]'),
      fill: c.find('[data-testid="usage-fill"]'),
    }
  }

  it('shows the count under the allowance as a progress bar', async () => {
    const { text, progress, fill } = await bar()
    expect(text).toBe('412 of 1,000 free replies this month')
    expect(progress.attributes('aria-valuenow')).toBe('412')
    expect(progress.attributes('aria-valuemin')).toBe('0')
    expect(progress.attributes('aria-valuemax')).toBe('1000')
    expect(progress.attributes('aria-valuetext')).toBe('412 of 1,000 free replies this month')
    expect(Number.parseFloat(fill.attributes('style')!.replace('width:', ''))).toBeCloseTo(41.2)
    expect(fill.classes()).toContain('bg-emerald-600')
    expect(fill.classes()).not.toContain('bg-amber-500')
  })

  it('stays in the normal tone at 799', async () => {
    usage = counted(799)
    const { fill } = await bar()
    expect(fill.classes()).toContain('bg-emerald-600')
  })

  it('takes the warning tone from 80 percent', async () => {
    usage = counted(800)
    const { text, fill } = await bar()
    expect(text).toBe('800 of 1,000 free replies this month')
    expect(fill.classes()).toContain('bg-amber-500')
  })

  it('is full in the warning tone at exactly the allowance', async () => {
    usage = counted(1000)
    const { text, fill } = await bar()
    expect(text).toBe('1,000 of 1,000 free replies this month')
    expect(fill.attributes('style')).toContain('width: 100%')
    expect(fill.classes()).toContain('bg-amber-500')
  })

  it('past the allowance is full and names Meta\'s billed count', async () => {
    // billed comes from Meta's REGULAR count, so it need not equal replies minus the allowance.
    usage = counted(1240, 237)
    const { text, progress, fill } = await bar()
    expect(text).toBe('1,240 replies this month, 237 billed')
    expect(progress.attributes('aria-valuenow')).toBe('1000')
    expect(fill.attributes('style')).toContain('width: 100%')
    expect(fill.classes()).toContain('bg-amber-500')
  })

  it('shows one muted line with the reason when the count is unknown', async () => {
    usage = { ...counted(0), state: 'UNKNOWN', replies: null, billed: null, reason: 'Invalid parameter' }
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(c.find('[data-testid="usage-unknown"]').exists()).toBe(true))
    const line = c.find('[data-testid="usage-unknown"]')
    expect(line.text()).toContain('Invalid parameter')
    expect(line.classes()).toContain('text-fg-muted')
    expect(c.find('[data-testid="usage"]').exists()).toBe(false)
  })

  it('shows nothing when the count is not applicable', async () => {
    usage = { ...counted(0), state: 'NOT_APPLICABLE', replies: null, billed: null }
    bindingsResponse = [binding()]
    const c = await mount()
    await vi.waitFor(() => expect(usageGets).toBeGreaterThan(0))
    await flushPromises()
    expect(c.find('[data-testid="usage"]').exists()).toBe(false)
    expect(c.find('[data-testid="usage-unknown"]').exists()).toBe(false)
  })

  it('never asks about a WhatsApp-Web or a disabled binding', async () => {
    bindingsResponse = [binding({ transport: 'WHATSAPP_WEB', phoneNumberId: null }), binding({ id: 8, enabled: false })]
    const c = await mount()
    await flushPromises()
    expect(usageGets).toBe(0)
    expect(c.find('[data-testid="usage"]').exists()).toBe(false)
  })
})
