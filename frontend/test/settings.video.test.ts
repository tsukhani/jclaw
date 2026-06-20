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

function configEntries(videoFrames: string | undefined, modelsJson: string) {
  const base = [
    { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1', updatedAt: '2026-06-19T10:00:00Z' },
    { key: 'provider.ollama-cloud.apiKey', value: 'sk-cloud-****', updatedAt: '2026-06-19T10:00:00Z' },
    { key: 'provider.ollama-cloud.models', value: modelsJson, updatedAt: '2026-06-19T10:00:00Z' },
  ]
  if (videoFrames !== undefined) {
    base.push({ key: 'video.sampleFrames', value: videoFrames, updatedAt: '2026-06-19T10:00:00Z' })
  }
  return base
}

function setupApi(opts?: { capturePost?: (b: { key?: string, value?: string }) => void, videoFrames?: string, models?: string, agentModelId?: string }) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: opts?.agentModelId ?? 'kimi-k2.5', enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/providers', () => PROVIDERS)
  registerEndpoint('/api/ocr/status', () => OCR)
  registerEndpoint('/api/transcription/state', () => TRANSCRIPTION)
  registerEndpoint('/api/config', {
    method: 'GET',
    handler: () => ({ entries: configEntries(opts?.videoFrames, opts?.models ?? MODELS_TEXT_ONLY) }),
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

describe('Settings page — Video Interpretation (JCLAW-223)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders the section, the frames input at its configured value, and the default model tier', async () => {
    setupApi({ videoFrames: '8' })
    const c = await mountSuspended(Settings)
    await flushPromises()

    expect(c.text()).toContain('Video Interpretation')
    const input = c.find<HTMLInputElement>('input[aria-label="Frames sampled per video"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('8')
    // kimi-k2.5 declares neither vision nor video → Tier 3 (text summary).
    expect(c.text()).toContain('text-only')
    expect(c.text()).toContain('text summary')
  })

  it('shows Tier 2 when the default model supports vision but not video', async () => {
    setupApi({ models: '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535,"supportsVision":true}]' })
    const c = await mountSuspended(Settings)
    await flushPromises()
    expect(c.text()).toContain('supports vision')
    expect(c.text()).toContain('still images')
  })

  it('shows Tier 1 when the default model is a Qwen-VL model with native video', async () => {
    setupApi({ agentModelId: 'qwen3-vl', models: '[{"id":"qwen3-vl","name":"Qwen3 VL","contextWindow":262144,"maxTokens":65535,"supportsVision":true,"supportsVideo":true}]' })
    const c = await mountSuspended(Settings)
    await flushPromises()
    expect(c.text()).toContain('supports native video')
  })

  it('falls back to frames-as-images when the model claims video but is not Qwen (e.g. Gemini)', async () => {
    // The bug case: kimi-k2.5 advertises supportsVideo but can't ingest our Qwen video part, so it
    // must degrade to vision (frames as images), not native video.
    setupApi({ models: '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535,"supportsVision":true,"supportsVideo":true}]' })
    const c = await mountSuspended(Settings)
    await flushPromises()
    expect(c.text()).toContain('still images')
    expect(c.text()).not.toContain('supports native video')
  })

  it('POSTs video.sampleFrames on change', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b), videoFrames: '8' })
    const c = await mountSuspended(Settings)
    await flushPromises()

    const input = c.find('input[aria-label="Frames sampled per video"]')
    await input.setValue('16')
    await input.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'video.sampleFrames')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('16')
  })

  it('clamps an out-of-range value to the 32 ceiling', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ capturePost: b => captured.push(b), videoFrames: '8' })
    const c = await mountSuspended(Settings)
    await flushPromises()

    const input = c.find('input[aria-label="Frames sampled per video"]')
    await input.setValue('99')
    await input.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'video.sampleFrames')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('32')
  })
})
