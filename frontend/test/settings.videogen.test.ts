import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-236 — Video Generation settings section. Toggle gating on the Replicate API key, the Replicate
 * backend radio, the maxJobMinutes persist round-trip, and the model dropdown — which is populated from
 * GET /api/videogen/models (Replicate's curated text-to-video collection) with no free-text entry, and
 * still surfaces a saved model that discovery didn't return. Replicate-only (SV-1); self-hosted is a
 * disabled "coming soon" placeholder.
 */

const MODELS = '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535}]'

function setupApi(opts?: {
  capturePost?: (b: { key?: string, value?: string }) => void
  extraEntries?: Array<{ key: string, value: string }>
  videoModels?: Array<{ slug: string, name: string, description: string | null }>
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
  registerEndpoint('/api/videogen/models', () => opts?.videoModels ?? [])
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

  it('with a Replicate key + provider set, shows the checked radio, the model select, and the timeout', async () => {
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

    // The model field is a <select>, not a text input; the saved value is surfaced even though
    // discovery (mocked empty here) didn't return it.
    const model = c.find<HTMLSelectElement>('select[aria-label="Replicate video model"]')
    expect(model.exists()).toBe(true)
    expect(model.element.value).toBe('lightricks/ltx-video')
    const timeout = c.find<HTMLInputElement>('input[aria-label="Video job timeout in minutes"]')
    expect(timeout.element.value).toBe('45')
  })

  it('populates the model dropdown from discovered Replicate models', async () => {
    setupApi({
      videoModels: [
        { slug: 'wan-video/wan-2.2-t2v-fast', name: 'wan-2.2-t2v-fast', description: 'Fast WAN 2.2' },
        { slug: 'lightricks/ltx-video', name: 'ltx-video', description: 'LTX' },
      ],
      extraEntries: [
        { key: 'provider.replicate.apiKey', value: 'r8_****' },
        { key: 'videogen.provider', value: 'replicate' },
      ],
    })
    const c = await mountSuspended(Settings)
    await flushPromises()

    const select = c.find('select[aria-label="Replicate video model"]')
    expect(select.exists()).toBe(true)
    const optionValues = select.findAll('option').map(o => (o.element as HTMLOptionElement).value)
    expect(optionValues).toContain('wan-video/wan-2.2-t2v-fast')
    expect(optionValues).toContain('lightricks/ltx-video')
    // The operator-facing jobs panel does not belong in Settings and was removed.
    expect(c.text()).not.toContain('Recent video jobs')
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

  it('POSTs videogen.cloud.model when a model is chosen from the dropdown', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b),
      videoModels: [
        { slug: 'wan-video/wan-2.2-t2v-fast', name: 'wan-2.2-t2v-fast', description: 'Fast WAN 2.2' },
      ],
      extraEntries: [
        { key: 'provider.replicate.apiKey', value: 'r8_****' },
        { key: 'videogen.provider', value: 'replicate' },
      ] })
    const c = await mountSuspended(Settings)
    await flushPromises()

    const select = c.find('select[aria-label="Replicate video model"]')
    await select.setValue('wan-video/wan-2.2-t2v-fast')
    await flushPromises()

    const hit = captured.find(b => b.key === 'videogen.cloud.model')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('wan-video/wan-2.2-t2v-fast')
  })
})
