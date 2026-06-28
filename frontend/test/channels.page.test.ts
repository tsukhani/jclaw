import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Channels from '~/pages/channels/index.vue'

// Mutable so individual tests can vary the Tailscale Funnel status + bindings.
let tailscaleResponse: Record<string, unknown> = { enabled: false, available: false, publicUrl: null, error: null }
let slackBindingsResponse: unknown[] = []
let whatsappBindingsResponse: unknown[] = []

registerEndpoint('/api/channels/telegram/bindings', () => [])
registerEndpoint('/api/channels/slack/bindings', () => slackBindingsResponse)
registerEndpoint('/api/channels/whatsapp/bindings', () => whatsappBindingsResponse)
registerEndpoint('/api/tailscale', () => tailscaleResponse)

beforeEach(() => {
  // useFetch caches by URL across mounts; clear it so each test re-fetches the
  // (possibly different) responses below.
  clearNuxtData()
  tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: null }
  slackBindingsResponse = []
  whatsappBindingsResponse = []
})

describe('channels page — binding-link cards (JCLAW-441/444) + Tailscale Funnel (JCLAW-84)', () => {
  it('shows Slack, Telegram and WhatsApp as per-binding link cards, not inline config', async () => {
    // JCLAW-441/444: Slack and WhatsApp joined Telegram on the per-agent binding
    // model, so each card links to its detail page with an active-count badge —
    // the old app-global inline config (Slack bot token, WhatsApp Cloud-API
    // fields) is gone from this page.
    slackBindingsResponse = [{ id: 1, enabled: true }]
    whatsappBindingsResponse = [{ id: 2, enabled: true }]
    const component = await mountSuspended(Channels)
    const text = component.text()
    expect(text).toContain('Slack')
    expect(text).toContain('Telegram')
    expect(text).toContain('WhatsApp')
    expect(text).toContain('Manage bindings →')
    // Enabled bindings are reflected as active-count badges.
    expect(text).toContain('1 active')
    // The retired app-global Slack config no longer renders on this page.
    expect(text).not.toContain('xoxb-')
    expect(text).not.toContain('chat:write')
    // Each card links to its per-binding detail page.
    const slackLink = component.findAll('a').find(a => a.attributes('href') === '/channels/slack')
    expect(slackLink).toBeDefined()
    const whatsappLink = component.findAll('a').find(a => a.attributes('href') === '/channels/whatsapp')
    expect(whatsappLink).toBeDefined()
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
    await flushPromises() // funnel status is lazy now — let it resolve before asserting
    const button = component.findAll('button').find(b => b.text() === 'Enable Funnel')
    expect(button?.attributes('disabled')).toBeDefined()
    // The resume hint only applies when the operator has the funnel switched on.
    expect(component.text()).not.toContain('will resume automatically')
  })

  it('enables Enable Funnel when Tailscale is installed and connected', async () => {
    tailscaleResponse = { enabled: false, available: true, publicUrl: null, error: null }
    const component = await mountSuspended(Channels)
    await flushPromises() // funnel status is lazy now — let it resolve before asserting
    const button = component.findAll('button').find(b => b.text() === 'Enable Funnel')
    expect(button?.attributes('disabled')).toBeUndefined()
  })

  it('keeps Disable Funnel clickable even when Tailscale becomes unavailable', async () => {
    tailscaleResponse = { enabled: true, available: false, publicUrl: null, error: 'tailscale CLI not available or tailscaled not running' }
    const component = await mountSuspended(Channels)
    await flushPromises() // funnel status is lazy now — let it resolve before asserting
    const button = component.findAll('button').find(b => b.text() === 'Disable Funnel')
    expect(button?.attributes('disabled')).toBeUndefined()
    // enabled (unavailable) is a self-healing state, not a stuck one — say so.
    expect(component.text()).toContain('The funnel will resume automatically when Tailscale reconnects.')
  })
})
