import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import Channels from '~/pages/channels/index.vue'

// Mutable so individual tests can vary the Tailscale Funnel status.
let tailscaleResponse: Record<string, unknown> = { enabled: false, available: false, publicUrl: null, error: null }

registerEndpoint('/api/channels', () => [])
registerEndpoint('/api/channels/telegram/bindings', () => [])
registerEndpoint('/api/tailscale', () => tailscaleResponse)

beforeEach(() => {
  // useFetch caches by URL across mounts; clear it so each test re-fetches the
  // (possibly different) /api/tailscale response below.
  clearNuxtData()
  tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: null }
})

describe('channels page — Slack guidance (JCLAW-83) + Tailscale Funnel (JCLAW-84)', () => {
  it('reveals the Events API request URL and setup steps when configuring Slack', async () => {
    const component = await mountSuspended(Channels)

    // channelTypes = [slack, whatsapp]; the first "Configure" button is Slack.
    const slackButton = component.findAll('button').find(b => b.text() === 'Configure')
    expect(slackButton).toBeDefined()
    await slackButton?.trigger('click')

    const text = component.text()
    expect(text).toContain('/api/webhooks/slack')
    expect(text).toContain('Event Subscriptions')
    expect(text).toContain('Bot token')
    expect(text).toContain('xoxb-')
    // Public-HTTPS requirement + the localhost caveat (no funnel here).
    expect(text).toContain('public HTTPS')
    expect(text).toContain('tunnel')
    // JCLAW-13: grouped, transcribable permissions + the DM path.
    expect(text).toContain('chat:write')
    expect(text).toContain('im:history')
    expect(text).toContain('message.im')
    expect(text).toContain('Messages Tab')
    // JCLAW-341: Assistant feature + scope for the native typing indicator + streaming.
    expect(text).toContain('assistant:write')
    expect(text).toContain('Assistant')
    // JCLAW-13: the green transcribable values each get a copy-to-clipboard button
    // (Request URL + every scope/event/setting).
    const copyButtons = component.findAll('button')
      .filter(b => (b.attributes('aria-label') ?? '').startsWith('Copy'))
    expect(copyButtons.length).toBeGreaterThan(5)
  })

  it('shows the app-level Tailscale Funnel toggle', async () => {
    const component = await mountSuspended(Channels)
    const text = component.text()
    expect(text).toContain('Tailscale Funnel')
    expect(text).toContain('Enable Funnel')
  })

  it('disables Enable Funnel while Tailscale is unavailable', async () => {
    tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: 'Tailscale is installed but not connected (state: Stopped)' }
    const component = await mountSuspended(Channels)
    const button = component.findAll('button').find(b => b.text() === 'Enable Funnel')
    expect(button?.attributes('disabled')).toBeDefined()
  })

  it('enables Enable Funnel when Tailscale is installed and connected', async () => {
    tailscaleResponse = { enabled: false, available: true, publicUrl: null, error: null }
    const component = await mountSuspended(Channels)
    const button = component.findAll('button').find(b => b.text() === 'Enable Funnel')
    expect(button?.attributes('disabled')).toBeUndefined()
  })

  it('keeps Disable Funnel clickable even when Tailscale becomes unavailable', async () => {
    tailscaleResponse = { enabled: true, available: false, publicUrl: null, error: 'tailscale CLI not available or tailscaled not running' }
    const component = await mountSuspended(Channels)
    const button = component.findAll('button').find(b => b.text() === 'Disable Funnel')
    expect(button?.attributes('disabled')).toBeUndefined()
  })

  it('uses the funnel public URL as the Slack request URL when the funnel is active', async () => {
    tailscaleResponse = { enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }
    const component = await mountSuspended(Channels)

    const slackButton = component.findAll('button').find(b => b.text() === 'Configure')
    await slackButton?.trigger('click')

    const text = component.text()
    expect(text).toContain('Set this as the Request URL')
    expect(text).toContain('via Tailscale Funnel')
    expect(text).toContain('https://jclaw.tnet.ts.net/api/webhooks/slack')
  })
})
