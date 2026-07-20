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

type TtsModelEntry = {
  id: string
  displayName: string
  approxSizeMb: number
  present: boolean
  downloading: boolean
}
type TtsEngineEntry = {
  id: string
  displayName: string
  available: boolean
  status: string
  model: string
  models: TtsModelEntry[]
}
type TtsState = { engine: string, engines: TtsEngineEntry[] }

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

async function setEngine(value: string) {
  await saveField('tts.engine', value)
  refreshTtsState()
}
async function setModel(engine: string, value: string) {
  await saveField(`tts.${engine}.model`, value)
  refreshTtsState()
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

      <fieldset>
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
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Voice</span>
          <select
            :value="activeModelId"
            aria-label="Text-to-speech voice model"
            class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
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
