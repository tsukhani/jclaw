import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus, type H3Event } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

// A select or input that saves on change must show the saved value and the reason when its save fails
// (JCLAW-1221). Several of these handlers had no catch and sat in panels that never re-render on failure,
// so the refused value stayed on screen as if it had been saved.

let unregister: Array<() => void> = []

function register(url: string, handler: Parameters<typeof registerEndpoint>[1]) {
  unregister.push(registerEndpoint(url, handler))
}

function badGateway(event: H3Event) {
  setResponseStatus(event, 502)
  return '<html><body>Bad Gateway</body></html>'
}

const openaiModels = JSON.stringify([
  { id: 'gpt-4o', name: 'GPT-4o', supportsVision: true },
  { id: 'gpt-4.1', name: 'GPT-4.1', supportsVision: true },
])
const openrouterAudioModels = JSON.stringify([
  { id: 'audio/one', name: 'Audio One', supportsAudio: true },
  { id: 'audio/two', name: 'Audio Two', supportsAudio: true },
])

function setupApi(entries: Record<string, string>) {
  register('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'openai', modelId: 'gpt-4o', enabled: true, isMain: true, providerConfigured: true },
  ])
  register('/api/channels', () => [])
  register('/api/providers', () => [
    { name: 'openai', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0, supportedModalities: ['PER_TOKEN'] },
  ])
  register('/api/ocr/status', () => ({ providers: [] }))
  register('/api/transcription/state', () => ({
    provider: 'whisper-local',
    localModel: 'small.en',
    ffmpegAvailable: true,
    ffmpegReason: 'available',
    models: [
      { id: 'base.en', displayName: 'Base (English)', approxSizeMb: 57, status: 'AVAILABLE', bytesDownloaded: 1, totalBytes: 1, error: null },
      { id: 'small.en', displayName: 'Small (English)', approxSizeMb: 190, status: 'AVAILABLE', bytesDownloaded: 1, totalBytes: 1, error: null },
    ],
  }))
  register('/api/transcription/diarization/models', () => ({
    models: [
      { role: 'diarizer', repo: 'pyannote/diarizer', displayName: 'Diarizer', status: 'AVAILABLE', bytesDownloaded: 1, totalBytes: 1, error: null },
      { role: 'emotion', repo: 'ser/one', displayName: 'SER One', status: 'AVAILABLE', bytesDownloaded: 1, totalBytes: 1, error: null },
    ],
    serOptions: [{ repo: 'ser/one', displayName: 'SER One' }, { repo: 'ser/two', displayName: 'SER Two' }],
  }))
  register('/api/tts/state', () => ({
    engine: entries['tts.engine'] ?? 'sidecar',
    referenceVoice: null,
    engines: [
      { id: 'sidecar', displayName: 'Sidecar', available: true, status: 'ready', model: 'kokoro', models: [
        { id: 'kokoro', displayName: 'Kokoro-82M', approxSizeMb: 330, present: true, downloading: false, voices: [{ id: 'af_bella', label: 'Bella' }, { id: 'bm_george', label: 'George' }], supportsCloning: false },
      ] },
      { id: 'jvm', displayName: 'JVM', available: true, status: 'ready', model: 'piper-en_US-amy-low', models: [
        { id: 'piper-en_US-amy-low', displayName: 'Piper Amy', approxSizeMb: 65, present: true, downloading: false, voices: [] },
        { id: 'kokoro-multi-lang-v1_0', displayName: 'Kokoro multilingual', approxSizeMb: 720, present: true, downloading: false, voices: [] },
      ] },
    ],
  }))
  register('/api/imagegen/models', () => [
    { slug: 'image/one', name: 'Image One', description: null },
    { slug: 'image/two', name: 'Image Two', description: null },
  ])
  register('/api/videogen/models', () => [
    { slug: 'video/one', name: 'Video One', description: null },
    { slug: 'video/two', name: 'Video Two', description: null },
  ])
  register('/api/providers/openrouter/video-models', () => ({ provider: 'openrouter', models: [{ id: 'vm/one', name: 'VM One' }, { id: 'vm/two', name: 'VM Two' }], count: 2 }))
  register('/api/providers/vllm/reachable', () => ({ provider: 'vllm', reachable: false, modelCount: 0, reason: 'not running' }))
  register('/api/config', {
    method: 'GET',
    handler: () => ({ entries: Object.entries(entries).map(([key, value]) => ({ key, value, updatedAt: '2026-09-17T10:00:00Z' })) }),
  })
  register('/api/config', { method: 'POST', handler: badGateway })
  register('/api/config/subagent.modelProvider', { method: 'DELETE', handler: badGateway })
}

async function mountSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings — a select or input whose save fails shows the saved value and why (JCLAW-1221)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  afterEach(() => {
    unregister.forEach(off => off())
    unregister = []
  })

  it.each<{ name: string, section: string, control: string, choose: string, saved: string, entries: Record<string, string> }>([
    { name: 'image captioning model', section: 'image-caption', control: 'select[aria-label="Caption cloud model"]', choose: 'gpt-4.1', saved: 'gpt-4o',
      entries: { 'caption.provider': 'openai', 'caption.model': 'gpt-4o', 'provider.openai.apiKey': 'sk-****', 'provider.openai.models': openaiModels } },
    { name: 'search recency filter', section: 'search', control: 'select[aria-label="Recency filter"]', choose: 'week', saved: 'month',
      entries: { 'search.perplexity.enabled': 'true', 'search.perplexity.apiKey': 'pplx-****', 'search.perplexity.recencyFilter': 'month' } },
    { name: 'subagent model', section: 'subagents', control: 'select[aria-label="Subagent model"]', choose: 'openai::gpt-4.1', saved: 'openai::gpt-4o',
      entries: { 'provider.openai.apiKey': 'sk-****', 'provider.openai.models': openaiModels, 'subagent.modelProvider': 'openai', 'subagent.modelId': 'gpt-4o' } },
    { name: 'local ASR model', section: 'transcription', control: 'select[aria-label="ASR model"]', choose: 'base.en', saved: 'small.en',
      entries: { 'transcription.provider': 'whisper-local', 'transcription.localModel': 'small.en' } },
    { name: 'cloud transcription model', section: 'transcription', control: 'input[aria-label="Cloud transcription model"]', choose: 'gpt-4o-transcribe', saved: 'whisper-1',
      entries: { 'transcription.provider': 'openai', 'transcription.model': 'whisper-1', 'provider.openai.apiKey': 'sk-****' } },
    { name: 'diarization audio model', section: 'transcription', control: 'select[aria-label="Diarization audio model"]', choose: 'audio/two', saved: 'audio/one',
      entries: { 'transcription.diarization.provider': 'openrouter', 'transcription.diarization.model': 'audio/one', 'provider.openrouter.apiKey': 'sk-or-****', 'provider.openrouter.models': openrouterAudioModels } },
    { name: 'emotion (SER) model', section: 'transcription', control: 'select[aria-label="On-device emotion (SER) model"]', choose: 'ser/two', saved: 'ser/one',
      entries: { 'transcription.diarization.provider': 'pyannote-local', 'transcription.diarization.emotionModel': 'ser/one' } },
    { name: 'speech model (JVM engine)', section: 'speech', control: 'select[aria-label="Text-to-speech model"]', choose: 'kokoro-multi-lang-v1_0', saved: 'piper-en_US-amy-low',
      entries: { 'tts.engine': 'jvm', 'tts.jvm.model': 'piper-en_US-amy-low' } },
    { name: 'speech voice', section: 'speech', control: 'select[aria-label="Text-to-speech speaker voice"]', choose: 'bm_george', saved: 'af_bella',
      entries: { 'tts.engine': 'sidecar', 'tts.sidecar.model': 'kokoro', 'tts.sidecar.voice': 'af_bella' } },
    { name: 'speech keep-warm minutes', section: 'speech', control: 'input[aria-label="Minutes the TTS sidecar stays loaded while idle"]', choose: '30', saved: '15',
      entries: { 'tts.engine': 'sidecar', 'tts.sidecar.model': 'kokoro', 'tts.local.idleTimeoutMinutes': '15' } },
    { name: 'Replicate image model', section: 'image-generation', control: 'select[aria-label="Replicate image model"]', choose: 'image/two', saved: 'image/one',
      entries: { 'imagegen.provider': 'replicate', 'provider.replicate.apiKey': 'r8_****', 'imagegen.replicate.model': 'image/one' } },
    { name: 'Replicate video model', section: 'video-generation', control: 'select[aria-label="Replicate video model"]', choose: 'video/two', saved: 'video/one',
      entries: { 'videogen.provider': 'replicate', 'provider.replicate.apiKey': 'r8_****', 'videogen.cloud.model': 'video/one' } },
    { name: 'video job timeout', section: 'video-generation', control: 'input[aria-label="Video job timeout in minutes"]', choose: '45', saved: '30',
      entries: { 'videogen.provider': 'replicate', 'provider.replicate.apiKey': 'r8_****', 'videogen.maxJobMinutes': '30' } },
    { name: 'video interpretation model', section: 'video-interpretation', control: 'select[aria-label="Video model"]', choose: 'vm/two', saved: 'vm/one',
      entries: { 'video.provider': 'openrouter', 'provider.openrouter.apiKey': 'sk-or-****', 'video.model': 'vm/one' } },
    { name: 'video seconds per frame', section: 'video-interpretation', control: 'input[aria-label="Seconds per frame"]', choose: '10', saved: '5',
      entries: { 'video.secondsPerFrame': '5' } },
    { name: 'video max frames', section: 'video-interpretation', control: 'input[aria-label="Max frames per video"]', choose: '16', saved: '8',
      entries: { 'video.sampleFrames': '8' } },
  ])('$name', async ({ section, control, choose, saved, entries }) => {
    setupApi(entries)
    const component = await mountSection(section)
    await vi.waitFor(() => expect(component.find(control).exists()).toBe(true))
    await vi.waitFor(() => expect((component.find(control).element as HTMLInputElement).value).toBe(saved))
    if (control.startsWith('select')) {
      await vi.waitFor(() => expect(component.find(`${control} option[value="${choose}"]`).exists()).toBe(true))
    }

    const el = component.find(control).element as HTMLInputElement | HTMLSelectElement
    el.value = choose
    expect(el.value, `${choose} should be a value the control offers`).toBe(choose)
    await component.find(control).trigger('change')

    await vi.waitFor(() => expect(component.findAll('[data-testid="api-error"]').some(a => a.text().includes('/api/config'))).toBe(true))
    await flushPromises()
    expect((component.find(control).element as HTMLInputElement).value).toBe(saved)
  })
})

describe('Settings — a two-write save that half-lands shows what was saved (JCLAW-1221)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  afterEach(() => {
    unregister.forEach(off => off())
    unregister = []
  })

  it('subagent model whose model id write fails', async () => {
    const models = JSON.stringify([{ id: 'gpt-4o', name: 'GPT-4o' }])
    const store: Record<string, string> = {
      'provider.openai.apiKey': 'sk-****', 'provider.openai.models': models,
      'provider.ollama-cloud.apiKey': 'oc-****', 'provider.ollama-cloud.models': JSON.stringify([{ id: 'gpt-4o', name: 'GPT-4o (cloud)' }]),
      'subagent.modelProvider': 'openai', 'subagent.modelId': 'gpt-4o',
    }
    setupApi(store)
    register('/api/config', {
      method: 'GET',
      handler: () => ({ entries: Object.entries(store).map(([key, value]) => ({ key, value, updatedAt: '2026-09-17T10:00:00Z' })) }),
    })
    register('/api/config', {
      method: 'POST',
      handler: async (event) => {
        const body = await readBody(event) as { key: string, value: string }
        if (body.key === 'subagent.modelId') return badGateway(event)
        store[body.key] = body.value
        return { ok: true }
      },
    })
    const component = await mountSection('subagents')
    const control = 'select[aria-label="Subagent model"]'
    await vi.waitFor(() => expect(component.find(`${control} option[value="ollama-cloud::gpt-4o"]`).exists()).toBe(true))

    const el = component.find(control).element as HTMLSelectElement
    el.value = 'ollama-cloud::gpt-4o'
    await component.find(control).trigger('change')

    await vi.waitFor(() => expect(component.findAll('[data-testid="api-error"]').some(a => a.text().includes('/api/config'))).toBe(true))
    await flushPromises()
    // The provider write landed and the model id is unchanged, so the saved pair is now ollama-cloud::gpt-4o.
    expect((component.find(control).element as HTMLSelectElement).value).toBe('ollama-cloud::gpt-4o')
  })

  it('speech model whose voice reset fails', async () => {
    const store: Record<string, string> = { 'tts.engine': 'jvm', 'tts.jvm.model': 'piper-en_US-amy-low', 'tts.jvm.voice': '' }
    setupApi(store)
    register('/api/config', {
      method: 'GET',
      handler: () => ({ entries: Object.entries(store).map(([key, value]) => ({ key, value, updatedAt: '2026-09-17T10:00:00Z' })) }),
    })
    register('/api/config', {
      method: 'POST',
      handler: async (event) => {
        const body = await readBody(event) as { key: string, value: string }
        if (body.key === 'tts.jvm.voice') return badGateway(event)
        store[body.key] = body.value
        return { ok: true }
      },
    })
    const component = await mountSection('speech')
    const control = 'select[aria-label="Text-to-speech model"]'
    await vi.waitFor(() => expect(component.find(`${control} option[value="kokoro-multi-lang-v1_0"]`).exists()).toBe(true))

    const el = component.find(control).element as HTMLSelectElement
    el.value = 'kokoro-multi-lang-v1_0'
    await component.find(control).trigger('change')

    await vi.waitFor(() => expect(component.findAll('[data-testid="api-error"]').some(a => a.text().includes('/api/config'))).toBe(true))
    await flushPromises()
    expect((component.find(control).element as HTMLSelectElement).value).toBe('kokoro-multi-lang-v1_0')
  })
})
