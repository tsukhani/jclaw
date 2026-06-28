import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
import Slack from '~/pages/channels/slack.vue'

// JCLAW-441: per-agent Slack bindings. The Events API Request URL is base +
// /api/webhooks/slack/{id} — operator-pasted (not auto-registered like Telegram).
// The base pre-fills from the Tailscale Funnel (or a public origin), else blank.

const AGENT = { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }

function binding(overrides: Record<string, unknown> = {}) {
  return {
    id: 7,
    agentId: 1,
    agentName: 'main',
    ownerUserId: null,
    transport: 'HTTP',
    webhookBaseUrl: null,
    effectiveRequestUrl: null,
    hasSigningSecret: true,
    hasAppToken: false,
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
let tailscaleResponse: Record<string, unknown> = {}
// The next probe response the test endpoint returns (Slack auth.test shape).
let probeResponse: Record<string, unknown> = {}

registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/channels/slack/bindings', () => bindingsResponse)
registerEndpoint('/api/tailscale', () => tailscaleResponse)
registerEndpoint('/api/channels/slack/bindings/7/test', { method: 'POST', handler: () => probeResponse })

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  bindingsResponse = []
  tailscaleResponse = { enabled: true, available: true, publicUrl: 'https://jclaw.tnet.ts.net', error: null }
  probeResponse = {}
})

describe('slack bindings page — Request URL + setup (JCLAW-441)', () => {
  it('shows the Events API Request URL with a copy button on a saved binding card', async () => {
    bindingsResponse = [binding({
      id: 7,
      webhookBaseUrl: 'https://jclaw.tnet.ts.net',
      effectiveRequestUrl: 'https://jclaw.tnet.ts.net/api/webhooks/slack/7',
      teamId: 'T123',
      botUserId: 'UBOT',
    })]
    const c = await mountSuspended(Slack)
    expect(c.text()).toContain('https://jclaw.tnet.ts.net/api/webhooks/slack/7')
    const copyBtn = c.findAll('button')
      .find(b => (b.attributes('aria-label') ?? '').startsWith('Copy request URL'))
    expect(copyBtn).toBeDefined()
    // JCLAW-350: the interactivity URL (Events URL + /interactive) also appears,
    // with its own copy button, for wiring Slack's Interactivity & Shortcuts.
    expect(c.text()).toContain('https://jclaw.tnet.ts.net/api/webhooks/slack/7/interactive')
    const interactiveCopy = c.findAll('button')
      .find(b => (b.attributes('aria-label') ?? '').startsWith('Copy interactivity URL'))
    expect(interactiveCopy).toBeDefined()
  })

  it('pre-fills the public base and builds the full Request URL when editing (funnel live)', async () => {
    bindingsResponse = [binding({ id: 7, webhookBaseUrl: null })]
    const c = await mountSuspended(Slack)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    // Base pre-filled from the funnel; full URL built with the binding id.
    const base = c.find('#binding-webhook-base').element as HTMLInputElement
    expect(base.value).toBe('https://jclaw.tnet.ts.net')
    expect(c.text()).toContain('https://jclaw.tnet.ts.net/api/webhooks/slack/7')
  })

  it('prompts for a public URL when the funnel is off and the origin is not public', async () => {
    tailscaleResponse = { enabled: false, available: false, publicUrl: null, error: null }
    bindingsResponse = [binding({ id: 7, webhookBaseUrl: null })]
    const c = await mountSuspended(Slack)
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    const base = c.find('#binding-webhook-base').element as HTMLInputElement
    expect(base.value).toBe('') // jsdom origin is http://localhost → not public
    expect(c.text()).toContain('Enter your public HTTPS URL')
  })

  it('renders the Slack setup guidance (scopes + events) and secret fields on create', async () => {
    const c = await mountSuspended(Slack)
    const newBtn = c.findAll('button').find(b => b.text() === '+ New binding')
    expect(newBtn).toBeTruthy()
    await newBtn!.trigger('click')
    await nextTick()
    const text = c.text()
    expect(text).toContain('chat:write')
    expect(text).toContain('assistant:write')
    expect(text).toContain('files:read')
    expect(text).toContain('files:write')
    expect(text).toContain('message.im')
    expect(text).toContain('message.channels')
    // Both required secret fields are present on create.
    expect(c.find('#binding-bot-token').exists()).toBe(true)
    expect(c.find('#binding-signing-secret').exists()).toBe(true)
    // JCLAW-350: the optional approver-user-id field + the Interactivity setup step.
    expect(c.find('#binding-owner-user-id').exists()).toBe(true)
    expect(text).toContain('Interactivity')
    // JCLAW-349: the lifecycle-command note (! prefix, since Slack blocks / in threads).
    expect(text).toContain('!reset')
  })

  it('shows the owner user id on the card when set', async () => {
    bindingsResponse = [binding({ id: 7, ownerUserId: 'UOWNER123', botUserId: 'UBOT' })]
    const c = await mountSuspended(Slack)
    expect(c.text()).toContain('Owner')
    expect(c.text()).toContain('UOWNER123')
  })

  it('requires an owner for a main-agent binding and blocks save until set', async () => {
    // AGENT (id 1) is the main agent; its binding has no owner.
    bindingsResponse = [binding({ id: 7, ownerUserId: null })]
    const c = await mountSuspended(Slack)
    await flushPromises() // funnel status is lazy now — let it resolve before interacting
    await c.find('[aria-label="Edit binding"]').trigger('click')
    await nextTick()
    // The warning shows and Save is disabled while the owner is blank.
    expect(c.text()).toContain('full filesystem and shell access')
    const saveBtn = c.findAll('button').find(b => b.text() === 'Save')
    expect(saveBtn!.attributes('disabled')).toBeDefined()
    // Entering an owner clears the block.
    await c.find('#binding-owner-user-id').setValue('UOWNER123')
    await nextTick()
    expect(saveBtn!.attributes('disabled')).toBeUndefined()
  })

  it('switches to Socket Mode: app token replaces the signing secret + public URL (JCLAW-351)', async () => {
    const c = await mountSuspended(Slack)
    await c.findAll('button').find(b => b.text() === '+ New binding')!.trigger('click')
    await nextTick()
    // HTTP default: signing secret + public URL present, no app token.
    expect(c.find('#binding-signing-secret').exists()).toBe(true)
    expect(c.find('#binding-webhook-base').exists()).toBe(true)
    expect(c.find('#binding-app-token').exists()).toBe(false)
    // Switch to Socket Mode.
    await c.find('#binding-transport').setValue('SOCKET')
    await nextTick()
    expect(c.find('#binding-app-token').exists()).toBe(true)
    expect(c.find('#binding-signing-secret').exists()).toBe(false)
    expect(c.find('#binding-webhook-base').exists()).toBe(false)
  })

  it('shows the transport on the card (JCLAW-351)', async () => {
    bindingsResponse = [binding({ id: 7, transport: 'SOCKET' })]
    const c = await mountSuspended(Slack)
    expect(c.text()).toContain('Socket Mode')
  })
})

describe('slack bindings page — health probe (JCLAW-441)', () => {
  it('renders an ok probe result with the bot user + team after clicking Test', async () => {
    bindingsResponse = [binding({ id: 7 })]
    probeResponse = { ok: true, botUserId: 'UBOTOK', teamId: 'T1', teamName: 'Acme', error: null }
    const c = await mountSuspended(Slack)
    // No result before the probe runs.
    expect(c.find('[data-testid="probe-result-7"]').exists()).toBe(false)
    await c.find('[title^="Test binding"]').trigger('click')
    await flushPromises()
    await nextTick()
    const result = c.find('[data-testid="probe-result-7"]')
    expect(result.exists()).toBe(true)
    expect(result.text()).toContain('OK')
    expect(result.text()).toContain('UBOTOK')
    expect(result.text()).toContain('Acme')
  })

  it('renders the error reason when the probe reports not-ok', async () => {
    bindingsResponse = [binding({ id: 7 })]
    probeResponse = { ok: false, botUserId: null, teamId: null, teamName: null, error: 'invalid_auth' }
    const c = await mountSuspended(Slack)
    await c.find('[title^="Test binding"]').trigger('click')
    await flushPromises()
    await nextTick()
    const result = c.find('[data-testid="probe-result-7"]')
    expect(result.exists()).toBe(true)
    expect(result.text()).toContain('invalid_auth')
  })
})
