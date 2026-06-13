import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
import Slack from '~/pages/channels/slack.vue'

/**
 * JCLAW-456: the Slack binding form exposes an optional xoxp user token + a
 * "keep read-only" gate (default on), so an operator can opt in to delivery-as-user
 * for private channels the bot isn't in. The token itself is never returned (presence
 * is surfaced as hasUserToken), so the edit field shows a keep-existing placeholder.
 */

const AGENT = { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }

function binding(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    agentId: 1,
    agentName: 'main',
    ownerUserId: 'U-OWNER',
    transport: 'HTTP',
    webhookBaseUrl: 'https://jclaw.tnet.ts.net',
    effectiveRequestUrl: 'https://jclaw.tnet.ts.net/api/webhooks/slack/7',
    hasSigningSecret: true,
    hasAppToken: false,
    hasUserToken: false,
    userTokenReadOnly: true,
    botUserId: null,
    teamId: null,
    enabled: true,
    replyToMode: null,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  }
}

let bindingsResponse: unknown[] = []

registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/channels/slack/bindings', () => bindingsResponse)
registerEndpoint('/api/tailscale', () => ({ enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }))

describe('slack bindings page — user token (JCLAW-456)', () => {
  beforeEach(() => {
    clearNuxtData()
    bindingsResponse = []
  })

  it('shows the user-token field with the read-only gate checked by default', async () => {
    bindingsResponse = [binding({ hasUserToken: false, userTokenReadOnly: true })]
    const c = await mountSuspended(Slack)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()

    const userToken = c.find('#binding-user-token')
    expect(userToken.exists()).toBe(true)
    // New-token placeholder (no stored token to keep).
    expect((userToken.element as HTMLInputElement).placeholder).toContain('xoxp-')

    // The read-only gate defaults to checked.
    const gate = c.find('#binding-user-token-readonly')
    expect(gate.exists()).toBe(true)
    expect((gate.element as HTMLInputElement).checked).toBe(true)
  })

  it('reflects an opted-in binding: gate unchecked + keep-existing placeholder', async () => {
    bindingsResponse = [binding({ hasUserToken: true, userTokenReadOnly: false })]
    const c = await mountSuspended(Slack)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()

    const userToken = c.find('#binding-user-token').element as HTMLInputElement
    expect(userToken.placeholder).toContain('leave blank to keep existing')

    // userTokenReadOnly=false → the gate is unchecked (delivery-as-user enabled).
    const gate = c.find('#binding-user-token-readonly')
    expect(gate.exists()).toBe(true)
    expect((gate.element as HTMLInputElement).checked).toBe(false)
  })
})
