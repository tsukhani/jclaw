import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import Channels from '~/pages/channels/index.vue'

// The page fetches both on load (Telegram bindings drive the header badge).
registerEndpoint('/api/channels', () => [])
registerEndpoint('/api/channels/telegram/bindings', () => [])

describe('channels page — Slack configuration guidance (JCLAW-83)', () => {
  it('reveals the Events API request URL and setup steps when configuring Slack', async () => {
    const component = await mountSuspended(Channels)

    // channelTypes = [slack, whatsapp]; the first "Configure" button is Slack.
    const slackButton = component.findAll('button').find(b => b.text() === 'Configure')
    expect(slackButton).toBeDefined()
    await slackButton?.trigger('click')

    const text = component.text()
    // Computed Events API Request URL (origin-relative assertion is host-agnostic).
    expect(text).toContain('/api/webhooks/slack')
    // Setup steps + field guidance the operator needs.
    expect(text).toContain('Event Subscriptions')
    expect(text).toContain('Bot token')
    expect(text).toContain('xoxb-')
    // Must not contradict itself: the public-HTTPS requirement + the localhost
    // caveat must be present (test origin is http://localhost, so no ready URL).
    expect(text).toContain('public HTTPS')
    expect(text).toContain('tunnel')
  })
})
