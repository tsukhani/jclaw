<script setup lang="ts">
// Speech (text-to-speech / read-aloud) settings panel (JCLAW-789/793). The
// operator picks the TTS engine — Sidecar (Qwen3-TTS / Kokoro via the Python
// sidecar) or JVM-native (sherpa-onnx, in-process) — and can switch at will;
// the backend TtsRouter reads tts.engine per request, so a change takes effect
// on the next read-aloud with no restart. Runtime engine availability + per-
// model readiness come from /api/tts/state, lazy-fetched so the panel paints
// immediately (mirrors the Transcription panel). Persisted selection reads from
// the shared config store and writes go through /api/config.
const { configValue, saveField, saving } = useSettingsConfig()

type TtsVoiceEntry = { id: string, label: string }
type TtsModelEntry = {
  id: string
  displayName: string
  approxSizeMb: number
  present: boolean
  downloading: boolean
  voices: TtsVoiceEntry[]
  /** Picks its speaker from a reference clip rather than a preset (JCLAW-865). */
  supportsCloning: boolean
}
type TtsEngineEntry = {
  id: string
  displayName: string
  available: boolean
  status: string
  model: string
  models: TtsModelEntry[]
}
type TtsState = { engine: string, engines: TtsEngineEntry[], referenceVoice?: string | null }

const { data: ttsState, refresh: refreshTtsState, status: ttsStateStatus }
  = useLazyFetch<TtsState>('/api/tts/state')
const ttsStateLoading = computed(() => ttsStateStatus.value === 'pending' && !ttsState.value)

const selectedEngine = computed(() => configValue('tts.engine', 'sidecar'))
const sidecarModel = computed(() => configValue('tts.sidecar.model', 'qwen3-0.6b'))
const jvmModel = computed(() => configValue('tts.jvm.model', 'piper-en_US-amy-low'))

const sidecarEntry = computed(() => ttsState.value?.engines?.find(e => e.id === 'sidecar') ?? null)
const activeEntry = computed(() => ttsState.value?.engines?.find(e => e.id === selectedEngine.value) ?? null)

// The model id + its status for whichever engine is currently selected.
const activeModelId = computed(() => (selectedEngine.value === 'jvm' ? jvmModel.value : sidecarModel.value))
const activeModelStatus = computed<TtsModelEntry | null>(() =>
  activeEntry.value?.models?.find(m => m.id === activeModelId.value) ?? null,
)

// Speaker voice for the active model (JCLAW-846). The list is per-model; the
// selection persists in the per-engine key tts.<engine>.voice.
const activeVoices = computed<TtsVoiceEntry[]>(() => activeModelStatus.value?.voices ?? [])
const selectedVoice = computed(() => configValue(`tts.${selectedEngine.value}.voice`, ''))

async function setEngine(value: string) {
  await saveField('tts.engine', value)
  refreshTtsState()
}
async function setModel(engine: string, value: string) {
  await saveField(`tts.${engine}.model`, value)
  // Voices are per-model (a Kokoro name is meaningless for Qwen3), so reset the
  // speaker to the model default whenever the model changes.
  await saveField(`tts.${engine}.voice`, '')
  refreshTtsState()
}
async function setVoice(value: string) {
  await saveField(`tts.${selectedEngine.value}.voice`, value)
}

// JCLAW-865: Chatterbox and Qwen3-TTS have no named voices — cloning from a short
// reference clip IS their voice selection. Without one they are stuck on whatever
// speaker the model ships with, which is why their Voice picker renders empty.
const supportsCloning = computed(() => activeModelStatus.value?.supportsCloning === true)
const referenceVoice = computed(() => ttsState.value?.referenceVoice ?? null)
const refError = ref('')

/** Prefer the server's reason (wrong format, too large) over a generic failure —
 *  it is actionable, and the operator still has the clip to hand. */
function reasonFor(e: unknown, fallback: string) {
  return (e as { data?: { message?: string } })?.data?.message || (e as Error)?.message || fallback
}

async function postReferenceVoice(file: File) {
  refError.value = ''
  saving.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    await $fetch('/api/tts/reference-voice', { method: 'POST', body })
    refreshTtsState()
  }
  catch (e) {
    refError.value = reasonFor(e, 'upload failed')
  }
  finally {
    saving.value = false
  }
}

async function uploadReferenceVoice(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    await postReferenceVoice(file)
  }
  finally {
    input.value = '' // let the same file be re-picked after a rejection
  }
}

// JCLAW-868: record a clip here instead of making the operator find or make one
// elsewhere first. Posts to the same endpoint — the recording is just a file.
const { recording, seconds, maxSeconds, start: startCapture, stop: stopCapture }
  = useVoiceClipRecorder({ onLimit: () => void finishRecording() })

async function toggleRecording() {
  if (recording.value) {
    await finishRecording()
    return
  }
  refError.value = ''
  try {
    await startCapture()
  }
  catch (e) {
    refError.value = reasonFor(e, 'could not start recording (mic permission?)')
  }
}

async function finishRecording() {
  const clip = stopCapture()
  if (!clip) {
    refError.value = 'nothing was recorded'
    return
  }
  await postReferenceVoice(new File([clip], 'recording.wav', { type: 'audio/wav' }))
}

async function clearReferenceVoice() {
  refError.value = ''
  saving.value = true
  try {
    await $fetch('/api/tts/reference-voice', { method: 'DELETE' })
    refreshTtsState()
  }
  finally { saving.value = false }
}

// JCLAW-863: how long the sidecar keeps the model loaded while idle. The key has
// always existed and been read at spawn (LocalSidecarDaemon), but was reachable
// only by editing the database — so the one setting that actually governs
// first-reply latency was invisible. Default mirrors the backend's.
const idleTimeoutMinutes = computed(() => configValue('tts.local.idleTimeoutMinutes', '15'))

async function setIdleTimeout(value: string) {
  // Clamp rather than reject: the field is a nudge, and a stray keystroke should
  // not persist something the daemon will read as nonsense. 0 means never unload.
  const n = Number.parseInt(value, 10)
  const safe = Number.isNaN(n) ? 15 : Math.min(1440, Math.max(0, n))
  await saveField('tts.local.idleTimeoutMinutes', String(safe))
}
async function downloadModel(id: string) {
  if (!id) return
  saving.value = true
  try {
    await $fetch(`/api/tts/models/${encodeURIComponent(id)}/download`, { method: 'POST', body: {} })
    startTtsPolling()
  }
  finally { saving.value = false }
}

// Poll /api/tts/state while any model is provisioning; stop once settled.
let ttsPollTimer: ReturnType<typeof setInterval> | null = null
function anyDownloadInFlight(): boolean {
  return (ttsState.value?.engines ?? []).some(e => e.models.some(m => m.downloading))
}
function startTtsPolling() {
  if (ttsPollTimer != null) return
  ttsPollTimer = setInterval(async () => {
    await refreshTtsState()
    if (!anyDownloadInFlight()) stopTtsPolling()
  }, 1500)
}
function stopTtsPolling() {
  if (ttsPollTimer != null) {
    clearInterval(ttsPollTimer)
    ttsPollTimer = null
  }
}
// A download already in flight when the panel opens becomes visible once the
// lazy state resolves — resume polling then.
watch(ttsState, () => {
  if (anyDownloadInFlight()) startTtsPolling()
})
onUnmounted(() => stopTtsPolling())
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Speech
    </h2>
    <p class="text-xs text-fg-muted">
      Read messages aloud with text-to-speech. Pick the engine that fits your setup — the
      quality-first <span class="text-fg-muted">Sidecar</span> (Qwen3-TTS / Kokoro, runs a local
      Python process, needs <span class="font-mono">uv</span>) or the <span class="text-fg-muted">JVM-native</span>
      engine (sherpa-onnx, runs in-process with no sidecar). You can switch at any time; the change
      applies to the next read-aloud.
    </p>

    <!-- Active-engine status line. -->
    <div class="px-3 py-2 text-[11px] border bg-muted border-border text-fg-muted">
      <template v-if="ttsStateLoading">
        Checking speech engines…
      </template>
      <template v-else-if="activeEntry">
        Active: <span class="font-medium text-fg-primary">{{ activeEntry.displayName }}</span> — {{ activeEntry.status }}
      </template>
      <template v-else>
        Speech engine status unavailable.
      </template>
    </div>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 border-b border-border">
        <span class="text-sm font-medium text-fg-strong">Engine</span>
      </div>

      <fieldset class="min-w-0">
        <legend class="sr-only">
          Text-to-speech engine
        </legend>
        <div class="divide-y divide-border">
          <label
            for="tts-engine-sidecar"
            class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
          >
            <input
              id="tts-engine-sidecar"
              type="radio"
              name="tts-engine"
              value="sidecar"
              :checked="selectedEngine === 'sidecar'"
              class="accent-emerald-600"
              @change="setEngine('sidecar')"
            >
            <span class="flex-1 text-sm text-fg-primary">
              Sidecar
              <span class="text-fg-muted">— Qwen3-TTS / Kokoro</span>
            </span>
            <span
              class="text-[10px] px-1 border"
              :class="sidecarEntry && !sidecarEntry.available
                ? 'text-amber-700 dark:text-amber-400 border-amber-400/30'
                : 'text-fg-muted border-input'"
            >{{ sidecarEntry && !sidecarEntry.available ? 'needs uv' : 'quality' }}</span>
          </label>
          <label
            for="tts-engine-jvm"
            class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
          >
            <input
              id="tts-engine-jvm"
              type="radio"
              name="tts-engine"
              value="jvm"
              :checked="selectedEngine === 'jvm'"
              class="accent-emerald-600"
              @change="setEngine('jvm')"
            >
            <span class="flex-1 text-sm text-fg-primary">
              JVM-native
              <span class="text-fg-muted">— sherpa-onnx</span>
            </span>
            <span class="text-[10px] px-1 border text-fg-muted border-input">no sidecar</span>
          </label>
        </div>
      </fieldset>

      <!-- Voice/model for the selected engine. -->
      <div class="border-t border-border">
        <div
          v-if="ttsStateLoading"
          class="px-4 py-2.5 text-xs text-fg-muted italic"
        >
          Loading voices…
        </div>
        <div
          v-else
          class="px-4 py-2.5 flex items-center gap-3"
        >
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Model</span>
          <select
            :value="activeModelId"
            aria-label="Text-to-speech model"
            class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
            @change="setModel(selectedEngine, ($event.target as HTMLSelectElement).value)"
          >
            <option
              v-for="m in (activeEntry?.models ?? [])"
              :key="m.id"
              :value="m.id"
            >
              {{ m.displayName }} (~{{ m.approxSizeMb }} MB)
            </option>
          </select>

          <!-- JVM models are disk-provisioned here; sidecar weights auto-pull. -->
          <template v-if="selectedEngine === 'jvm'">
            <span
              v-if="activeModelStatus?.present"
              class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1 shrink-0"
            >Ready</span>
            <span
              v-else-if="activeModelStatus?.downloading"
              class="text-xs text-fg-muted italic shrink-0"
            >downloading…</span>
            <button
              v-else
              type="button"
              class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors shrink-0"
              :disabled="saving"
              @click="downloadModel(activeModelId)"
            >
              Download
            </button>
          </template>
          <template v-else>
            <span class="text-[10px] px-1 border text-fg-muted border-input shrink-0">auto</span>
          </template>
        </div>

        <!-- Speaker voice for the selected model (JCLAW-846) — only when the model offers presets. -->
        <div
          v-if="!ttsStateLoading && activeVoices.length"
          class="px-4 py-2.5 flex items-center gap-3 border-t border-border"
        >
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Voice</span>
          <select
            :value="selectedVoice"
            aria-label="Text-to-speech speaker voice"
            class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
            @change="setVoice(($event.target as HTMLSelectElement).value)"
          >
            <option value="">
              Default
            </option>
            <option
              v-for="v in activeVoices"
              :key="v.id"
              :value="v.id"
            >
              {{ v.label }}
            </option>
          </select>
        </div>

        <!--
          Reference voice (JCLAW-865). Shown INSTEAD of the voice picker, not
          beside it: these models have no named speakers, so the picker above
          renders nothing and a clip is the only way to choose a voice. Without
          one the model is fixed on whatever speaker it ships with.
        -->
        <div
          v-if="!ttsStateLoading && supportsCloning"
          class="px-4 py-2.5 flex items-center gap-3 border-t border-border"
        >
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Voice clip</span>
          <span
            v-if="recording"
            role="status"
            aria-live="polite"
            class="flex-1 text-sm font-mono text-red-600 dark:text-red-400"
          >Recording… {{ seconds }}s / {{ maxSeconds }}s</span>
          <span
            v-else-if="referenceVoice"
            class="flex-1 text-sm text-fg-strong font-mono truncate"
          >{{ referenceVoice }}</span>
          <span
            v-else
            class="flex-1 text-sm text-fg-muted italic"
          >model default — record or upload a clip to clone a voice</span>
          <button
            type="button"
            class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors shrink-0"
            :disabled="saving"
            @click="toggleRecording"
          >
            {{ recording ? 'Stop' : 'Record' }}
          </button>
          <label
            v-if="!recording"
            for="tts-reference-voice"
            class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors shrink-0 cursor-pointer"
          >
            {{ referenceVoice ? 'Replace' : 'Upload' }}
            <input
              id="tts-reference-voice"
              type="file"
              accept=".wav,.mp3,.flac,.m4a,.ogg,audio/*"
              aria-label="Reference voice clip"
              class="hidden"
              :disabled="saving"
              @change="uploadReferenceVoice"
            >
          </label>
          <button
            v-if="referenceVoice && !recording"
            type="button"
            class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-muted transition-colors shrink-0"
            :disabled="saving"
            @click="clearReferenceVoice"
          >
            Clear
          </button>
        </div>
        <p
          v-if="!ttsStateLoading && supportsCloning && refError"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-red-600 dark:text-red-400"
        >
          {{ refError }}
        </p>
        <p
          v-else-if="!ttsStateLoading && supportsCloning"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
        >
          A few seconds of clean speech works best — this model clones the speaker
          rather than offering named voices. WAV, MP3, FLAC, M4A or OGG, under 10&nbsp;MB.
        </p>

        <!--
          Keep-warm window (JCLAW-863). The sidecar unloads its model after this
          many idle minutes, and reloading is the expensive part — a heavy model
          like Chatterbox measures ~18-52s cold versus ~2-4s warm. Prewarming can
          only hide that when it gets a head start, so for a genuinely responsive
          first turn the model has to still be resident. That costs RAM for as
          long as it is held, which is the operator's call, not a default.
          Sidecar-only: the JVM engine loads in-process and has no daemon to idle.
        -->
        <div
          v-if="selectedEngine === 'sidecar'"
          class="px-4 py-2.5 flex items-center gap-3 border-t border-border"
        >
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Keep warm</span>
          <input
            :value="idleTimeoutMinutes"
            type="number"
            min="0"
            max="1440"
            step="5"
            aria-label="Minutes the TTS sidecar stays loaded while idle"
            class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
            :disabled="saving"
            @change="setIdleTimeout(($event.target as HTMLInputElement).value)"
          >
          <span class="text-[11px] text-fg-muted">
            minutes idle before the model unloads — 0 never unloads
          </span>
        </div>
        <p
          v-if="selectedEngine === 'sidecar'"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
        >
          Longer keeps the first reply fast but holds the model in RAM
          (Chatterbox is ~3&nbsp;GB); shorter frees memory but makes the next
          reply after a gap pay the load again. Takes effect the next time the
          sidecar starts.
        </p>
        <p
          v-if="selectedEngine === 'sidecar'"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
        >
          Sidecar weights download automatically from Hugging Face on first read-aloud (needs
          <span class="font-mono">uv</span> on PATH). Qwen3-TTS supports voice cloning and is
          GPU-capable; Kokoro is the lighter option.
        </p>
        <p
          v-else
          class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
        >
          Runs in-process via sherpa-onnx — no Python, no sidecar. The voice downloads once (button
          above, or automatically on first read-aloud) and then synthesizes on CPU. Piper is tiny
          and fast; Kokoro adds languages.
        </p>
      </div>
    </div>
  </div>
</template>
