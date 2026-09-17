import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * JCLAW-789/793 — Speech (text-to-speech / read-aloud) settings section. Renders
 * the engine selector (Sidecar vs JVM-native), reflects the selected engine, and
 * persists tts.engine / tts.<engine>.model via /api/config. JVM voices show a
 * Download control when absent and Ready when present.
 */

interface Opts {
  referenceVoice?: string | null
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
    referenceVoice: opts.referenceVoice ?? null,
    engines: [
      {
        id: 'sidecar',
        displayName: 'Sidecar (Qwen3-TTS / Kokoro)',
        available: opts.sidecarAvailable ?? true,
        status: (opts.sidecarAvailable ?? true) ? 'ready — starts on first use' : 'needs \'uv\' on PATH',
        model: opts.sidecarModel ?? 'qwen3-0.6b',
        models: [
          { id: 'qwen3-0.6b', displayName: 'Qwen3-TTS 0.6B', approxSizeMb: 2500, present: false, downloading: false, voices: [{ id: '1', label: 'Voice 1' }, { id: '2', label: 'Voice 2' }], supportsCloning: true },
          { id: 'kokoro', displayName: 'Kokoro-82M', approxSizeMb: 330, present: false, downloading: false, voices: [{ id: 'af_bella', label: 'Bella (American, female)' }, { id: 'bm_george', label: 'George (British, male)' }], supportsCloning: false },
          { id: 'chatterbox', displayName: 'Chatterbox (PyTorch, MPS/CUDA)', approxSizeMb: 1000, present: false, downloading: false, voices: [], supportsCloning: true },
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

  // ===== Keep-warm window (JCLAW-863) =====
  //
  // The key was always read at spawn but reachable only by editing the database,
  // so the one setting that governs first-reply latency was invisible. These pin
  // that it is now operable and that a stray value cannot reach the daemon.

  it('shows the keep-warm control for the sidecar engine, defaulting to 15', async () => {
    setupApi({ engine: 'sidecar' })
    const c = await mountSettingsSection('speech')

    const input = c.find<HTMLInputElement>('input[aria-label="Minutes the TTS sidecar stays loaded while idle"]')
    expect(input.exists()).toBe(true)
    expect(input.element.value).toBe('15')
  })

  it('hides the keep-warm control for the JVM engine, which has no daemon to idle', async () => {
    setupApi({ engine: 'jvm' })
    const c = await mountSettingsSection('speech')

    expect(c.find('input[aria-label="Minutes the TTS sidecar stays loaded while idle"]').exists()).toBe(false)
  })

  it('POSTs tts.local.idleTimeoutMinutes when changed', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ engine: 'sidecar', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('speech')

    const input = c.find<HTMLInputElement>('input[aria-label="Minutes the TTS sidecar stays loaded while idle"]')
    input.element.value = '120'
    await input.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'tts.local.idleTimeoutMinutes')
    expect(hit).toBeTruthy()
    expect(hit!.value).toBe('120')
  })

  it('clamps an out-of-range keep-warm value instead of persisting nonsense', async () => {
    const captured: Array<{ key?: string, value?: string }> = []
    setupApi({ engine: 'sidecar', capturePost: b => captured.push(b) })
    const c = await mountSettingsSection('speech')

    const input = c.find<HTMLInputElement>('input[aria-label="Minutes the TTS sidecar stays loaded while idle"]')
    input.element.value = '-5'
    await input.trigger('change')
    await flushPromises()

    const hit = captured.find(b => b.key === 'tts.local.idleTimeoutMinutes')
    expect(hit!.value).toBe('0')
  })

  // ===== Reference voice clip (JCLAW-865) =====
  //
  // Chatterbox and Qwen3-TTS have no named speakers, so the voice picker renders
  // empty for them and a clip is the only way to choose a voice. The control
  // therefore appears exactly where that picker would be, and only for models
  // that can actually use it.

  it('offers a reference clip upload for a cloning model', async () => {
    setupApi({ engine: 'sidecar', sidecarModel: 'chatterbox' })
    const c = await mountSettingsSection('speech')

    expect(c.find('input[aria-label="Reference voice clip"]').exists()).toBe(true)
    expect(c.text()).toContain('model default')
  })

  it('does not offer a clip for a model with named voices', async () => {
    // Kokoro selects by name; a clip would be meaningless and the picker is enough.
    setupApi({ engine: 'sidecar', sidecarModel: 'kokoro' })
    const c = await mountSettingsSection('speech')

    expect(c.find('input[aria-label="Reference voice clip"]').exists()).toBe(false)
  })

  it('shows the active clip filename and a way to remove it', async () => {
    setupApi({ engine: 'sidecar', sidecarModel: 'chatterbox', referenceVoice: 'reference.wav' })
    const c = await mountSettingsSection('speech')

    expect(c.text()).toContain('reference.wav')
    const clear = c.findAll('button').find(b => b.text() === 'Clear')
    expect(clear).toBeTruthy()
  })

  it('offers no Clear button when no clip is set', async () => {
    setupApi({ engine: 'sidecar', sidecarModel: 'chatterbox', referenceVoice: null })
    const c = await mountSettingsSection('speech')

    const clear = c.findAll('button').find(b => b.text() === 'Clear')
    expect(clear).toBeFalsy()
  })

  it('hides the clip control for the JVM engine entirely', async () => {
    setupApi({ engine: 'jvm' })
    const c = await mountSettingsSection('speech')

    expect(c.find('input[aria-label="Reference voice clip"]').exists()).toBe(false)
  })

  // JCLAW-868 — recording a clip from the mic, alongside uploading one.
  describe('reference clip recording', () => {
    afterEach(() => {
      vi.unstubAllGlobals()
      Reflect.deleteProperty(navigator, 'mediaDevices')
    })

    const findButton = (c: Awaited<ReturnType<typeof mountSettingsSection>>, label: string) =>
      c.findAll('button').find(b => b.text() === label)

    it('offers Record wherever it offers Upload', async () => {
      setupApi({ engine: 'sidecar', sidecarModel: 'chatterbox' })
      const c = await mountSettingsSection('speech')

      expect(findButton(c, 'Record')).toBeTruthy()
    })

    it('offers no Record button for a model that cannot clone', async () => {
      setupApi({ engine: 'sidecar', sidecarModel: 'kokoro' })
      const c = await mountSettingsSection('speech')

      expect(findButton(c, 'Record')).toBeFalsy()
    })

    it('surfaces a reason when the browser exposes no microphone', async () => {
      // No mediaDevices in the test environment, which is also the real
      // behaviour on an insecure origin — it must not fail silently.
      setupApi({ engine: 'sidecar', sidecarModel: 'chatterbox' })
      const c = await mountSettingsSection('speech')

      await findButton(c, 'Record')!.trigger('click')
      await flushPromises()

      expect(c.text()).toContain('microphone capture is not available in this browser')
    })

    it('encodes the recording and posts it as recording.wav', async () => {
      const nodes: Array<{ port: { onmessage: ((e: { data: Float32Array }) => void) | null } }> = []
      const stopTrack = vi.fn()

      class FakeWorkletNode {
        port: { onmessage: ((e: { data: Float32Array }) => void) | null } = { onmessage: null }
        connect = vi.fn()
        disconnect = vi.fn()
        constructor() {
          nodes.push(this)
        }
      }
      class FakeAudioContext {
        sampleRate = 48000
        destination = {}
        audioWorklet = { addModule: vi.fn().mockResolvedValue(undefined) }
        createMediaStreamSource = vi.fn(() => ({ connect: vi.fn(), disconnect: vi.fn() }))
        close = vi.fn().mockResolvedValue(undefined)
      }
      vi.stubGlobal('AudioContext', FakeAudioContext)
      vi.stubGlobal('AudioWorkletNode', FakeWorkletNode)
      Object.defineProperty(navigator, 'mediaDevices', {
        configurable: true,
        value: { getUserMedia: vi.fn().mockResolvedValue({ getTracks: () => [{ stop: stopTrack }] }) },
      })

      let posted = 0
      registerEndpoint('/api/tts/reference-voice', {
        method: 'POST',
        handler: () => {
          posted++
          return { status: 'ok', filename: 'recording.wav' }
        },
      })
      setupApi({ engine: 'sidecar', sidecarModel: 'chatterbox' })
      const c = await mountSettingsSection('speech')

      await findButton(c, 'Record')!.trigger('click')
      await flushPromises()
      expect(findButton(c, 'Stop')).toBeTruthy()

      nodes[0]!.port.onmessage!({ data: new Float32Array(4096) })
      await flushPromises()

      await findButton(c, 'Stop')!.trigger('click')
      await flushPromises()

      expect(posted).toBe(1)
      // The mic must not outlive the recording.
      expect(stopTrack).toHaveBeenCalled()
    })
  })
})

describe('Settings page — Speech radios put the saved choice back when a save fails (JCLAW-1221)', () => {
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

  it('text-to-speech engine', async () => {
    setupApi({ engine: 'sidecar' })
    failSaves()
    const component = await mountSettingsSection('speech')
    await chooseAndExpectPutBack(component, '#tts-engine-jvm', '#tts-engine-sidecar')
  })
})
