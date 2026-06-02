import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
import Telegram from '~/pages/channels/telegram.vue'

// JCLAW-339: the webhook URL is base + the fixed /api/webhooks/telegram/{id}/{secret}
// path. Only the public base is editable; the secret is auto-generated. The base
// pre-fills from the Tailscale Funnel (or a public origin), else blank.

const AGENT = { id: 1, name: 'main', enabled: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }

function binding(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    agentId: 1,
    agentName: 'main',
    telegramUserId: '878224171',
    transport: 'WEBHOOK',
    webhookBaseUrl: null,
    hasWebhookSecret: false,
    effectiveWebhookUrl: null,
    enabled: false,
    replyToMode: null,
    errorReplyPolicy: null,
    notifierCooldownMs: null,
    cooldownUntil: null,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  }
}

let bindingsResponse: unknown[] = []
let tailscaleResponse: Record<string, unknown> = {}
// JCLAW-362: the next probe response the test endpoint returns.
let probeResponse: Record<string, unknown> = {}

registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/channels/telegram/bindings', () => bindingsResponse)
registerEndpoint('/api/tailscale', () => tailscaleResponse)
registerEndpoint('/api/channels/telegram/bindings/7/test', { method: 'POST', handler: () => probeResponse })

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  bindingsResponse = []
  tailscaleResponse = { enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }
  probeResponse = {}
})

describe('telegram bindings page — webhook base URL + auto-secret (JCLAW-339)', () => {
  it('shows the full webhook URL (funnel base + path) for a binding that already has a secret', async () => {
    bindingsResponse = [binding({
      id: 7,
      webhookBaseUrl: 'https://jclaw.tnet.ts.net',
      hasWebhookSecret: true,
      effectiveWebhookUrl: 'https://jclaw.tnet.ts.net/api/webhooks/telegram/7/abc123',
    })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    // Editable base is the host only.
    const base = c.find('#binding-webhook-base').element as HTMLInputElement
    expect(base.value).toBe('https://jclaw.tnet.ts.net')
    // The full URL (with the fixed path) is shown.
    expect(c.text()).toContain('https://jclaw.tnet.ts.net/api/webhooks/telegram/7/abc123')
    // The secret field is gone.
    expect(c.find('#binding-webhook-secret').exists()).toBe(false)
  })

  it('auto-generates a secret and builds the full URL when a secretless binding is edited (funnel live)', async () => {
    bindingsResponse = [binding({ id: 9, webhookBaseUrl: null, hasWebhookSecret: false, effectiveWebhookUrl: null })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    // Base pre-filled from the funnel; full URL built with a generated secret.
    const base = c.find('#binding-webhook-base').element as HTMLInputElement
    expect(base.value).toBe('https://jclaw.tnet.ts.net')
    expect(c.text()).toContain('https://jclaw.tnet.ts.net/api/webhooks/telegram/9/')
  })

  it('prompts for a public URL when the funnel is off and the origin is not public', async () => {
    tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: null }
    bindingsResponse = [binding({ id: 7, webhookBaseUrl: null, hasWebhookSecret: false, effectiveWebhookUrl: null })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const base = c.find('#binding-webhook-base').element as HTMLInputElement
    expect(base.value).toBe('') // jsdom origin is http://localhost → not public
    expect(c.text()).toContain('Enter your public HTTPS URL')
  })
})

describe('telegram bindings page — per-binding settings (JCLAW-378)', () => {
  it('renders the three override controls in the editor', async () => {
    bindingsResponse = [binding({ id: 7, transport: 'POLLING' })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    expect(c.find('#binding-reply-mode').exists()).toBe(true)
    expect(c.find('#binding-error-policy').exists()).toBe(true)
    expect(c.find('#binding-notifier-cooldown').exists()).toBe(true)
  })

  it('pre-fills the override controls from the binding being edited', async () => {
    bindingsResponse = [binding({
      id: 7,
      transport: 'POLLING',
      replyToMode: 'all',
      errorReplyPolicy: 'silent',
      notifierCooldownMs: 12345,
    })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    expect((c.find('#binding-reply-mode').element as HTMLSelectElement).value).toBe('all')
    expect((c.find('#binding-error-policy').element as HTMLSelectElement).value).toBe('silent')
    expect((c.find('#binding-notifier-cooldown').element as HTMLInputElement).value).toBe('12345')
  })

  it('submits the chosen overrides (and null for blanks) in the PUT body', async () => {
    bindingsResponse = [binding({ id: 7, transport: 'POLLING' })]
    let captured: unknown = null
    registerEndpoint('/api/channels/telegram/bindings/7', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        captured = await readBody(event)
        return binding({ id: 7, transport: 'POLLING' })
      },
    })
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    await c.find('#binding-reply-mode').setValue('first')
    await c.find('#binding-notifier-cooldown').setValue('9000')
    // errorReplyPolicy left at '' (Default) → should submit as null.
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await flushPromises()
    await nextTick()
    expect(captured).not.toBeNull()
    expect(captured).toMatchObject({
      replyToMode: 'first',
      notifierCooldownMs: 9000,
      errorReplyPolicy: null,
    })
  })
})

describe('telegram bindings page — health probe (JCLAW-362)', () => {
  it('renders an ok probe result with the bot username after clicking Test', async () => {
    bindingsResponse = [binding({ id: 7, transport: 'POLLING' })]
    probeResponse = {
      ok: true,
      transport: 'POLLING',
      botUsername: 'jclaw_test_bot',
      botId: 4242,
      webhookUrl: null,
      webhookPendingUpdates: null,
      webhookLastError: null,
      error: null,
    }
    const c = await mountSuspended(Telegram)
    // No result before the probe runs.
    expect(c.find('[data-testid="probe-result-7"]').exists()).toBe(false)
    await c.find('[aria-label="Test binding"]').trigger('click')
    await flushPromises()
    await nextTick()
    const result = c.find('[data-testid="probe-result-7"]')
    expect(result.exists()).toBe(true)
    expect(result.text()).toContain('OK')
    expect(result.text()).toContain('@jclaw_test_bot')
  })

  it('renders the error reason when the probe reports not-ok', async () => {
    bindingsResponse = [binding({ id: 7, transport: 'POLLING' })]
    probeResponse = {
      ok: false,
      transport: 'POLLING',
      botUsername: null,
      botId: null,
      webhookUrl: null,
      webhookPendingUpdates: null,
      webhookLastError: null,
      error: 'getMe failed: [401] Unauthorized',
    }
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Test binding"]').trigger('click')
    await flushPromises()
    await nextTick()
    const result = c.find('[data-testid="probe-result-7"]')
    expect(result.exists()).toBe(true)
    expect(result.text()).toContain('getMe failed')
    expect(result.text()).toContain('Unauthorized')
  })

  it('surfaces webhook pending-updates and last error for a webhook binding', async () => {
    bindingsResponse = [binding({ id: 7, transport: 'WEBHOOK' })]
    probeResponse = {
      ok: true,
      transport: 'WEBHOOK',
      botUsername: 'jclaw_test_bot',
      botId: 4242,
      webhookUrl: 'https://jclaw.tnet.ts.net/api/webhooks/telegram/7/abc',
      webhookPendingUpdates: 3,
      webhookLastError: 'Wrong response from the webhook',
      error: null,
    }
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Test binding"]').trigger('click')
    await flushPromises()
    await nextTick()
    const result = c.find('[data-testid="probe-result-7"]')
    expect(result.text()).toContain('Webhook pending updates:')
    expect(result.text()).toContain('3')
    expect(result.text()).toContain('Wrong response from the webhook')
  })
})
