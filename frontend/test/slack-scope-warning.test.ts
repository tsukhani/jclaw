import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
import Slack from '~/pages/channels/slack.vue'

/**
 * JCLAW-458: saving a Slack binding whose bot token can't list channels for name-based delivery
 * (missing channels:read/groups:read) returns a non-blocking deliveryScopeWarning; the page shows
 * it as a dismissible banner so the operator learns at config time, not hours later at delivery.
 */

const AGENT = { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }
const WARNING = 'This bot token cannot look up channels by name (missing the channels:read / groups:read scope), '
  + 'so name-based delivery will fail. Add the scope and reinstall, or deliver by channel id.'

function binding(over: Record<string, unknown> = {}) {
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
    botUserId: null,
    teamId: null,
    enabled: true,
    replyToMode: null,
    createdAt: null,
    updatedAt: null,
    ...over,
  }
}

let putResponse: () => Record<string, unknown> = () => binding()

registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/channels/slack/bindings', () => [binding()])
registerEndpoint('/api/tailscale', () => ({ enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }))
registerEndpoint('/api/channels/slack/bindings/7', { method: 'PUT', handler: () => putResponse() })

describe('slack bindings — delivery scope warning (JCLAW-458)', () => {
  beforeEach(() => {
    clearNuxtData()
    putResponse = () => binding()
  })

  it('shows a dismissible banner when save returns deliveryScopeWarning', async () => {
    putResponse = () => binding({ deliveryScopeWarning: WARNING })
    const c = await mountSuspended(Slack)
    await flushPromises()

    expect(c.find('[data-testid="scope-notice"]').exists()).toBe(false)

    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const saveBtn = c.findAll('button').find(b => b.text().includes('Save'))
    await saveBtn!.trigger('click')
    await flushPromises()

    const notice = c.find('[data-testid="scope-notice"]')
    expect(notice.exists()).toBe(true)
    expect(notice.text()).toContain('groups:read')

    await notice.find('button[aria-label="Dismiss warning"]').trigger('click')
    await nextTick()
    expect(c.find('[data-testid="scope-notice"]').exists()).toBe(false)
  })

  it('shows no banner when save returns no warning', async () => {
    putResponse = () => binding({ deliveryScopeWarning: null })
    const c = await mountSuspended(Slack)
    await flushPromises()

    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const saveBtn = c.findAll('button').find(b => b.text().includes('Save'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(c.find('[data-testid="scope-notice"]').exists()).toBe(false)
  })
})
