import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
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

  it('leaves secret fields blank-to-keep when editing (placeholders, not values)', async () => {
    bindingsResponse = [binding({ id: 7 })]
    const c = await mountSuspended(WhatsApp)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    // phoneNumberId (an identifier) is pre-filled; secrets are blank with a keep hint.
    const phone = c.find('#binding-phone-number-id').element as HTMLInputElement
    expect(phone.value).toBe('phone-123')
    const token = c.find('#binding-access-token').element as HTMLInputElement
    expect(token.value).toBe('')
    expect(token.placeholder).toContain('leave blank to keep')
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
  })
})
