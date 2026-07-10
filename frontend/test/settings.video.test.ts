import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-223 — Video Interpretation settings section. Render + persist round-trip
 * for the "Frames sampled per video" knob, plus the read-only tier display computed
 * from the default ("main") agent's model capabilities.
 */

const PROVIDERS = [
  { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20, supportedModalities: ['SUBSCRIPTION'] },
]
const TRANSCRIPTION = { provider: 'whisper-local', localModel: 'small.en', ffmpegAvailable: true, ffmpegReason: 'available', models: [] }
const OCR = { providers: [] }

// Default main-agent model (kimi-k2.5) declares neither vision nor video → Tier 3.
const MODELS_TEXT_ONLY = '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535}]'

function configEntries(videoFrames: string | undefined, modelsJson: string, extra?: Array<{ key: string, value: string }>) {
  const base = [
    { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1', updatedAt: '2026-06-19T10:00:00Z' },
    { key: 'provider.ollama-cloud.apiKey', value: 'sk-cloud-****', updatedAt: '2026-06-19T10:00:00Z' },
    { key: 'provider.ollama-cloud.models', value: modelsJson, updatedAt: '2026-06-19T10:00:00Z' },
  ]
  if (videoFrames !== undefined) {
    base.push({ key: 'video.sampleFrames', value: videoFrames, updatedAt: '2026-06-19T10:00:00Z' })
  }
  for (const e of extra ?? []) base.push({ ...e, updatedAt: '2026-06-19T10:00:00Z' })
  return base
}

function setupApi(opts?: { capturePost?: (b: { key?: string, value?: string }) => void, videoFrames?: string, models?: string, agentModelId?: string, extraEntries?: Array<{ key: string, value: string }>, vllmReachable?: boolean, videoModels?: Array<{ id: string, name: string }> }) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: opts?.agentModelId ?? 'kimi-k2.5', enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/providers', () => PROVIDERS)
  registerEndpoint('/api/ocr/status', () => OCR)
  registerEndpoint('/api/transcription/state', () => TRANSCRIPTION)
  registerEndpoint('/api/providers/vllm/reachable', () => ({
    provider: 'vllm',
    reachable: opts?.vllmReachable ?? false,
    modelCount: opts?.vllmReachable ? 1 : 0,
    reason: opts?.vllmReachable ? null : 'vllm not running',
  }))
  registerEndpoint('/api/providers/openrouter/video-models', () => ({
    provider: 'openrouter',
    models: opts?.videoModels ?? [],
    count: (opts?.videoModels ?? []).length,
  }))
  registerEndpoint('/api/config', {
    method: 'GET',
    handler: () => ({ entries: configEntries(opts?.videoFrames, opts?.models ?? MODELS_TEXT_ONLY, opts?.extraEntries) }),
  })
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as { key?: string, value?: string }
      opts?.capturePost?.(body)
      return { ok: true }
    },
  })
}

/**
 * Mount Settings and open a specific section. The page renders one section at a
 * time (`<component :is>` swap), so tests must activate their section before
 * asserting on its DOM. Setting activeSectionId drives the swap; the double
 * flush settles the freshly-mounted panel's async setup + <Suspense>.
 */
async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Video Interpretation (JCLAW-223)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders the section, the frames input at its configured value, and the default model tier', async () => {
    setupApi({ videoFrames: '8' })
    const c = await mountSettingsSection('video-interpretation')

    expect(c.text()).toContain('Video Interpretation')
    const input = c.find<HTMLInputElement>('input[aria-label="Max frames per video"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('8')
    // kimi-k2.5 declares neither vision nor video → Tier 3 (text summary).
    expect(c.text()).toContain('text-only')
    expect(c.text()).toContain('text summary')
  })

  it('shows Tier 2 when the default model supports vision but not video', async () => {
    setupApi({ models: '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535,"supportsVision":true}]' })
    const c = await mountSettingsSection('video-interpretation')
    expect(c.text()).toContain('supports vision')
    expect(c.text()).toContain('still images')
  })

  it('shows Tier 1 when the default model supports video natively (any video-capable model)', async () => {
    // Any model with supportsVideo (e.g. Gemini, here also kimi for the test) → native video_url.
    // No Qwen-family special-casing anymore.
    setupApi({ models: '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535,"supportsVision":true,"supportsVideo":true}]' })
    const c = await mountSettingsSection('video-interpretation')
    expect(c.text()).toContain('supports native video')
  })

  it('POSTs video.sampleFrames on change', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b), videoFrames: '8' })
    const c = await mountSettingsSection('video-interpretation')

    const input = c.find('input[aria-label="Max frames per video"]')
    await input.setValue('16')
    await input.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'video.sampleFrames')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('16')
  })

  it('renders the seconds-per-frame input and POSTs video.secondsPerFrame on change', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b), extraEntries: [{ key: 'video.secondsPerFrame', value: '10' }] })
    const c = await mountSettingsSection('video-interpretation')

    const input = c.find<HTMLInputElement>('input[aria-label="Seconds per frame"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('10')

    await input.setValue('2')
    await input.trigger('change')
    await flushPromises()
    const hit = captured.find(b => b.key === 'video.secondsPerFrame')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('2')
  })

  it('clamps seconds-per-frame to the 60 ceiling', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('video-interpretation')

    const input = c.find('input[aria-label="Seconds per frame"]')
    await input.setValue('999')
    await input.trigger('change')
    await flushPromises()
    const hit = captured.find(b => b.key === 'video.secondsPerFrame')
    expect(hit!.value).toBe('60')
  })

  it('clamps an out-of-range value to the 32 ceiling', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b), videoFrames: '8' })
    const c = await mountSettingsSection('video-interpretation')

    const input = c.find('input[aria-label="Max frames per video"]')
    await input.setValue('99')
    await input.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'video.sampleFrames')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('32')
  })

  it('populates the video-model dropdown with live-discovered video models from OpenRouter', async () => {
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'openrouter' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or-****' },
      ],
      videoModels: [
        { id: 'google/gemini-2.5-flash', name: 'Gemini 2.5 Flash' },
        { id: 'qwen/qwen3.5-flash', name: 'Qwen3.5 Flash' },
      ],
    })
    const c = await mountSettingsSection('video-interpretation')
    // Video-model discovery is a $fetch fired from an immediate watcher (and
    // re-fired once the vLLM probe resolves), so it settles a couple of ticks
    // after the panel mounts — drain them before reading the resolved dropdown.
    await flushPromises()
    await flushPromises()
    const select = c.find('select[aria-label="Video model"]')
    expect(select.exists()).toBe(true)
    const opts = select.findAll('option').map(o => o.text())
    expect(opts.join(' | ')).toContain('Gemini 2.5 Flash')
    expect(opts.join(' | ')).toContain('Qwen3.5 Flash')
  })

  it('shows an empty-state hint when the provider has no video models', async () => {
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'openrouter' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or-****' },
      ],
      videoModels: [],
    })
    const c = await mountSettingsSection('video-interpretation')
    // Discovery $fetch settles a couple of ticks after mount (see above).
    await flushPromises()
    await flushPromises()
    expect(c.text()).toContain('No video-capable models found on openrouter')
  })

  it('enables the vLLM video radio only when vLLM is reachable', async () => {
    // Dedicated model on (video.provider set) + vLLM base URL configured + probe says reachable.
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'openrouter' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or-****' },
        { key: 'provider.vllm.baseUrl', value: 'http://localhost:8000/v1' },
      ],
      vllmReachable: true,
    })
    const c = await mountSettingsSection('video-interpretation')
    // The vLLM reachability probe is a $fetch from an immediate watcher; let it
    // resolve (and re-run the model discovery it gates) before asserting.
    await flushPromises()
    await flushPromises()
    const radio = c.find<HTMLInputElement>('#video-provider-vllm')
    expect(radio.exists()).toBe(true)
    expect(radio.element.disabled).toBe(false)
    expect(c.text()).toContain('reachable')
  })

  it('disables the vLLM video radio with a "not reachable" hint when vLLM is down', async () => {
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'openrouter' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or-****' },
        { key: 'provider.vllm.baseUrl', value: 'http://localhost:8000/v1' },
      ],
      vllmReachable: false,
    })
    const c = await mountSettingsSection('video-interpretation')
    const radio = c.find<HTMLInputElement>('#video-provider-vllm')
    expect(radio.element.disabled).toBe(true)
    expect(c.text()).toContain('not reachable')
  })

  it('offers Ollama local/cloud video radios, enabled when their base URL is configured', async () => {
    // ollama-cloud baseUrl is seeded by the base config; we add ollama-local.baseUrl → both enabled.
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'openrouter' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or-****' },
        { key: 'provider.ollama-local.baseUrl', value: 'http://localhost:11434/v1' },
      ],
    })
    const c = await mountSettingsSection('video-interpretation')
    const local = c.find<HTMLInputElement>('#video-provider-ollama-local')
    const cloud = c.find<HTMLInputElement>('#video-provider-ollama-cloud')
    expect(local.exists()).toBe(true)
    expect(cloud.exists()).toBe(true)
    expect(local.element.disabled).toBe(false)
    expect(cloud.element.disabled).toBe(false)
  })

  it('disables the Ollama local video radio when its base URL is not configured', async () => {
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'openrouter' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or-****' },
      ],
    })
    const c = await mountSettingsSection('video-interpretation')
    const local = c.find<HTMLInputElement>('#video-provider-ollama-local')
    expect(local.exists()).toBe(true)
    expect(local.element.disabled).toBe(true)
  })

  it('loads the video-model list for ollama-local when it is the selected provider', async () => {
    registerEndpoint('/api/providers/ollama-local/video-models', () => ({
      provider: 'ollama-local',
      models: [{ id: 'qwen3-vl:8b', name: 'qwen3-vl:8b' }],
      count: 1,
    }))
    setupApi({
      extraEntries: [
        { key: 'video.provider', value: 'ollama-local' },
        { key: 'provider.ollama-local.baseUrl', value: 'http://localhost:11434/v1' },
      ],
    })
    const c = await mountSettingsSection('video-interpretation')
    // Discovery $fetch settles a couple of ticks after mount (see above).
    await flushPromises()
    await flushPromises()
    const select = c.find('select[aria-label="Video model"]')
    expect(select.exists()).toBe(true)
    const opts = select.findAll('option').map(o => o.text())
    expect(opts.join(' | ')).toContain('qwen3-vl:8b')
  })
})
