import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-236 — Video Generation settings section + jobs panel. Toggle gating on the Replicate API key,
 * the Replicate backend radio + model/timeout inputs, the maxJobMinutes persist round-trip, and the
 * recent-jobs panel render. Replicate-only (SV-1); self-hosted is a disabled "coming soon" placeholder.
 */

const MODELS = '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535}]'

function setupApi(opts?: {
  capturePost?: (b: { key?: string, value?: string }) => void
  extraEntries?: Array<{ key: string, value: string }>
  recentJobs?: unknown[]
}) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/providers', () => [
    { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20, supportedModalities: ['SUBSCRIPTION'] },
  ])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/transcription/state', () => ({ provider: 'whisper-local', localModel: 'small.en', ffmpegAvailable: true, ffmpegReason: 'available', models: [] }))
  registerEndpoint('/api/providers/vllm/reachable', () => ({ provider: 'vllm', reachable: false, modelCount: 0, reason: 'vllm not running' }))
  registerEndpoint('/api/providers/openrouter/video-models', () => ({ provider: 'openrouter', models: [], count: 0 }))
  registerEndpoint('/api/videogen/jobs/recent', () => opts?.recentJobs ?? [])
  const base = [
    { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
    { key: 'provider.ollama-cloud.apiKey', value: 'sk-cloud-****' },
    { key: 'provider.ollama-cloud.models', value: MODELS },
    ...(opts?.extraEntries ?? []),
  ]
  registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: base }) })
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      opts?.capturePost?.(await readBody(event) as { key?: string, value?: string })
      return { ok: true }
    },
  })
}

describe('Settings — Video Generation (JCLAW-236)', () => {
  beforeEach(() => clearNuxtData())

  it('renders the section; the enable toggle is disabled when no Replicate key is set', async () => {
    setupApi()
    const c = await mountSuspended(Settings)
    await flushPromises()

    expect(c.text()).toContain('Video Generation')
    const toggle = c.find<HTMLButtonElement>('button[aria-label="Enable video generation"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.element.disabled).toBe(true)
  })

  it('with a Replicate key + provider set, shows the checked Replicate radio and the model/timeout inputs', async () => {
    setupApi({ extraEntries: [
      { key: 'provider.replicate.apiKey', value: 'r8_****' },
      { key: 'videogen.provider', value: 'replicate' },
      { key: 'videogen.maxJobMinutes', value: '45' },
      { key: 'videogen.cloud.model', value: 'lightricks/ltx-video' },
    ] })
    const c = await mountSuspended(Settings)
    await flushPromises()

    const radio = c.find<HTMLInputElement>('#videogen-provider-replicate')
    expect(radio.exists()).toBe(true)
    expect(radio.element.disabled).toBe(false)
    expect(radio.element.checked).toBe(true)

    const model = c.find<HTMLInputElement>('input[aria-label="Replicate video model"]')
    expect(model.element.value).toBe('lightricks/ltx-video')
    const timeout = c.find<HTMLInputElement>('input[aria-label="Video job timeout in minutes"]')
    expect(timeout.element.value).toBe('45')
  })

  it('POSTs videogen.maxJobMinutes on change', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b), extraEntries: [
      { key: 'provider.replicate.apiKey', value: 'r8_****' },
      { key: 'videogen.provider', value: 'replicate' },
      { key: 'videogen.maxJobMinutes', value: '30' },
    ] })
    const c = await mountSuspended(Settings)
    await flushPromises()

    const timeout = c.find('input[aria-label="Video job timeout in minutes"]')
    await timeout.setValue('15')
    await timeout.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'videogen.maxJobMinutes')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('15')
  })

  it('renders recent jobs in the jobs panel with a link into the conversation', async () => {
    setupApi({ recentJobs: [
      { id: 7, state: 'SUCCEEDED', prompt: 'a comet over a city', percent: null, errorMessage: null, conversationId: 42, createdAt: '2026-06-25T10:00:00Z' },
    ] })
    const c = await mountSuspended(Settings)
    await flushPromises()

    expect(c.text()).toContain('Recent video jobs')
    expect(c.text()).toContain('a comet over a city')
    expect(c.text()).toContain('SUCCEEDED')
    expect(c.find('a[href="/chat?conversation=42"]').exists()).toBe(true)
  })
})
