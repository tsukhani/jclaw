<script setup lang="ts">
// Transcription + diarization settings panel (JCLAW-680 second pass).
// Moved verbatim from pages/settings.vue. Runtime ffmpeg-probe + per-model
// download state come from /api/transcription/state; persisted selection reads
// from the shared config store and writes go through /api/config, exactly as the
// pre-extraction monolith did. Provider API-key checks and the audio-model
// catalog are injected from the shared settings-config context.
import type { ProviderModelDef } from '~/types/api'

const { configData, saving, refresh, getProviderModels, apiKeyConfigured } = useSettingsConfig()

const openrouterApiKeyConfigured = computed(() => apiKeyConfigured('openrouter'))
const openaiApiKeyConfigured = computed(() => apiKeyConfigured('openai'))

// Runtime state — ffmpeg probe + per-model download status — comes from
// /api/transcription/state. Persisted selection (provider radio + local
// model dropdown) reads from configData via the standard managed-key path,
// and writes go through /api/config like every other section.
type TranscriptionModelStatus = {
  id: string
  displayName: string
  approxSizeMb: number
  status: 'ABSENT' | 'DOWNLOADING' | 'VERIFYING' | 'AVAILABLE' | 'ERROR' | 'UNAVAILABLE'
  bytesDownloaded: number
  totalBytes: number
  error: string | null
}
type TranscriptionState = {
  provider: string | null
  localModel: string | null
  ffmpegAvailable: boolean
  ffmpegReason: string | null
  models: TranscriptionModelStatus[]
}
// Lazy (non-blocking) fetch: /api/transcription/state cold-boots the Python ASR
// sidecar (uv run + heavy ML imports, ~seconds; the sidecar idle-drains after
// ~15 min so this recurs). A top-level `await` here would suspend the whole
// panel behind the settings-page "Loading…" fallback for that entire duration.
// useLazyFetch lets the panel paint immediately; the model-status area shows a
// "starting engine" indicator until the request resolves (JCLAW UI perf fix).
const { data: transcriptionState, refresh: refreshTranscriptionState, status: transcriptionStateStatus }
  = useLazyFetch<TranscriptionState>('/api/transcription/state')
// Initial load only (not a refresh, which keeps stale data visible): the engine
// is booting and no per-model status has arrived yet.
const transcriptionStateLoading = computed(() =>
  transcriptionStateStatus.value === 'pending' && !transcriptionState.value,
)

const selectedTranscriptionProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.provider')?.value ?? '',
)
// Master toggle: presence of a non-empty transcription.provider IS the
// "enabled" state. No separate config key needed; toggling off clears the
// value and toggling on defaults to whisper-local (the only backend that
// works without external setup, so it's the safest fresh-enable target).
const transcriptionEnabled = computed(() =>
  selectedTranscriptionProvider.value.trim().length > 0,
)
// Which backend is active, for the status line (mirrors captionActiveBackend).
const transcriptionIsCloud = computed(() =>
  selectedTranscriptionProvider.value === 'openai' || selectedTranscriptionProvider.value === 'openrouter',
)
const transcriptionActiveBackend = computed(() => {
  if (transcriptionIsCloud.value) return 'cloud'
  if (selectedTranscriptionProvider.value === 'whisper-local') return 'local'
  return 'none'
})
async function toggleTranscriptionEnabled() {
  saving.value = true
  try {
    const next = transcriptionEnabled.value ? '' : 'whisper-local'
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.provider', value: next } })
    refresh()
  }
  finally { saving.value = false }
}

// Diarization runs either through an audio-capable chat model — cloud
// (OpenRouter/OpenAI) or local (llama.cpp/vLLM), which need an audio-model
// pick — or through the fully on-device pyannote sidecar (JCLAW-565 revival),
// which has one fixed model and needs no picker. The operator picks the
// provider here; the diarize_audio tool errors clearly when unset.
const diarizationProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.diarization.provider')?.value ?? '',
)
const diarizationModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.diarization.model')?.value ?? '',
)
const diarizationEnabled = computed(() => diarizationProvider.value.trim().length > 0)
// The fully on-device pyannote path: no audio-model picker (one fixed model),
// no API key — its speaker turns are fused with the local ASR transcript.
const diarizationIsLocal = computed(() => diarizationProvider.value === 'pyannote-local')
// Audio-capable models the operator configured for the chosen provider — the picker
// filters provider.{name}.models to the audio-tagged ones, so the operator selects
// rather than free-types (a non-audio model would just fail on every recording).
const diarizationAudioModels = computed<ProviderModelDef[]>(() => {
  if (!diarizationEnabled.value) return []
  return getProviderModels(diarizationProvider.value).filter(m => m.supportsAudio === true)
})
const diarizationAudioModelOptions = computed(() =>
  diarizationAudioModels.value.map(m => ({ id: m.id, label: m.name || m.id })),
)
const diarizationModelOrphaned = computed(() =>
  diarizationModel.value !== '' && !diarizationAudioModelOptions.value.some(o => o.id === diarizationModel.value),
)
const diarizationModelSelectValue = computed(() => (diarizationModelOrphaned.value ? '' : diarizationModel.value))
async function toggleDiarizationEnabled() {
  saving.value = true
  try {
    const defaultProvider = openrouterApiKeyConfigured.value ? 'openrouter' : 'openai'
    const next = diarizationEnabled.value ? '' : defaultProvider
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.diarization.provider', value: next } })
    refresh()
  }
  finally { saving.value = false }
}
async function setDiarizationProvider(value: string) {
  saving.value = true
  try {
    // Reset the model on a provider switch — independent keys, fire in parallel.
    await Promise.all([
      $fetch('/api/config', { method: 'POST', body: { key: 'transcription.diarization.provider', value } }),
      $fetch('/api/config', { method: 'POST', body: { key: 'transcription.diarization.model', value: '' } }),
    ])
    refresh()
  }
  finally { saving.value = false }
}
async function setDiarizationModel(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.diarization.model', value } })
    refresh()
  }
  finally { saving.value = false }
}

// On-device (pyannote-local) per-turn emotion model (SER). Any HF
// audio-classification model works; blank = MERaLiON-SER-v1 (the sidecar default).
const selectedEmotionModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.diarization.emotionModel')?.value ?? '',
)
async function setEmotionModel(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.diarization.emotionModel', value } })
    refresh()
  }
  finally { saving.value = false }
}

const selectedLocalModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.localModel')?.value ?? 'small',
)

const selectedLocalModelStatus = computed<TranscriptionModelStatus | null>(() => {
  const id = selectedLocalModel.value
  return transcriptionState.value?.models?.find(m => m.id === id) ?? null
})
const selectedLocalModelDownloadPct = computed(() => {
  const s = selectedLocalModelStatus.value
  if (!s || s.totalBytes === 0) return 0
  return Math.min(100, Math.round((s.bytesDownloaded / s.totalBytes) * 100))
})

async function setTranscriptionProvider(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.provider', value } })
    refresh()
  }
  finally { saving.value = false }
}
async function setLocalModel(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.localModel', value } })
    refresh()
  }
  finally { saving.value = false }
}

// Cloud transcription model (transcription.model). The backend defaults to
// whisper-1 when blank; the operator can pick any model the provider's
// /audio/transcriptions endpoint accepts (e.g. gpt-4o-transcribe).
const selectedTranscriptionModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.model')?.value ?? '',
)
async function setTranscriptionModel(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'transcription.model', value } })
    refresh()
  }
  finally { saving.value = false }
}
async function downloadLocalModel(modelId: string) {
  saving.value = true
  try {
    await $fetch(`/api/transcription/models/${encodeURIComponent(modelId)}/download`,
      { method: 'POST', body: {} })
    // Kick off the polling loop so the progress bar starts moving.
    startTranscriptionPolling()
  }
  finally { saving.value = false }
}

// Poll /api/transcription/state every 1.5s while any model is in flight,
// stop once everything has settled. Single shared timer — multiple downloads
// (rare; the UI only lets the user kick off one at a time) all ride one
// poll. Cleans up on unmount.
let transcriptionPollTimer: ReturnType<typeof setInterval> | null = null
function anyDownloadInFlight(): boolean {
  const ms = transcriptionState.value?.models ?? []
  return ms.some(m => m.status === 'DOWNLOADING' || m.status === 'VERIFYING')
}
function startTranscriptionPolling() {
  if (transcriptionPollTimer != null) return
  transcriptionPollTimer = setInterval(async () => {
    await refreshTranscriptionState()
    if (!anyDownloadInFlight()) {
      stopTranscriptionPolling()
    }
  }, 1500)
}
function stopTranscriptionPolling() {
  if (transcriptionPollTimer != null) {
    clearInterval(transcriptionPollTimer)
    transcriptionPollTimer = null
  }
}
// The state loads lazily, so a download already in flight when the panel opens
// only becomes visible once the fetch resolves — resume the progress poll then.
watch(transcriptionState, () => {
  if (anyDownloadInFlight()) startTranscriptionPolling()
})
onUnmounted(() => stopTranscriptionPolling())
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Transcription
    </h2>
    <p class="text-xs text-fg-muted">
      Pair every audio attachment with a text transcript before it reaches the LLM.
      Audio-capable models still receive native audio; text-only models receive the
      transcript as text. Cloud options reuse the API keys configured in
      <span class="text-fg-muted">LLM Providers</span> above; the self-hosted option
      runs a local ASR model and downloads it
      from Hugging Face on first use.
    </p>

    <!-- Active-backend status line. -->
    <div
      class="px-3 py-2 text-[11px] border"
      :class="transcriptionEnabled
        ? 'bg-emerald-50/50 dark:bg-emerald-900/15 border-emerald-200 dark:border-emerald-800/50 text-emerald-800 dark:text-emerald-300'
        : 'bg-muted border-border text-fg-muted'"
    >
      <template v-if="transcriptionActiveBackend === 'cloud'">
        Active: cloud transcription via {{ selectedTranscriptionProvider }}.
      </template>
      <template v-else-if="transcriptionActiveBackend === 'local'">
        Active: Self-Hosted ASR ({{ selectedLocalModelStatus?.displayName || 'default model' }}).
      </template>
      <template v-else>
        Transcription is off — enable it and pick a backend below. Until then, audio attachments
        reach the LLM without a transcript.
      </template>
    </div>

    <!-- Master toggle: ON when transcription.provider is non-empty. -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-3">
        <span class="text-sm font-medium text-fg-strong flex-1">Enable transcription</span>
        <button
          type="button"
          :aria-pressed="transcriptionEnabled"
          aria-label="Enable transcription"
          :class="transcriptionEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="toggleTranscriptionEnabled"
        >
          <span
            :class="transcriptionEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-[11px] text-fg-muted">
          {{ transcriptionEnabled ? 'on' : 'off' }}
        </span>
      </div>

      <template v-if="transcriptionEnabled">
        <div
          v-if="transcriptionState && !transcriptionState.ffmpegAvailable && selectedTranscriptionProvider === 'whisper-local'"
          class="border-t border-border px-4 py-2 bg-amber-50 dark:bg-amber-900/20 text-[11px] text-amber-800 dark:text-amber-300"
        >
          ⚠ <span class="font-mono">ffmpeg</span> is not on PATH. The self-hosted Whisper backend
          needs ffmpeg to convert audio attachments to PCM before inference.
          Install it (e.g. <span class="font-mono">brew install ffmpeg</span>) and reload this page.
          <span
            v-if="transcriptionState?.ffmpegReason"
            class="block opacity-70 mt-0.5"
          >Probe: {{ transcriptionState.ffmpegReason }}</span>
        </div>
        <fieldset class="border-t border-border">
          <legend class="sr-only">
            Transcription backend
          </legend>
          <div class="divide-y divide-border">
            <label
              for="transcription-provider-openrouter"
              class="px-4 py-2.5 flex items-center gap-3"
              :class="openrouterApiKeyConfigured
                ? 'cursor-pointer'
                : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
              :title="openrouterApiKeyConfigured ? '' : 'Add an OpenRouter API key in LLM Providers above to enable.'"
            >
              <input
                id="transcription-provider-openrouter"
                type="radio"
                name="transcription-provider"
                value="openrouter"
                :checked="selectedTranscriptionProvider === 'openrouter'"
                :disabled="!openrouterApiKeyConfigured"
                class="accent-emerald-600"
                @change="setTranscriptionProvider('openrouter')"
              >
              <span
                class="flex-1 text-sm"
                :class="openrouterApiKeyConfigured
                  ? 'text-fg-primary'
                  : 'text-amber-800 dark:text-amber-300 opacity-80'"
              >OpenRouter</span>
              <span
                v-if="!openrouterApiKeyConfigured"
                class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
              >no API key — configure in LLM Providers</span>
            </label>
            <label
              for="transcription-provider-openai"
              class="px-4 py-2.5 flex items-center gap-3"
              :class="openaiApiKeyConfigured
                ? 'cursor-pointer'
                : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
              :title="openaiApiKeyConfigured ? '' : 'Add an OpenAI API key in LLM Providers above to enable.'"
            >
              <input
                id="transcription-provider-openai"
                type="radio"
                name="transcription-provider"
                value="openai"
                :checked="selectedTranscriptionProvider === 'openai'"
                :disabled="!openaiApiKeyConfigured"
                class="accent-emerald-600"
                @change="setTranscriptionProvider('openai')"
              >
              <span
                class="flex-1 text-sm"
                :class="openaiApiKeyConfigured
                  ? 'text-fg-primary'
                  : 'text-amber-800 dark:text-amber-300 opacity-80'"
              >OpenAI</span>
              <span
                v-if="!openaiApiKeyConfigured"
                class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
              >no API key — configure in LLM Providers</span>
            </label>
            <label
              for="transcription-provider-whisper-local"
              class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
            >
              <input
                id="transcription-provider-whisper-local"
                type="radio"
                name="transcription-provider"
                value="whisper-local"
                :checked="selectedTranscriptionProvider === 'whisper-local'"
                class="accent-emerald-600"
                @change="setTranscriptionProvider('whisper-local')"
              >
              <span class="flex-1 text-sm text-fg-primary">Self-Hosted ASR</span>
              <span
                class="text-[10px] px-1 border"
                :class="selectedTranscriptionProvider === 'whisper-local'
                  ? 'text-green-700 dark:text-green-400 border-green-400/30'
                  : 'text-fg-muted border-input'"
              >local</span>
            </label>
          </div>
        </fieldset>

        <div
          v-if="selectedTranscriptionProvider === 'whisper-local'"
          class="border-t border-border"
        >
          <div
            v-if="transcriptionStateLoading"
            class="px-4 py-2.5 text-xs text-fg-muted italic"
          >
            Starting the transcription engine… <span class="opacity-70">(first load spins up the local ASR sidecar)</span>
          </div>
          <div
            v-else
            class="px-4 py-2.5 flex items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Model</span>
            <select
              :value="selectedLocalModel"
              aria-label="ASR model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              @change="setLocalModel(($event.target as HTMLSelectElement).value)"
            >
              <option
                v-for="m in (transcriptionState?.models ?? [])"
                :key="m.id"
                :value="m.id"
              >
                {{ m.displayName }} (~{{ m.approxSizeMb }} MB)
              </option>
            </select>
            <template v-if="selectedLocalModelStatus?.status === 'ABSENT'">
              <button
                type="button"
                class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors"
                :disabled="saving"
                @click="downloadLocalModel(selectedLocalModel)"
              >
                Download
              </button>
            </template>
            <template v-else-if="selectedLocalModelStatus?.status === 'DOWNLOADING'">
              <div class="flex items-center gap-2">
                <div class="w-32 h-2 bg-muted border border-input overflow-hidden">
                  <div
                    class="h-full bg-emerald-600 transition-[width] duration-300"
                    :style="{ width: selectedLocalModelDownloadPct + '%' }"
                  />
                </div>
                <span class="text-xs font-mono text-fg-muted tabular-nums w-10 text-right">
                  {{ selectedLocalModelDownloadPct }}%
                </span>
              </div>
            </template>
            <template v-else-if="selectedLocalModelStatus?.status === 'VERIFYING'">
              <span class="text-xs text-fg-muted italic">Verifying SHA256…</span>
            </template>
            <template v-else-if="selectedLocalModelStatus?.status === 'AVAILABLE'">
              <span class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1">Ready</span>
            </template>
            <template v-else-if="selectedLocalModelStatus?.status === 'ERROR'">
              <button
                type="button"
                class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors"
                :disabled="saving"
                @click="downloadLocalModel(selectedLocalModel)"
              >
                Retry
              </button>
            </template>
            <template v-else-if="selectedLocalModelStatus?.status === 'UNAVAILABLE'">
              <span class="text-[10px] px-1 border text-amber-700 dark:text-amber-400 border-amber-400/30">unavailable</span>
            </template>
          </div>
          <div
            v-if="(selectedLocalModelStatus?.status === 'ERROR' || selectedLocalModelStatus?.status === 'UNAVAILABLE') && selectedLocalModelStatus?.error"
            class="px-4 pb-2.5 -mt-1 text-[11px] text-red-700 dark:text-red-400 break-words"
          >
            {{ selectedLocalModelStatus.error }}
          </div>
        </div>

        <!-- Cloud providers: pick the transcription model (blank = whisper-1). -->
        <div
          v-if="transcriptionIsCloud"
          class="border-t border-border"
        >
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Model</span>
            <input
              id="transcription-model"
              :value="selectedTranscriptionModel"
              list="transcription-model-suggestions"
              placeholder="whisper-1 (default)"
              aria-label="Cloud transcription model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              @change="setTranscriptionModel(($event.target as HTMLInputElement).value.trim())"
            >
            <datalist id="transcription-model-suggestions">
              <option value="whisper-1" />
              <option value="gpt-4o-transcribe" />
              <option value="gpt-4o-mini-transcribe" />
            </datalist>
          </div>
          <p class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted">
            The model on {{ selectedTranscriptionProvider }}'s
            <span class="font-mono">/audio/transcriptions</span> endpoint. Leave blank for
            <span class="font-mono">whisper-1</span>. Common options:
            <span class="font-mono">whisper-1</span>, <span class="font-mono">gpt-4o-transcribe</span>,
            <span class="font-mono">gpt-4o-mini-transcribe</span>.
          </p>
        </div>
      </template>
    </div>

    <!-- Diarization = audio-capable chat model (cloud or Ollama Local). Outside
           the master-toggle template — the diarize_audio tool works whatever the
           inbound-transcription provider is (JCLAW-654). -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-3">
        <span class="text-sm font-medium text-fg-strong flex-1">Diarization</span>
        <button
          type="button"
          :aria-pressed="diarizationEnabled"
          aria-label="Enable speaker diarization"
          :class="diarizationEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="toggleDiarizationEnabled"
        >
          <span
            :class="diarizationEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-[11px] text-fg-muted">{{ diarizationEnabled ? 'on' : 'off' }}</span>
      </div>
      <div class="px-4 pt-2.5 text-[11px] text-fg-muted">
        Who-said-what transcripts (the diarize-audio tool) come from an
        <span class="font-medium">audio-capable</span> chat model (cloud or local) — the recording
        is sent with a verbatim-diarization prompt — or from the fully on-device
        <span class="font-mono">pyannote</span> diarizer (speaker turns fused with the local ASR
        transcript, with optional per-turn emotion; no audio leaves the host). Pick a provider below
        (chat-model API keys come from <span class="text-fg-muted">LLM Providers</span> above).
        Ordinary voice-note transcription stays local and is unaffected.
      </div>
      <template v-if="diarizationEnabled">
        <fieldset class="border-t border-border mt-2.5">
          <legend class="sr-only">
            Diarization provider
          </legend>
          <div class="divide-y divide-border">
            <label
              for="diarization-provider-openrouter"
              class="px-4 py-2.5 flex items-center gap-3"
              :class="openrouterApiKeyConfigured
                ? 'cursor-pointer'
                : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
              :title="openrouterApiKeyConfigured ? '' : 'Add an OpenRouter API key in LLM Providers above to enable.'"
            >
              <input
                id="diarization-provider-openrouter"
                type="radio"
                name="diarization-provider"
                value="openrouter"
                :checked="diarizationProvider === 'openrouter'"
                :disabled="!openrouterApiKeyConfigured"
                class="accent-emerald-600"
                @change="setDiarizationProvider('openrouter')"
              >
              <span
                class="flex-1 text-sm"
                :class="openrouterApiKeyConfigured ? 'text-fg-primary' : 'text-amber-800 dark:text-amber-300 opacity-80'"
              >OpenRouter</span>
              <span
                v-if="!openrouterApiKeyConfigured"
                class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
              >no API key — configure in LLM Providers</span>
            </label>
            <label
              for="diarization-provider-openai"
              class="px-4 py-2.5 flex items-center gap-3"
              :class="openaiApiKeyConfigured
                ? 'cursor-pointer'
                : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
              :title="openaiApiKeyConfigured ? '' : 'Add an OpenAI API key in LLM Providers above to enable.'"
            >
              <input
                id="diarization-provider-openai"
                type="radio"
                name="diarization-provider"
                value="openai"
                :checked="diarizationProvider === 'openai'"
                :disabled="!openaiApiKeyConfigured"
                class="accent-emerald-600"
                @change="setDiarizationProvider('openai')"
              >
              <span
                class="flex-1 text-sm"
                :class="openaiApiKeyConfigured ? 'text-fg-primary' : 'text-amber-800 dark:text-amber-300 opacity-80'"
              >OpenAI</span>
              <span
                v-if="!openaiApiKeyConfigured"
                class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
              >no API key — configure in LLM Providers</span>
            </label>
            <label
              for="diarization-provider-llama-cpp"
              class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
              title="llama.cpp (llama-server) accepts audio input over its OpenAI-compatible API — the local audio-diarization path. Audio support is experimental upstream."
            >
              <input
                id="diarization-provider-llama-cpp"
                type="radio"
                name="diarization-provider"
                value="llama-cpp"
                :checked="diarizationProvider === 'llama-cpp'"
                class="accent-emerald-600"
                @change="setDiarizationProvider('llama-cpp')"
              >
              <span class="flex-1 text-sm text-fg-primary">llama.cpp</span>
              <span
                class="text-[10px] px-1 border"
                :class="diarizationProvider === 'llama-cpp'
                  ? 'text-green-700 dark:text-green-400 border-green-400/30'
                  : 'text-fg-muted border-input'"
              >local</span>
            </label>
            <label
              for="diarization-provider-vllm"
              class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
              title="vLLM audio input over the OpenAI-compatible API depends on the vLLM version and model — verify with your setup."
            >
              <input
                id="diarization-provider-vllm"
                type="radio"
                name="diarization-provider"
                value="vllm"
                :checked="diarizationProvider === 'vllm'"
                class="accent-emerald-600"
                @change="setDiarizationProvider('vllm')"
              >
              <span class="flex-1 text-sm text-fg-primary">vLLM</span>
              <span
                class="text-[10px] px-1 border"
                :class="diarizationProvider === 'vllm'
                  ? 'text-green-700 dark:text-green-400 border-green-400/30'
                  : 'text-fg-muted border-input'"
              >local</span>
            </label>
            <label
              for="diarization-provider-pyannote-local"
              class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
              title="On-device pyannote speaker diarization (community-1), fused with the local ASR transcript — no audio leaves the host. Speaker turns only, no emotion. Needs a Hugging Face token (shared with Image Generation) for the gated weights."
            >
              <input
                id="diarization-provider-pyannote-local"
                type="radio"
                name="diarization-provider"
                value="pyannote-local"
                :checked="diarizationProvider === 'pyannote-local'"
                class="accent-emerald-600"
                @change="setDiarizationProvider('pyannote-local')"
              >
              <span class="flex-1 text-sm text-fg-primary">pyannote (on-device)</span>
              <span
                class="text-[10px] px-1 border"
                :class="diarizationProvider === 'pyannote-local'
                  ? 'text-green-700 dark:text-green-400 border-green-400/30'
                  : 'text-fg-muted border-input'"
              >local</span>
            </label>
          </div>
        </fieldset>
        <div
          v-if="!diarizationIsLocal"
          class="border-t border-border"
        >
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Audio model</span>
            <select
              :value="diarizationModelSelectValue"
              aria-label="Diarization audio model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              @change="setDiarizationModel(($event.target as HTMLSelectElement).value)"
            >
              <option value="">
                (select an audio-capable model)
              </option>
              <option
                v-for="m in diarizationAudioModelOptions"
                :key="m.id"
                :value="m.id"
              >
                {{ m.label }}
              </option>
            </select>
          </div>
          <p
            v-if="diarizationAudioModels.length === 0"
            class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
          >
            No audio-capable models are configured for this provider. Add one under
            <span class="text-fg-muted">LLM Providers</span> above (models with audio input
            show an “Audio” badge in the chat model picker), then select it here.
          </p>
          <p
            v-if="diarizationModelOrphaned"
            class="px-4 pb-2.5 -mt-1 text-[11px] text-amber-700 dark:text-amber-400"
          >
            The saved model “{{ diarizationModel }}” is not marked audio-capable — pick one
            from the list so recordings can actually be heard.
          </p>
        </div>
        <div
          v-else
          class="border-t border-border"
        >
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Emotion model</span>
            <input
              id="diarization-emotion-model"
              :value="selectedEmotionModel"
              list="diarization-emotion-model-suggestions"
              placeholder="MERaLiON/MERaLiON-SER-v1 (default)"
              aria-label="On-device emotion (SER) model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              @change="setEmotionModel(($event.target as HTMLInputElement).value.trim())"
            >
            <datalist id="diarization-emotion-model-suggestions">
              <option value="MERaLiON/MERaLiON-SER-v1" />
              <option value="superb/wav2vec2-base-superb-er" />
              <option value="Dpngtm/wav2vec2-emotion-recognition" />
            </datalist>
          </div>
          <p class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted">
            On-device diarization uses
            <span class="font-mono">pyannote/speaker-diarization-community-1</span> (needs
            <span class="font-mono">uv</span> + a Hugging Face token, shared with
            <span class="text-fg-muted">Image Generation</span>). Per-turn emotion — when the
            diarize-audio tool is asked for it — runs the SER model above. Leave blank for
            <span class="font-mono">MERaLiON-SER-v1</span>, a robust multilingual default
            (English, Chinese, Malay, Tamil, Indonesian; 7 emotions + valence/arousal/dominance).
            Match the model to your audio — the wav2vec2 options are English, categorical-only, and
            can suit English-heavy content. Any Hugging Face audio-classification SER model works.
          </p>
        </div>
      </template>
    </div>
  </div>
</template>
