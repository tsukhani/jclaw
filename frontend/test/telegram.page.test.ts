import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
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
    cooldownUntil: null,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  }
}

let bindingsResponse: unknown[] = []
let tailscaleResponse: Record<string, unknown> = {}

registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/channels/telegram/bindings', () => bindingsResponse)
registerEndpoint('/api/tailscale', () => tailscaleResponse)

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  bindingsResponse = []
  tailscaleResponse = { enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }
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
