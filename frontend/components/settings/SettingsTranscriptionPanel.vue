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
  status: 'ABSENT' | 'DOWNLOADING' | 'VERIFYING' | 'AVAILABLE' | 'ERROR'
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
const { data: transcriptionState, refresh: refreshTranscriptionState }
  = await useFetch<TranscriptionState>('/api/transcription/state')

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

// JCLAW-654: diarization runs through an audio-capable CLOUD chat model —
// local diarization was removed after the measured tier comparison. The
// operator picks provider + model here; the diarize_audio tool errors
// clearly when unset. Mirrors the Image Captioning provider/model pattern.
const diarizationProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.diarization.provider')?.value ?? '',
)
const diarizationModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.diarization.model')?.value ?? '',
)
const diarizationEnabled = computed(() => diarizationProvider.value.trim().length > 0)
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

const selectedLocalModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'transcription.localModel')?.value ?? 'small.en',
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
onMounted(() => {
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
      runs <span class="font-mono">whisper.cpp</span> locally and downloads its model
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
        Active: Self-Hosted Whisper ({{ selectedLocalModelStatus?.displayName || 'default model' }}).
      </template>
      <template v-else>
        Transcription is off — enable it and pick a backend below. Until then, audio attachments
        reach the LLM without a transcript.
      </template>
    </div>

    <!-- Master toggle: ON when transcription.provider is non-empty. -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3 cursor-pointer">
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
        <span class="text-sm font-medium text-fg-strong">Enable transcription</span>
        <span class="ml-auto text-[11px] text-fg-muted">
          {{ transcriptionEnabled ? 'on' : 'off' }}
        </span>
      </div>
    </div>

    <template v-if="transcriptionEnabled">
      <div
        v-if="transcriptionState && !transcriptionState.ffmpegAvailable && selectedTranscriptionProvider === 'whisper-local'"
        class="px-3 py-2 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800/50 text-[11px] text-amber-800 dark:text-amber-300"
      >
        ⚠ <span class="font-mono">ffmpeg</span> is not on PATH. The self-hosted Whisper backend
        needs ffmpeg to convert audio attachments to PCM before inference.
        Install it (e.g. <span class="font-mono">brew install ffmpeg</span>) and reload this page.
        <span
          v-if="transcriptionState?.ffmpegReason"
          class="block opacity-70 mt-0.5"
        >Probe: {{ transcriptionState.ffmpegReason }}</span>
      </div>
      <fieldset class="bg-surface-elevated border border-border">
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
            <span class="flex-1 text-sm text-fg-primary">Self-Hosted Whisper</span>
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
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Model size</span>
          <select
            :value="selectedLocalModel"
            aria-label="Whisper model size"
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
        </div>
        <div
          v-if="selectedLocalModelStatus?.status === 'ERROR' && selectedLocalModelStatus?.error"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-red-700 dark:text-red-400 break-words"
        >
          {{ selectedLocalModelStatus.error }}
        </div>
      </div>
    </template>

    <!-- JCLAW-654: Diarization = audio-capable cloud chat model. Outside the
           master-toggle template — the diarize_audio tool works whatever the
           inbound-transcription provider is. -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-3">
        <span class="text-sm font-medium text-fg-strong flex-1">Diarization</span>
        <button
          type="button"
          :aria-pressed="diarizationEnabled"
          aria-label="Enable speaker diarization via a cloud audio model"
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
        Who-said-what transcripts (the diarize-audio tool) are produced by an
        <span class="font-medium">audio-capable</span> chat model — the recording is sent to it
        with a verbatim-diarization prompt. Pick a provider and one of its audio-capable models
        below (API keys come from <span class="text-fg-muted">LLM Providers</span> above).
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
              for="diarization-provider-local"
              class="px-4 py-2.5 flex items-center gap-3 cursor-not-allowed opacity-50"
              title="Coming in a future release: fully on-device diarization once local audio models mature (JCLAW-656)"
            >
              <input
                id="diarization-provider-local"
                type="radio"
                name="diarization-provider"
                value="local"
                disabled
                class="accent-emerald-600"
              >
              <span class="flex-1 text-sm text-fg-primary">Local audio model (on-device)</span>
              <span class="text-[10px] px-1 border text-fg-muted border-input">unavailable</span>
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
          </div>
        </fieldset>
        <div class="border-t border-border">
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
      </template>
    </div>
  </div>
</template>
