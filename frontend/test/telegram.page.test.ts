import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
import Telegram from '~/pages/channels/telegram.vue'

// JCLAW-338: the webhook URL is funnel-base + /api/webhooks/telegram/{id}/{secret}.
// The base comes from /api/tailscale; the secret from what the operator types,
// or — for an existing secret — the server-derived effectiveWebhookUrl.

const AGENT = { id: 1, name: 'main', enabled: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }

function binding(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    agentId: 1,
    agentName: 'main',
    telegramUserId: '878224171',
    transport: 'WEBHOOK',
    webhookUrl: null,
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
  // useFetch caches by URL across mounts; clear so each test re-fetches its own
  // (possibly different) responses below.
  clearNuxtData()
  bindingsResponse = []
  tailscaleResponse = { enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }
})

describe('telegram bindings page — funnel-derived webhook URL (JCLAW-338)', () => {
  it('pre-fills the webhook URL from the funnel when editing a binding that has a secret', async () => {
    bindingsResponse = [binding({
      id: 7,
      hasWebhookSecret: true,
      effectiveWebhookUrl: 'https://jclaw.tnet.ts.net/api/webhooks/telegram/7/abc123',
    })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const input = c.find('#binding-webhook-url').element as HTMLInputElement
    expect(input.value).toBe('https://jclaw.tnet.ts.net/api/webhooks/telegram/7/abc123')
  })

  it('builds the URL live as the operator types a secret (binding has none yet)', async () => {
    bindingsResponse = [binding({ id: 9, hasWebhookSecret: false, effectiveWebhookUrl: null })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const urlInput = c.find('#binding-webhook-url').element as HTMLInputElement
    expect(urlInput.value).toBe('') // nothing to derive without a secret yet

    await c.find('#binding-webhook-secret').setValue('s3cret')
    await nextTick()
    expect(urlInput.value).toBe('https://jclaw.tnet.ts.net/api/webhooks/telegram/9/s3cret')
  })

  it('does not pre-fill when the funnel is off', async () => {
    tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: null }
    bindingsResponse = [binding({ id: 7, hasWebhookSecret: true, effectiveWebhookUrl: null })]
    const c = await mountSuspended(Telegram)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const input = c.find('#binding-webhook-url').element as HTMLInputElement
    expect(input.value).toBe('')
    // JCLAW-339: the operator is told it won't be auto-registered.
    expect(c.text()).toContain('Tailscale Funnel is offline')
  })
})
