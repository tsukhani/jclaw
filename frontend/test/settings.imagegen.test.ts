import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-229 — Image Generation settings section. Renders the section, gates the cloud-provider radios
 * on their API keys, and persists imagegen.provider via /api/config.
 */

const PROVIDERS = [
  { name: 'openai', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0, supportedModalities: ['PER_TOKEN'] },
]

interface Opts {
  imagegenProvider?: string
  openaiKey?: string
  bflKey?: string
  replicateKey?: string
  uvAvailable?: boolean
  fluxModelStatus?: 'ABSENT' | 'DOWNLOADING' | 'AVAILABLE' | 'ERROR'
  hfToken?: string
  capturePost?: (b: { key?: string, value?: string }) => void
  capturePull?: () => void
}

function configEntries(opts: Opts) {
  const e = [
    { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1', updatedAt: '2026-06-23T10:00:00Z' },
    { key: 'provider.openai.apiKey', value: opts.openaiKey ?? '', updatedAt: '2026-06-23T10:00:00Z' },
    { key: 'provider.bfl.baseUrl', value: 'https://api.bfl.ai/v1', updatedAt: '2026-06-23T10:00:00Z' },
    { key: 'provider.bfl.apiKey', value: opts.bflKey ?? '', updatedAt: '2026-06-23T10:00:00Z' },
    { key: 'provider.replicate.baseUrl', value: 'https://api.replicate.com/v1', updatedAt: '2026-06-23T10:00:00Z' },
    { key: 'provider.replicate.apiKey', value: opts.replicateKey ?? '', updatedAt: '2026-06-23T10:00:00Z' },
    { key: 'imagegen.local.hfToken', value: opts.hfToken ?? '', updatedAt: '2026-06-23T10:00:00Z' },
  ]
  if (opts.imagegenProvider !== undefined) {
    e.push({ key: 'imagegen.provider', value: opts.imagegenProvider, updatedAt: '2026-06-23T10:00:00Z' })
  }
  return e
}

function setupApi(opts: Opts = {}) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'openai', modelId: 'gpt-4.1', enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/providers', () => PROVIDERS)
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/transcription/state', () => ({ provider: 'whisper-local', localModel: 'small.en', ffmpegAvailable: true, ffmpegReason: 'available', models: [] }))
  registerEndpoint('/api/imagegen/local/state', () => ({
    provider: opts.imagegenProvider ?? '',
    model: 'black-forest-labs/FLUX.2-klein-4B',
    uvAvailable: opts.uvAvailable ?? false,
    uvReason: opts.uvAvailable ? 'available' : 'uv not found on PATH',
    modelStatus: opts.fluxModelStatus ?? 'ABSENT',
    bytesDownloaded: 0,
    totalBytes: 0,
    error: null,
  }))
  registerEndpoint('/api/imagegen/local/pull', {
    method: 'POST',
    handler: () => {
      opts.capturePull?.()
      return { status: 'downloading', model: 'black-forest-labs/FLUX.2-klein-4B' }
    },
  })
  registerEndpoint('/api/providers/vllm/reachable', () => ({ provider: 'vllm', reachable: false, modelCount: 0, reason: 'vllm not running' }))
  registerEndpoint('/api/providers/openrouter/video-models', () => ({ provider: 'openrouter', models: [], count: 0 }))
  registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: configEntries(opts) }) })
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      const body = await readBody(event) as { key?: string, value?: string }
      opts.capturePost?.(body)
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

describe('Settings page — Image Generation (JCLAW-229)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders the section and shows off when no provider is set', async () => {
    setupApi({ openaiKey: 'sk-****' })
    const c = await mountSettingsSection('image-generation')
    expect(c.text()).toContain('Image Generation')
    expect(c.text()).toContain('Image generation is off')
  })

  it('renders the three provider radios, checks the active one, and gates the keyless ones', async () => {
    setupApi({ imagegenProvider: 'openai', openaiKey: 'sk-****', bflKey: '', replicateKey: '' })
    const c = await mountSettingsSection('image-generation')

    const openai = c.find<HTMLInputElement>('#imagegen-provider-openai')
    const bfl = c.find<HTMLInputElement>('#imagegen-provider-bfl')
    const replicate = c.find<HTMLInputElement>('#imagegen-provider-replicate')
    expect(openai.exists()).toBe(true)
    expect(bfl.exists()).toBe(true)
    expect(replicate.exists()).toBe(true)
    // OpenAI key is set (reused from LLM Providers) → enabled + checked.
    expect(openai.element.checked).toBe(true)
    expect(openai.element.disabled).toBe(false)
    // No BFL / Replicate keys → those radios are disabled with the "set it below" hint.
    expect(bfl.element.disabled).toBe(true)
    expect(replicate.element.disabled).toBe(true)
    expect(c.text()).toContain('no API key')
    // The self-hosted Flux radio is present but disabled until uv is detected (none in this setup).
    const flux = c.find<HTMLInputElement>('#imagegen-provider-flux-local')
    expect(flux.exists()).toBe(true)
    expect(flux.element.disabled).toBe(true)
  })

  it('JCLAW-700: groups Replicate models into image-to-image and text-to-image optgroups', async () => {
    registerEndpoint('/api/imagegen/models', () => [
      { slug: 'black-forest-labs/flux-schnell', name: 'flux-schnell', description: 'fast t2i', imageToImage: false },
      { slug: 'black-forest-labs/flux-kontext-pro', name: 'flux-kontext-pro', description: 'style transfer', imageToImage: true },
    ])
    setupApi({ imagegenProvider: 'replicate', replicateKey: 're_****' })
    const c = await mountSettingsSection('image-generation')

    const select = c.find('select[aria-label="Replicate image model"]')
    expect(select.exists()).toBe(true)

    // The discovered models are grouped under labeled optgroups (image-to-image first).
    await vi.waitFor(() => {
      const labels = select.findAll('optgroup').map(g => g.attributes('label'))
      expect(labels).toContain('Image-to-image (style transfer)')
      expect(labels).toContain('Text-to-image')
    })
    const groups = select.findAll('optgroup')
    const i2i = groups.find(g => g.attributes('label') === 'Image-to-image (style transfer)')!
    expect(i2i.text()).toContain('black-forest-labs/flux-kontext-pro')
    const t2i = groups.find(g => g.attributes('label') === 'Text-to-image')!
    expect(t2i.text()).toContain('black-forest-labs/flux-schnell')
  })

  it('enables the self-hosted Flux radio when uv is available and POSTs flux-local on select', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ imagegenProvider: 'openai', openaiKey: 'sk-****', uvAvailable: true, capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    const flux = c.find<HTMLInputElement>('#imagegen-provider-flux-local')
    expect(flux.exists()).toBe(true)
    expect(flux.element.disabled).toBe(false)

    await flux.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'imagegen.provider')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('flux-local')
  })

  it('shows the uv-missing banner when flux-local is selected but uv is absent', async () => {
    setupApi({ imagegenProvider: 'flux-local', openaiKey: 'sk-****', uvAvailable: false })
    const c = await mountSettingsSection('image-generation')
    expect(c.text()).toContain('Install uv')
  })

  it('sets the optional Hugging Face token inline', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ imagegenProvider: 'flux-local', openaiKey: 'sk-****', uvAvailable: true, capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    // Inline guidance: a link to the HF tokens page is present so users aren't left guessing.
    expect(c.html()).toContain('huggingface.co/settings/tokens')

    await c.find('button[aria-label="Edit Hugging Face token"]').trigger('click')
    await flushPromises()
    const input = c.find<HTMLInputElement>('input[aria-label="Hugging Face token"]')
    expect(input.exists()).toBe(true)
    await input.setValue('hf_secret_abc')
    await c.find('button[title="Save"]').trigger('click')
    await flushPromises()

    const hit = captured.find(b => b.key === 'imagegen.local.hfToken')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('hf_secret_abc')
  })

  it('triggers the weight pull when the Download button is clicked', async () => {
    const pulls: number[] = []
    setupApi({ imagegenProvider: 'flux-local', openaiKey: 'sk-****', uvAvailable: true, fluxModelStatus: 'ABSENT', capturePull: () => pulls.push(1) })
    const c = await mountSettingsSection('image-generation')

    const btn = c.findAll('button').find(b => b.text() === 'Download')
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()

    expect(pulls.length).toBe(1)
    c.unmount() // clears the poll interval started by downloadFluxModel()
  })

  it('POSTs imagegen.provider when the section is enabled', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ openaiKey: 'sk-****', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    await c.find('button[aria-label="Enable image generation"]').trigger('click')
    await flushPromises()

    const hit = captured.find(b => b.key === 'imagegen.provider')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('openai')
  })

  it('POSTs imagegen.provider=bfl when the BFL radio is selected', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ imagegenProvider: 'openai', openaiKey: 'sk-****', bflKey: 'bfl-****', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    await c.find('#imagegen-provider-bfl').trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'imagegen.provider')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('bfl')
  })

  it('sets the BFL API key inline (not via LLM Providers)', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ imagegenProvider: 'openai', openaiKey: 'sk-****', bflKey: '', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    // BFL key not set → reveal the inline field, type a key, and save it.
    await c.find('button[aria-label="Edit Black Forest Labs API key"]').trigger('click')
    await flushPromises()
    const input = c.find<HTMLInputElement>('input[aria-label="Black Forest Labs API key"]')
    expect(input.exists()).toBe(true)
    await input.setValue('bfl-secret-123')
    await c.find('button[title="Save"]').trigger('click')
    await flushPromises()

    const hit = captured.find(b => b.key === 'provider.bfl.apiKey')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('bfl-secret-123')
  })

  it('POSTs imagegen.provider=replicate when the Replicate radio is selected', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ imagegenProvider: 'openai', openaiKey: 'sk-****', replicateKey: 'r8-****', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    await c.find('#imagegen-provider-replicate').trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'imagegen.provider')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('replicate')
  })

  it('sets the Replicate API key inline', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ imagegenProvider: 'openai', openaiKey: 'sk-****', replicateKey: '', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('image-generation')

    await c.find('button[aria-label="Edit Replicate API key"]').trigger('click')
    await flushPromises()
    const input = c.find<HTMLInputElement>('input[aria-label="Replicate API key"]')
    expect(input.exists()).toBe(true)
    await input.setValue('r8-secret-456')
    await c.find('button[title="Save"]').trigger('click')
    await flushPromises()

    const hit = captured.find(b => b.key === 'provider.replicate.apiKey')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('r8-secret-456')
  })
})
