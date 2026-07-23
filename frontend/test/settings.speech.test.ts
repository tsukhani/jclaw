import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-789/793 — Speech (text-to-speech / read-aloud) settings section. Renders
 * the engine selector (Sidecar vs JVM-native), reflects the selected engine, and
 * persists tts.engine / tts.<engine>.model via /api/config. JVM voices show a
 * Download control when absent and Ready when present.
 */

interface Opts {
  engine?: string
  sidecarModel?: string
  jvmModel?: string
  sidecarAvailable?: boolean
  jvmModelPresent?: boolean
  capturePost?: (b: { key?: string, value?: string }) => void
}

function configEntries(opts: Opts) {
  return [
    { key: 'tts.engine', value: opts.engine ?? 'sidecar', updatedAt: '2026-07-20T10:00:00Z' },
    { key: 'tts.sidecar.model', value: opts.sidecarModel ?? 'qwen3-0.6b', updatedAt: '2026-07-20T10:00:00Z' },
    { key: 'tts.jvm.model', value: opts.jvmModel ?? 'piper-en_US-amy-low', updatedAt: '2026-07-20T10:00:00Z' },
  ]
}

function ttsState(opts: Opts) {
  return {
    engine: opts.engine ?? 'sidecar',
    engines: [
      {
        id: 'sidecar',
        displayName: 'Sidecar (Qwen3-TTS / Kokoro)',
        available: opts.sidecarAvailable ?? true,
        status: (opts.sidecarAvailable ?? true) ? 'ready — starts on first use' : 'needs \'uv\' on PATH',
        model: opts.sidecarModel ?? 'qwen3-0.6b',
        models: [
          { id: 'qwen3-0.6b', displayName: 'Qwen3-TTS 0.6B', approxSizeMb: 2500, present: false, downloading: false, voices: [{ id: '1', label: 'Voice 1' }, { id: '2', label: 'Voice 2' }] },
          { id: 'kokoro', displayName: 'Kokoro-82M', approxSizeMb: 330, present: false, downloading: false, voices: [{ id: 'af_bella', label: 'Bella (American, female)' }, { id: 'bm_george', label: 'George (British, male)' }] },
        ],
      },
      {
        id: 'jvm',
        displayName: 'JVM-native (sherpa-onnx)',
        available: true,
        status: (opts.jvmModelPresent ?? false) ? 'ready' : 'model downloads on first use',
        model: opts.jvmModel ?? 'piper-en_US-amy-low',
        models: [
          { id: 'piper-en_US-amy-low', displayName: 'Piper Amy (English, fast)', approxSizeMb: 65, present: opts.jvmModelPresent ?? false, downloading: false, voices: [] },
          { id: 'kokoro-multi-lang-v1_0', displayName: 'Kokoro-82M (multilingual)', approxSizeMb: 720, present: false, downloading: false, voices: [] },
        ],
      },
    ],
  }
}

function setupApi(opts: Opts = {}) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'openai', modelId: 'gpt-4.1', enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/tts/state', () => ttsState(opts))
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

// The page renders one section at a time (`<component :is>` swap), so tests must
// activate their section before asserting on its DOM (mirrors the other
// settings.*.test.ts). The double flush settles the panel's async setup.
async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Speech (JCLAW-789/793)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders the section with both engine radios, sidecar selected by default', async () => {
    setupApi({ engine: 'sidecar' })
    const c = await mountSettingsSection('speech')
    expect(c.text()).toContain('Speech')
    const sidecar = c.find<HTMLInputElement>('#tts-engine-sidecar')
    const jvm = c.find<HTMLInputElement>('#tts-engine-jvm')
    expect(sidecar.exists()).toBe(true)
    expect(jvm.exists()).toBe(true)
    expect(sidecar.element.checked).toBe(true)
    expect(jvm.element.checked).toBe(false)
  })

  it('POSTs tts.engine=jvm when the JVM-native radio is selected', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ engine: 'sidecar', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('speech')

    await c.find('#tts-engine-jvm').trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'tts.engine')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('jvm')
  })

  it('offers a Download control for an absent JVM voice', async () => {
    setupApi({ engine: 'jvm', jvmModelPresent: false })
    const c = await mountSettingsSection('speech')
    expect(c.find('#tts-engine-jvm').element).toBeTruthy()
    expect(c.text()).toContain('Download')
  })

  it('shows Ready for a present JVM voice', async () => {
    setupApi({ engine: 'jvm', jvmModelPresent: true })
    const c = await mountSettingsSection('speech')
    expect(c.text()).toContain('Ready')
  })

  it('shows a Voice dropdown for a model with preset voices and POSTs tts.<engine>.voice (JCLAW-846)', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ engine: 'sidecar', sidecarModel: 'kokoro', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('speech')

    const voiceSelect = c.find<HTMLSelectElement>('select[aria-label="Text-to-speech speaker voice"]')
    expect(voiceSelect.exists()).toBe(true)
    expect(c.text()).toContain('George (British, male)')

    voiceSelect.element.value = 'bm_george'
    await voiceSelect.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'tts.sidecar.voice')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('bm_george')
  })

  it('hides the Voice dropdown for a single-voice model (JCLAW-846)', async () => {
    setupApi({ engine: 'jvm', jvmModel: 'piper-en_US-amy-low' })
    const c = await mountSettingsSection('speech')
    expect(c.find('select[aria-label="Text-to-speech speaker voice"]').exists()).toBe(false)
  })
})
