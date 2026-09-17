import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
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

describe('Settings — Video Generation (JCLAW-236)', () => {
  beforeEach(() => clearNuxtData())

  it('renders the section; the enable toggle is disabled when no Replicate key is set', async () => {
    setupApi()
    const c = await mountSettingsSection('video-generation')

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
    const c = await mountSettingsSection('video-generation')

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
    const c = await mountSettingsSection('video-generation')

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
    const c = await mountSettingsSection('video-generation')

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
    const c = await mountSettingsSection('video-generation')

    const select = c.find('select[aria-label="Replicate video model"]')
    await select.setValue('wan-video/wan-2.2-t2v-fast')
    await flushPromises()

    const hit = captured.find(b => b.key === 'videogen.cloud.model')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('wan-video/wan-2.2-t2v-fast')
  })
})

describe('Settings — Video Generation radios put the saved choice back when a save fails (JCLAW-1221)', () => {
  let unregister: Array<() => void> = []

  beforeEach(() => {
    clearNuxtData()
  })

  afterEach(() => {
    unregister.forEach(off => off())
    unregister = []
  })

  function failSaves() {
    unregister.push(registerEndpoint('/api/config', {
      method: 'POST',
      handler: (event) => {
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    }))
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
  async function chooseAndExpectPutBack(component: any, choose: string, saved: string) {
    await component.find(choose).setValue(true)
    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(component.find('[data-testid="api-error"]').text()).toContain('/api/config')
    expect((component.find(saved).element as HTMLInputElement).checked).toBe(true)
    expect((component.find(choose).element as HTMLInputElement).checked).toBe(false)
  }

  function capability(models: Array<{ id: string, provider: string }>) {
    unregister.push(registerEndpoint('/api/videogen/capability', () => ({
      uvAvailable: true,
      uvReason: null,
      state: 'READY',
      capability: {
        kind: 'cuda',
        gpu: 'Test GPU',
        freeVramGb: 24,
        totalVramGb: 24,
        models: models.map(m => ({ ...m, label: m.id, minVramGb: 8, tier: 'ready', runnable: true, reason: null })),
      },
      error: null,
    })))
  }

  it('Replicate, while a self-hosted engine is saved', async () => {
    setupApi({ extraEntries: [
      { key: 'provider.replicate.apiKey', value: 'r8_****' },
      { key: 'videogen.provider', value: 'ltx-local' },
    ] })
    failSaves()
    const component = await mountSettingsSection('video-generation')
    await chooseAndExpectPutBack(component, '#videogen-provider-replicate', '#videogen-provider-local')
  })

  it('Self-Hosted, while Replicate is saved', async () => {
    setupApi({ extraEntries: [
      { key: 'provider.replicate.apiKey', value: 'r8_****' },
      { key: 'videogen.provider', value: 'replicate' },
    ] })
    capability([{ id: 'ltx', provider: 'ltx-local' }])
    failSaves()
    const component = await mountSettingsSection('video-generation')
    await vi.waitFor(() => expect((component.find('#videogen-provider-local').element as HTMLInputElement).disabled).toBe(false))
    await chooseAndExpectPutBack(component, '#videogen-provider-local', '#videogen-provider-replicate')
  })

  it('a self-hosted engine tier', async () => {
    setupApi({ extraEntries: [
      { key: 'provider.replicate.apiKey', value: 'r8_****' },
      { key: 'videogen.provider', value: 'ltx-local' },
      { key: 'videogen.local.model', value: 'ltx' },
    ] })
    capability([{ id: 'ltx', provider: 'ltx-local' }, { id: 'ltx-int8', provider: 'ltx-local' }])
    failSaves()
    const component = await mountSettingsSection('video-generation')
    await vi.waitFor(() => expect(component.find('#videogen-engine-ltx-int8').exists()).toBe(true))
    await chooseAndExpectPutBack(component, '#videogen-engine-ltx-int8', '#videogen-engine-ltx')
  })
})

describe('Settings — Video Generation Self-Hosted radio when the probe selects nothing', () => {
  let unregister: Array<() => void> = []
  let posted: Array<{ key?: string, value?: string }> = []

  beforeEach(() => {
    clearNuxtData()
    posted = []
  })

  afterEach(() => {
    unregister.forEach(off => off())
    unregister = []
  })

  type Snapshot = { uvAvailable: boolean, uvReason: null, state: string, capability: unknown, error: string | null }
  const needsProbe: Snapshot = { uvAvailable: true, uvReason: null, state: 'NEEDS_PROBE', capability: null, error: null }
  function ready(runnable: boolean): Snapshot {
    return {
      uvAvailable: true,
      uvReason: null,
      state: 'READY',
      capability: {
        kind: 'cuda',
        gpu: 'Test GPU',
        freeVramGb: 24,
        totalVramGb: 24,
        models: [{ id: 'ltx', label: 'LTX', provider: 'ltx-local', minVramGb: 8, tier: runnable ? 'ready' : 'no', runnable, reason: runnable ? null : 'too little VRAM' }],
      },
      error: null,
    }
  }

  // Each probe settles on the next snapshot in `results`.
  function probeSettlesOn(results: Snapshot[]) {
    let snapshot = needsProbe
    setupApi({
      capturePost: b => posted.push(b),
      extraEntries: [
        { key: 'provider.replicate.apiKey', value: 'r8_****' },
        { key: 'videogen.provider', value: 'replicate' },
      ],
    })
    unregister.push(registerEndpoint('/api/videogen/capability', () => snapshot))
    unregister.push(registerEndpoint('/api/videogen/capability/probe', {
      method: 'POST',
      handler: () => {
        snapshot = results.shift() ?? snapshot
        return { state: 'PROBING' }
      },
    }))
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
  const checked = (component: any, id: string) => (component.find(id).element as HTMLInputElement).checked

  it('puts the saved backend back when no engine can run here', async () => {
    probeSettlesOn([ready(false)])
    const component = await mountSettingsSection('video-generation')
    await vi.waitFor(() => expect((component.find('#videogen-provider-local').element as HTMLInputElement).disabled).toBe(false))

    await component.find('#videogen-provider-local').setValue(true)
    await vi.waitFor(() => expect(component.text()).toContain('can\'t run local video generation'))

    expect(checked(component, '#videogen-provider-replicate')).toBe(true)
    expect(checked(component, '#videogen-provider-local')).toBe(false)
    expect(posted).toEqual([])
  })

  it('puts the saved backend back when the probe fails, and a later detect does not switch it', async () => {
    probeSettlesOn([{ ...needsProbe, state: 'ERROR', error: 'probe crashed' }, ready(true)])
    const component = await mountSettingsSection('video-generation')
    await vi.waitFor(() => expect((component.find('#videogen-provider-local').element as HTMLInputElement).disabled).toBe(false))

    await component.find('#videogen-provider-local').setValue(true)
    await vi.waitFor(() => expect(component.text()).toContain('probe crashed'))
    expect(checked(component, '#videogen-provider-replicate')).toBe(true)
    expect(checked(component, '#videogen-provider-local')).toBe(false)

    const detect = component.findAll('button').find((b: { text: () => string }) => b.text() === 'detect GPU')
    await detect!.trigger('click')
    await vi.waitFor(() => expect(component.find('#videogen-engine-ltx').exists()).toBe(true))
    await flushPromises()

    expect(posted).toEqual([])
    expect(checked(component, '#videogen-provider-replicate')).toBe(true)
  })
})
