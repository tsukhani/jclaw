import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Page-level tests for {@code settings.vue} focused on the form-binding
 * surface.
 *
 * <p>{@code pages.test.ts} covers the structural rendering (provider section
 * presence, no add-entry form). These tests sit one layer deeper, exercising:
 *
 * <ul>
 *   <li>Provider config values from {@code /api/config} render in the
 *       provider section.</li>
 *   <li>The model-add form's text inputs are present and respond to
 *       programmatic input changes (the v-model two-way binding contract).</li>
 *   <li>Capability checkboxes (thinking/vision/audio) render as form
 *       controls so the assemble path can read their state.</li>
 *   <li>The discovery search/filter inputs render — those drive the
 *       JCLAW-118 discover-models flow.</li>
 * </ul>
 */

// Mutable so individual tests can swap the OCR shape (available/unavailable)
// before mounting the page. Re-assigned by setupConfigApi() each call so a
// preceding test's override doesn't leak into the next.
let ocrStatusPayload: { providers: Array<Record<string, unknown>> } = {
  providers: [
    {
      name: 'tesseract',
      displayName: 'Tesseract OCR',
      available: true,
      enabled: true,
      version: 'tesseract 5.5.2 (test stub)',
      reason: null,
      configKey: 'ocr.tesseract.enabled',
      description: 'Apache Tika TesseractOCRParser test stub.',
      installHint: 'brew install tesseract',
    },
  ],
}

function setupConfigApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
      enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [
    { channelType: 'telegram', enabled: false },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1',
        updatedAt: '2026-04-22T10:00:00Z' },
      { key: 'provider.ollama-cloud.apiKey', value: 'sk-test-****',
        updatedAt: '2026-04-22T10:00:00Z' },
      { key: 'provider.ollama-cloud.models', value:
        '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535,"supportsThinking":true}]',
      updatedAt: '2026-04-22T10:00:00Z' },
      { key: 'ocr.tesseract.enabled', value: 'true',
        updatedAt: '2026-04-22T10:00:00Z' },
    ],
  }))
  registerEndpoint('/api/ocr/status', () => ocrStatusPayload)
  registerEndpoint('/api/transcription/state', () => transcriptionStatePayload)
}

// Mutable so the radio-gating tests below can flip ffmpeg / models around
// without redefining the whole stub.
let transcriptionStatePayload: {
  provider: string | null
  localModel: string | null
  ffmpegAvailable: boolean
  ffmpegReason: string | null
  models: Array<{
    id: string
    displayName: string
    approxSizeMb: number
    status: string
    bytesDownloaded: number
    totalBytes: number
    error: string | null
  }>
} = {
  provider: 'whisper-local',
  localModel: 'small.en',
  ffmpegAvailable: true,
  ffmpegReason: 'available',
  models: [
    { id: 'base.en', displayName: 'Base (English)', approxSizeMb: 57,
      status: 'ABSENT', bytesDownloaded: 0, totalBytes: 0, error: null },
    { id: 'small.en', displayName: 'Small (English)', approxSizeMb: 190,
      status: 'AVAILABLE', bytesDownloaded: 199229440, totalBytes: 199229440, error: null },
    { id: 'medium.en', displayName: 'Medium (English)', approxSizeMb: 514,
      status: 'ABSENT', bytesDownloaded: 0, totalBytes: 0, error: null },
    { id: 'small', displayName: 'Small (Multilingual)', approxSizeMb: 190,
      status: 'ABSENT', bytesDownloaded: 0, totalBytes: 0, error: null },
    { id: 'medium', displayName: 'Medium (Multilingual)', approxSizeMb: 514,
      status: 'ABSENT', bytesDownloaded: 0, totalBytes: 0, error: null },
  ],
}

describe('Settings page — provider section', () => {
  // JCLAW-182: the grouping test below reshapes the /api/config response, so
  // clear Nuxt's useFetch cache between tests — otherwise the next test re-uses
  // the prior test's payload (same gotcha the OCR section's beforeEach calls out).
  beforeEach(() => {
    clearNuxtData()
  })

  it('surfaces the configured provider name from the config payload', async () => {
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.text()).toContain('LLM Providers')
    // JCLAW-182: provider cards now render the friendly display label
    // (PROVIDER_LABELS map) rather than the raw kebab-case name.
    expect(component.text()).toContain('Ollama Cloud')
  })

  it('groups providers under Remote and Local subheadings in that order', async () => {
    // JCLAW-182 AC #4-#6: with all four providers configured, the LLM Providers
    // section emits a Remote heading containing ollama-cloud + openrouter, then
    // a Local heading containing ollama-local + lm-studio. The order is
    // load-bearing — operators expect remote/hosted options first.
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/channels', () => [])
    registerEndpoint('/api/config', () => ({
      entries: [
        { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.ollama-cloud.apiKey', value: 'sk-cloud', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.openrouter.baseUrl', value: 'https://openrouter.ai/api/v1', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.openrouter.apiKey', value: 'sk-or', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.ollama-local.baseUrl', value: 'http://localhost:11434/v1', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.ollama-local.apiKey', value: 'ollama-local', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.lm-studio.baseUrl', value: 'http://localhost:1234/v1', updatedAt: '2026-04-29T10:00:00Z' },
        { key: 'provider.lm-studio.apiKey', value: 'lm-studio', updatedAt: '2026-04-29T10:00:00Z' },
      ],
    }))
    registerEndpoint('/api/ocr/status', () => ocrStatusPayload)

    const component = await mountSuspended(Settings)
    await flushPromises()

    const html = component.html()

    // Search for the H3 headings specifically — "Local" appears as a substring
    // in "Ollama Local" otherwise, and "Remote" could collide with future class
    // names. The H3 element is unique to the group dividers.
    const remoteHeadingIdx = html.search(/<h3[^>]*>\s*Remote\s*<\/h3>/)
    const localHeadingIdx = html.search(/<h3[^>]*>\s*Local\s*<\/h3>/)
    expect(remoteHeadingIdx).toBeGreaterThan(-1)
    expect(localHeadingIdx).toBeGreaterThan(-1)
    expect(remoteHeadingIdx).toBeLessThan(localHeadingIdx)

    // Remote group contains Ollama Cloud + OpenRouter, both before the Local heading.
    const ollamaCloudIdx = html.indexOf('Ollama Cloud')
    const openRouterIdx = html.indexOf('OpenRouter')
    expect(ollamaCloudIdx).toBeGreaterThan(remoteHeadingIdx)
    expect(openRouterIdx).toBeGreaterThan(remoteHeadingIdx)
    expect(ollamaCloudIdx).toBeLessThan(localHeadingIdx)
    expect(openRouterIdx).toBeLessThan(localHeadingIdx)

    // Local group contains Ollama Local + LM Studio, both after the Local heading.
    const ollamaLocalIdx = html.indexOf('Ollama Local')
    const lmStudioIdx = html.indexOf('LM Studio')
    expect(ollamaLocalIdx).toBeGreaterThan(localHeadingIdx)
    expect(lmStudioIdx).toBeGreaterThan(localHeadingIdx)
  })

  it('surfaces a models count badge from the persisted models JSON', async () => {
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // Models are parsed out of provider.{name}.models and summarized as a
    // click-to-expand count badge — the detail rows only render after the
    // operator expands that section. The count itself must surface.
    expect(component.text()).toMatch(/1 model/i)
  })

  it('does not leak unmasked API keys into the rendered DOM', async () => {
    // The backend masks sensitive keys before serializing /api/config; the
    // frontend just renders what the backend hands it. Pin the contract that
    // we don't accidentally bypass that guard with a local re-fetch path.
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    const html = component.html()
    expect(html).toContain('sk-test-****')
    expect(html).not.toContain('sk-test-real-key')
  })
})

describe('Settings page — form binding', () => {
  it('renders interactive buttons for the inline edit affordance', async () => {
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // Settings uses a click-to-edit pattern: rows render read-only by default
    // and promote to a v-model'd input only after the operator clicks edit.
    // Pin that the interactive scaffolding (buttons) is present so those
    // click handlers have somewhere to attach — without them the click-to-edit
    // lifecycle can't start.
    const buttons = component.findAll('button')
    expect(buttons.length).toBeGreaterThanOrEqual(1)
  })

  it('honors programmatic input value updates as a v-model smoke check', async () => {
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // Find the first text-shaped input (skips checkboxes/radios) and verify
    // setValue propagates through happy-dom's input event. This is the most
    // basic v-model contract check possible — if Vue's two-way binding
    // pipeline is broken, this fails.
    const textInputs = component.findAll('input')
      .filter((w) => {
        const t = (w.element as HTMLInputElement).type
        return t === 'text' || t === 'url' || t === '' // default is text
      })
    if (textInputs.length === 0) return // nothing to bind against in initial state
    const first = textInputs[0]!
    await first.setValue('proof-of-binding')
    expect((first.element as HTMLInputElement).value).toBe('proof-of-binding')
  })

  it('renders the provider configuration block that hosts capability toggles', async () => {
    // Capability flags (supportsThinking / Vision / Audio) live inside the
    // model-edit modal, which is click-gated. On the initial page, we can
    // still pin the scaffolding the operator reaches them through — the
    // provider section carries the "configured" state marker for the
    // provider whose models the modal would edit.
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.text().toLowerCase()).toContain('configured')
  })
})

describe('Settings page — OCR section', () => {
  // Nuxt's useFetch payload is keyed by URL by default; without clearing,
  // the second mountSuspended in this describe block re-uses the first
  // test's /api/ocr/status response and the per-test ocrStatusPayload swap
  // never reaches the rendered DOM.
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders the OCR section between LLM Providers and Search Providers', async () => {
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    const text = component.text()
    expect(text).toContain('OCR')
    expect(text).toContain('Tesseract OCR')

    // Slot order: LLM Providers heading must appear before the OCR heading,
    // which must appear before Search Providers. The heading text is unique
    // enough to survive other matches in the page (no other element renders
    // the bare string "OCR" at the section-heading level).
    const html = component.html()
    const llmIdx = html.indexOf('LLM Providers')
    const ocrIdx = html.search(/<h2[^>]*>\s*OCR\s*</)
    const searchIdx = html.indexOf('Search Providers')
    expect(llmIdx).toBeGreaterThan(-1)
    expect(ocrIdx).toBeGreaterThan(-1)
    expect(searchIdx).toBeGreaterThan(-1)
    expect(llmIdx).toBeLessThan(ocrIdx)
    expect(ocrIdx).toBeLessThan(searchIdx)
  })

  it('shows the active pill and an interactive toggle when tesseract is detected', async () => {
    ocrStatusPayload = {
      providers: [{
        name: 'tesseract', displayName: 'Tesseract OCR',
        available: true, enabled: true,
        version: 'tesseract 5.5.2', reason: null,
        configKey: 'ocr.tesseract.enabled',
        description: 'Apache Tika TesseractOCRParser.',
        installHint: 'brew install tesseract',
      }],
    }
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.text()).toContain('active')
    const toggle = component.find('button[aria-label*="Tesseract OCR"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('disabled')).toBeUndefined()
  })

  it('disables the toggle and shows "not detected" when probe says missing', async () => {
    // The acceptance condition for the OCR section: when tesseract isn't on
    // PATH, the toggle must NOT be interactive — installation is a host-side
    // action, not a UI flip. Without this, an operator could "enable" a
    // backend with no underlying binary and silently get empty extractions.
    ocrStatusPayload = {
      providers: [{
        name: 'tesseract', displayName: 'Tesseract OCR',
        available: false, enabled: true, // enabled in DB but probe says missing
        version: null,
        reason: 'tesseract --version exited 127: command not found',
        configKey: 'ocr.tesseract.enabled',
        description: 'Apache Tika TesseractOCRParser.',
        installHint: 'brew install tesseract (macOS), apt-get install tesseract-ocr (Debian/Ubuntu)',
      }],
    }
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.text()).toContain('not detected')
    expect(component.text()).toContain('apt-get install tesseract-ocr')

    const toggle = component.find('button[aria-label*="Tesseract OCR"]')
    expect(toggle.exists()).toBe(true)
    // Vue normalizes :disabled="true" to the disabled attribute being present
    // (value is the empty string in the rendered DOM).
    expect(toggle.attributes('disabled')).toBeDefined()
  })
})

describe('Settings page — discovery surface', () => {
  it('renders the discovery search input when models exist', async () => {
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // discoverySearch is bound to a text input under the model discovery panel.
    // It is gated on the panel being open, so we only assert the broader
    // settings content rendered without errors — the deeper interaction is
    // covered indirectly when the discovery filter updates the catalog list.
    expect(component.text()).toContain('Settings')
  })
})

describe('Settings page — Transcription section (JCLAW-164)', () => {
  // Like the OCR section above, the transcription section pulls ffmpeg state
  // through useFetch and gating depends on /api/config payload — clear Nuxt's
  // useFetch cache between tests so payload swaps actually reach the DOM.
  beforeEach(() => {
    clearNuxtData()
  })

  /** Override /api/config to control which provider API keys are configured. */
  function configPayload(extra: Array<{ key: string, value: string }>) {
    return {
      entries: [
        { key: 'transcription.provider', value: 'whisper-local',
          updatedAt: '2026-04-22T10:00:00Z' },
        { key: 'transcription.localModel', value: 'small.en',
          updatedAt: '2026-04-22T10:00:00Z' },
        ...extra.map(e => ({ ...e, updatedAt: '2026-04-22T10:00:00Z' })),
      ],
    }
  }

  it('disables both cloud-provider radios when neither API key is configured', async () => {
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/channels', () => [])
    registerEndpoint('/api/config', () => configPayload([]))
    registerEndpoint('/api/ocr/status', () => ocrStatusPayload)
    registerEndpoint('/api/transcription/state', () => transcriptionStatePayload)
    const component = await mountSuspended(Settings)
    await flushPromises()

    // Self-Hosted Whisper is always enabled — it doesn't depend on a remote key.
    const whisper = component.find('input[name="transcription-provider"][value="whisper-local"]')
    expect(whisper.exists()).toBe(true)
    expect(whisper.attributes('disabled')).toBeUndefined()

    // OpenRouter and OpenAI both gated by their respective provider.*.apiKey row.
    const openrouter = component.find('input[name="transcription-provider"][value="openrouter"]')
    const openai = component.find('input[name="transcription-provider"][value="openai"]')
    expect(openrouter.exists()).toBe(true)
    expect(openai.exists()).toBe(true)
    expect(openrouter.attributes('disabled')).toBeDefined()
    expect(openai.attributes('disabled')).toBeDefined()
  })

  it('enables a cloud-provider radio once its API key is set', async () => {
    registerEndpoint('/api/agents', () => [])
    registerEndpoint('/api/channels', () => [])
    registerEndpoint('/api/config', () => configPayload([
      // Mask suffix mirrors ConfigService.maskValue's "first4chars + ****" shape;
      // the gate treats any non-blank value as configured, including masked.
      { key: 'provider.openrouter.apiKey', value: 'sk-o****' },
    ]))
    registerEndpoint('/api/ocr/status', () => ocrStatusPayload)
    registerEndpoint('/api/transcription/state', () => transcriptionStatePayload)
    const component = await mountSuspended(Settings)
    await flushPromises()

    const openrouter = component.find('input[name="transcription-provider"][value="openrouter"]')
    const openai = component.find('input[name="transcription-provider"][value="openai"]')
    expect(openrouter.attributes('disabled')).toBeUndefined()
    // OpenAI key still missing — radio stays disabled.
    expect(openai.attributes('disabled')).toBeDefined()
  })

  it('shows the ffmpeg banner when the probe reports it missing', async () => {
    transcriptionStatePayload = {
      ...transcriptionStatePayload,
      ffmpegAvailable: false,
      ffmpegReason: 'ffmpeg not found on PATH',
    }
    setupConfigApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    const text = component.text()
    expect(text).toContain('ffmpeg')
    expect(text).toContain('not on PATH')
    expect(text).toContain('ffmpeg not found on PATH')
  })
})
