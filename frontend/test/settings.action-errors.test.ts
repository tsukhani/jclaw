import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus, type H3Event } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

// A Settings toggle, download or detect whose request fails must say so on the page (JCLAW-1221). Each
// used to await its request with no catch, so the rejection reached only the console.

let unregister: Array<() => void> = []

function register(url: string, handler: Parameters<typeof registerEndpoint>[1]) {
  unregister.push(registerEndpoint(url, handler))
}

function badGateway(event: H3Event) {
  setResponseStatus(event, 502)
  return '<html><body>Bad Gateway</body></html>'
}

function setupApi(extra: Array<{ key: string, value: string }> = []) {
  register('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'openai', modelId: 'gpt-4', enabled: true, isMain: true, providerConfigured: true },
  ])
  register('/api/channels', () => [])
  register('/api/providers', () => [
    { name: 'openai', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0, supportedModalities: ['PER_TOKEN'] },
  ])
  register('/api/ocr/status', () => ({
    providers: [{ name: 'tesseract', displayName: 'Tesseract OCR', available: true, enabled: true, version: 'test', reason: null, configKey: 'ocr.tesseract.enabled', description: 'test', installHint: '' }],
  }))
  register('/api/transcription/state', () => ({
    provider: 'whisper-local',
    localModel: 'small.en',
    ffmpegAvailable: true,
    ffmpegReason: 'available',
    models: [{ id: 'small.en', displayName: 'Small (English)', approxSizeMb: 190, status: 'ABSENT', bytesDownloaded: 0, totalBytes: 0, error: null }],
  }))
  register('/api/tts/state', () => ({
    engine: 'jvm',
    referenceVoice: 'my-voice.wav',
    engines: [
      { id: 'sidecar', displayName: 'Sidecar', available: true, status: 'ready', model: 'qwen3-0.6b', models: [{ id: 'qwen3-0.6b', displayName: 'Qwen3', approxSizeMb: 2500, present: false, downloading: false, voices: [], supportsCloning: true }] },
      { id: 'jvm', displayName: 'JVM', available: true, status: 'model downloads on first use', model: 'piper-en_US-amy-low', models: [{ id: 'piper-en_US-amy-low', displayName: 'Piper Amy', approxSizeMb: 65, present: false, downloading: false, voices: [] }] },
    ],
  }))
  register('/api/videogen/capability', () => ({ uvAvailable: true, uvReason: null, state: 'NEEDS_PROBE', capability: null, error: null }))
  register('/api/config', {
    method: 'GET',
    handler: () => ({
      entries: [
        { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1' },
        { key: 'provider.openai.apiKey', value: 'sk-****' },
        { key: 'provider.openai.models', value: '[{"id":"gpt-4","name":"GPT-4"}]' },
        { key: 'provider.replicate.apiKey', value: 'r8_****' },
        { key: 'search.exa.enabled', value: 'true' },
        { key: 'scanner.malwarebazaar.enabled', value: 'true' },
        ...extra,
      ].map(e => ({ ...e, updatedAt: '2026-09-17T10:00:00Z' })),
    }),
  })
  register('/api/config', { method: 'POST', handler: badGateway })
}

async function mountSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
function byText(component: any, text: string) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: see above.
  return component.findAll('button').find((b: any) => b.text().trim() === text)
}

describe('Settings — a failed toggle, download or detect says why (JCLAW-1221)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  afterEach(() => {
    unregister.forEach(off => off())
    unregister = []
  })

  it.each([
    { name: 'transcription toggle', section: 'transcription', extra: [], click: 'button[aria-label="Enable transcription"]', url: '/api/config' },
    { name: 'diarization toggle', section: 'transcription', extra: [], click: 'button[aria-label="Enable speaker diarization"]', url: '/api/config' },
    { name: 'image captioning toggle', section: 'image-caption', extra: [], click: 'button[aria-label="Enable image captioning"]', url: '/api/config' },
    { name: 'video model toggle', section: 'video-interpretation', extra: [], click: 'button[aria-label="Enable a dedicated video model"]', url: '/api/config' },
    { name: 'image generation toggle', section: 'image-generation', extra: [], click: 'button[aria-label="Enable image generation"]', url: '/api/config' },
    { name: 'embeddings toggle', section: 'memory', extra: [], click: '[data-testid="memory-vector-toggle"]', url: '/api/config' },
    { name: 'reranker toggle', section: 'memory-reranker', extra: [], click: '[data-testid="memory-rerank-toggle"]', url: '/api/config' },
    { name: 'search provider toggle', section: 'search', extra: [], click: 'button[aria-label="Exa search provider"]', url: '/api/config' },
    { name: 'malware scanner toggle', section: 'malware', extra: [], click: 'button[aria-label="MalwareBazaar (abuse.ch) scanner"]', url: '/api/config' },
    { name: 'OCR backend toggle', section: 'ocr', extra: [], click: 'button[aria-label="Tesseract OCR"]', url: '/api/config' },
    { name: 'price refresh toggle', section: 'providers', extra: [], click: 'button[aria-label="Auto-update model prices nightly"]', url: '/api/config' },
    { name: 'provider enable toggle', section: 'providers', extra: [], click: 'button[aria-label="openai provider"]', url: '/api/config' },
  ])('$name', async ({ section, extra, click, url }) => {
    setupApi(extra)
    const component = await mountSection(section)
    const control = component.find(click)
    expect(control.exists(), `${click} should render`).toBe(true)
    await control.trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('[data-testid="api-error"]').text()).toContain(url)
  })

  it('local transcription model download', async () => {
    setupApi([{ key: 'transcription.provider', value: 'whisper-local' }, { key: 'transcription.localModel', value: 'small.en' }])
    register('/api/transcription/models/small.en/download', { method: 'POST', handler: badGateway })
    const component = await mountSection('transcription')
    await byText(component, 'Download').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('[data-testid="api-error"]').text()).toContain('/api/transcription/models/small.en/download')
  })

  it('speech model download', async () => {
    setupApi([{ key: 'tts.engine', value: 'jvm' }, { key: 'tts.jvm.model', value: 'piper-en_US-amy-low' }])
    register('/api/tts/models/piper-en_US-amy-low/download', { method: 'POST', handler: badGateway })
    const component = await mountSection('speech')
    await vi.waitFor(() => expect(byText(component, 'Download')).toBeTruthy())
    await byText(component, 'Download').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('[data-testid="api-error"]').text()).toContain('/api/tts/models/piper-en_US-amy-low/download')
  })

  it('removing the speech reference clip', async () => {
    setupApi([{ key: 'tts.engine', value: 'sidecar' }, { key: 'tts.sidecar.model', value: 'qwen3-0.6b' }])
    register('/api/tts/reference-voice', { method: 'DELETE', handler: badGateway })
    const component = await mountSection('speech')
    await vi.waitFor(() => expect(byText(component, 'Clear')).toBeTruthy())
    await byText(component, 'Clear').trigger('click')

    await vi.waitFor(() => expect(component.text()).toContain('/api/tts/reference-voice'))
  })

  it('video generation GPU detect', async () => {
    setupApi([{ key: 'videogen.provider', value: 'replicate' }])
    register('/api/videogen/capability/probe', { method: 'POST', handler: badGateway })
    const component = await mountSection('video-generation')
    await vi.waitFor(() => expect(byText(component, 'detect GPU')).toBeTruthy())
    await byText(component, 'detect GPU').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('[data-testid="api-error"]').text()).toContain('/api/videogen/capability/probe')
  })

  it('a Skills Promotion provider change whose model reset fails shows the provider that was saved', async () => {
    const store = new Map([['skillsPromotion.provider', 'openai'], ['skillsPromotion.model', 'gpt-4'], ['provider.openai.apiKey', 'sk-****'], ['provider.ollama-local.baseUrl', 'http://localhost:11434/v1']])
    setupApi()
    register('/api/config', { method: 'GET', handler: () => ({ entries: [...store].map(([key, value]) => ({ key, value, updatedAt: '2026-09-17T10:00:00Z' })) }) })
    register('/api/config', {
      method: 'POST',
      handler: async (event) => {
        const body = await readBody(event) as { key: string, value: string }
        if (body.key === 'skillsPromotion.model') return badGateway(event)
        store.set(body.key, body.value)
        return { ok: true }
      },
    })
    const component = await mountSection('skills')
    await component.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    await component.find('select').setValue('ollama-local')
    await component.find('button[title="Save"]').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    await component.find('button[title="Cancel"]').trigger('click')
    await flushPromises()
    expect(component.text()).toContain('ollama-local')
  })
})
