<script setup lang="ts">
// Video Interpretation settings panel (JCLAW-680 second pass).
// Moved verbatim from pages/settings.vue. One knob (frame-sample density)
// plus backend selection — a text-only chat model "watches" a video by
// sampling frames + captioning them, mirroring Transcription/Captioning.
// Reads the shared config store + provider-model catalog; derives the main
// agent's model as the default via its own (Nuxt-deduped) /api/agents fetch.
import type { Agent, ProviderModelDef } from '~/types/api'

const { configData, saving, refresh, getProviderModels, apiKeyConfigured } = useSettingsConfig()
const openrouterApiKeyConfigured = computed(() => apiKeyConfigured('openrouter'))
const { data: agentsList } = await useFetch<Agent[]>('/api/agents')
const mainAgent = computed(() => agentsList.value?.find(a => a.name === 'main') ?? null)

// One knob (frame-sample density) plus a read-only display of which of the three
// interpretation strategies the default ("main") agent's model would use — native
// video / multi-image / text summary — computed from the same supportsVideo /
// supportsVision flags the dispatcher routes on.
const VIDEO_FRAMES_DEFAULT = 8
const VIDEO_FRAMES_MIN = 2
const VIDEO_FRAMES_MAX = 32
const videoSampleFrames = computed(() => {
  const raw = configData.value?.entries?.find(e => e.key === 'video.sampleFrames')?.value
  if (!raw) return VIDEO_FRAMES_DEFAULT
  const n = Number.parseInt(raw, 10)
  if (!Number.isFinite(n)) return VIDEO_FRAMES_DEFAULT
  return Math.max(VIDEO_FRAMES_MIN, Math.min(VIDEO_FRAMES_MAX, n))
})
async function saveVideoSampleFrames(value: string | number) {
  saving.value = true
  try {
    const n = Math.max(VIDEO_FRAMES_MIN,
      Math.min(VIDEO_FRAMES_MAX, Number.parseInt(String(value), 10) || VIDEO_FRAMES_DEFAULT))
    await $fetch('/api/config', { method: 'POST', body: { key: 'video.sampleFrames', value: String(n) } })
    refresh()
  }
  finally { saving.value = false }
}
// Sampling density: one frame per N seconds of video (FrameSampler.video.secondsPerFrame).
// Lower = denser. The actual frame count = clamp(round(duration / secondsPerFrame), 2, sampleFrames),
// so this is what densifies SHORT clips (which otherwise floor at 2 frames) while sampleFrames caps
// long ones. Backend defaults/clamps: 10, [1, 60].
const VIDEO_SPF_DEFAULT = 10
const VIDEO_SPF_MIN = 1
const VIDEO_SPF_MAX = 60
const videoSecondsPerFrame = computed(() => {
  const raw = configData.value?.entries?.find(e => e.key === 'video.secondsPerFrame')?.value
  if (!raw) return VIDEO_SPF_DEFAULT
  const n = Number.parseInt(raw, 10)
  if (!Number.isFinite(n)) return VIDEO_SPF_DEFAULT
  return Math.max(VIDEO_SPF_MIN, Math.min(VIDEO_SPF_MAX, n))
})
async function saveVideoSecondsPerFrame(value: string | number) {
  saving.value = true
  try {
    const n = Math.max(VIDEO_SPF_MIN,
      Math.min(VIDEO_SPF_MAX, Number.parseInt(String(value), 10) || VIDEO_SPF_DEFAULT))
    await $fetch('/api/config', { method: 'POST', body: { key: 'video.secondsPerFrame', value: String(n) } })
    refresh()
  }
  finally { saving.value = false }
}
// The default model = the main agent's model (mirrors the Subagents section's resolution).
const defaultVideoModel = computed<ProviderModelDef | null>(() => {
  const a = mainAgent.value
  if (!a) return null
  return getProviderModels(a.modelProvider).find(m => m.id === a.modelId) ?? null
})
// Mirror of VideoUnderstandingDispatcher.strategyFor: a video-capable model (supportsVideo, from the
// provider's input_modalities) watches the clip natively via a video_url part; else vision → frames;
// else a captioned text summary. (Does not model the inline size cap — an over-cap clip degrades to
// frames at dispatch time.)
const defaultVideoStrategy = computed(() => {
  const m = defaultVideoModel.value
  if (m?.supportsVideo === true) return 'native'
  if (m?.supportsVision === true) return 'multiImage'
  return 'textSummary'
})

// Dedicated native-video model (mirrors caption/transcription). Presence of a non-empty
// video.provider is the master enable; when set, EVERY video routes to this model (a separate
// call → prose spliced into the chat message), so even a text-only chat model gets video.
// Unset → fall back to the chat model's own capability (defaultVideoStrategy above).
const videoProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'video.provider')?.value ?? '',
)
const videoModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'video.model')?.value ?? '',
)
const videoEnabled = computed(() => videoProvider.value.trim().length > 0)
// vLLM is self-hosted (no API key). It's offered as a video backend only when it's actually
// running and reachable, not merely configured — so we live-probe provider.vllm.baseUrl via
// GET /api/providers/vllm/reachable (a short GET /models with a 7s timeout). vllmConfigured gates
// whether we even probe; vllmReachable is the answer used to enable the radio.
const vllmConfigured = computed(() => {
  const v = configData.value?.entries?.find(e => e.key === 'provider.vllm.baseUrl')?.value
  return !!v && v.trim().length > 0
})
// Ollama (local and cloud) are MULTI_IMAGE video backends: no native video, so a vision model
// interprets sampled frames. Gated on a configured base URL (the universal requirement; the
// video-models endpoint tolerates a missing API key). If the daemon isn't running the model fetch
// surfaces an error rather than the radio being disabled — same graceful path as any provider.
const ollamaLocalConfigured = computed(() => {
  const v = configData.value?.entries?.find(e => e.key === 'provider.ollama-local.baseUrl')?.value
  return !!v && v.trim().length > 0
})
const ollamaCloudConfigured = computed(() => {
  const v = configData.value?.entries?.find(e => e.key === 'provider.ollama-cloud.baseUrl')?.value
  return !!v && v.trim().length > 0
})
const vllmReachable = ref(false)
const vllmReachableReason = ref<string | null>(null)
const vllmProbing = ref(false)
async function probeVllm() {
  if (!vllmConfigured.value) {
    vllmReachable.value = false
    vllmReachableReason.value = 'not configured'
    return
  }
  vllmProbing.value = true
  try {
    const r = await $fetch<{ reachable: boolean, modelCount: number, reason: string | null }>(
      '/api/providers/vllm/reachable',
    )
    vllmReachable.value = r.reachable
    vllmReachableReason.value = r.reachable ? null : (r.reason || 'not reachable')
  }
  catch {
    vllmReachable.value = false
    vllmReachableReason.value = 'probe failed'
  }
  finally { vllmProbing.value = false }
}
// Probe when the dedicated-model section is in use and vLLM is configured (on mount + whenever
// either condition flips). Cheap and lazy: no probe for the common "video off" or "no vLLM" case.
watch([videoEnabled, vllmConfigured], ([on, configured]) => {
  if (on && configured) probeVllm()
}, { immediate: true })
// Video-capable models the operator configured for the chosen provider — filtered to the
// supportsVideo-tagged ones (the same flag the dispatcher routes on), so the operator selects
// rather than free-types a model that may not handle video.
// The dedicated video model is picked from the provider's LIVE catalog, filtered to video-capable
// models (supportsVideo) — not the operator's manually-configured set, since a dedicated video model
// needn't be pre-added (VideoInterpretationClient calls it by id). Discovered lazily when the section
// is on + a provider is selected (see the watch below); GET /api/providers/{name}/video-models.
const videoModelOptions = ref<Array<{ id: string, label: string }>>([])
const videoModelsLoading = ref(false)
const videoModelsError = ref<string | null>(null)
async function discoverVideoModels() {
  const p = videoProvider.value
  if (!videoEnabled.value || !['openrouter', 'vllm', 'ollama-local', 'ollama-cloud'].includes(p)) {
    videoModelOptions.value = []
    return
  }
  // Don't probe vLLM's catalog until we know it's reachable (avoids a guaranteed-failing call).
  if (p === 'vllm' && !vllmReachable.value) {
    videoModelOptions.value = []
    return
  }
  videoModelsLoading.value = true
  videoModelsError.value = null
  try {
    const r = await $fetch<{ models: Array<{ id: string, name: string }> }>(`/api/providers/${p}/video-models`)
    videoModelOptions.value = (r.models || []).map(m => ({ id: m.id, label: m.name || m.id }))
  }
  catch (e: unknown) {
    const err = e as { data?: { message?: string }, message?: string } | undefined
    videoModelsError.value = err?.data?.message || err?.message || 'Failed to load video models'
    videoModelOptions.value = []
  }
  finally { videoModelsLoading.value = false }
}
// A saved video.model not in the discovered list (transient discovery failure, or a model the
// provider no longer flags video) stays selectable so the operator doesn't silently lose it.
const videoModelOrphaned = computed(() =>
  videoModel.value !== '' && !videoModelOptions.value.some(o => o.id === videoModel.value),
)
const videoModelSelectValue = computed(() => videoModel.value)
watch([videoEnabled, videoProvider, vllmReachable], () => {
  discoverVideoModels()
}, { immediate: true })

// Master toggle: off clears the provider (and model); on defaults to OpenRouter (the common
// video-capable-model host) — the operator then picks a video model. Mirrors caption's toggle.
async function toggleVideoEnabled() {
  saving.value = true
  try {
    if (videoEnabled.value) {
      await Promise.all([
        $fetch('/api/config', { method: 'POST', body: { key: 'video.provider', value: '' } }),
        $fetch('/api/config', { method: 'POST', body: { key: 'video.model', value: '' } }),
      ])
    }
    else {
      await $fetch('/api/config', { method: 'POST', body: { key: 'video.provider', value: 'openrouter' } })
    }
    refresh()
  }
  finally { saving.value = false }
}
async function setVideoProvider(value: string) {
  saving.value = true
  try {
    // Reset the model on a provider switch — a model from the previous provider isn't valid here.
    await Promise.all([
      $fetch('/api/config', { method: 'POST', body: { key: 'video.provider', value } }),
      $fetch('/api/config', { method: 'POST', body: { key: 'video.model', value: '' } }),
    ])
    refresh()
  }
  finally { saving.value = false }
}
async function setVideoModel(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'video.model', value } })
    refresh()
  }
  finally { saving.value = false }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Video Interpretation
    </h2>
    <p class="text-xs text-fg-muted">
      A chat model that supports video natively watches uploaded videos directly. When it can't, a
      dedicated video model interprets the clip and hands your chat model a text description — so even
      a text-only chat model can “watch” videos, mirroring how Transcription and Image Captioning work.
      OpenRouter watches the clip natively; a self-hosted vLLM or Ollama vision model reads sampled
      frames instead (preserving motion such as panning). With none set, videos fall back to your chat
      model's own capability (frames as images, else a captioned summary).
    </p>

    <!-- Master toggle -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3 cursor-pointer">
        <button
          type="button"
          :aria-pressed="videoEnabled"
          aria-label="Enable a dedicated video model"
          :class="videoEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="toggleVideoEnabled"
        >
          <span
            :class="videoEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-sm font-medium text-fg-strong">Dedicated video model</span>
        <span class="ml-auto text-[11px] text-fg-muted">{{ videoEnabled ? 'on' : 'off' }}</span>
      </div>
    </div>

    <!-- Active-path / fallback status -->
    <div
      class="px-3 py-2 text-[11px] border"
      :class="videoEnabled
        ? 'bg-emerald-50/50 dark:bg-emerald-900/15 border-emerald-200 dark:border-emerald-800/50 text-emerald-800 dark:text-emerald-300'
        : 'bg-muted border-border text-fg-muted'"
    >
      <template v-if="!mainAgent">
        {{ videoEnabled ? 'A dedicated video model is set.' : 'Off.' }} Set up a “main” agent to see how
        its videos are handled.
      </template>
      <template v-else-if="defaultVideoStrategy === 'native'">
        Your main agent's model (<span class="font-mono">{{ mainAgent.modelId }}</span>) supports native
        video, so it watches videos directly.<template v-if="videoEnabled">
          The dedicated model
          (<span class="font-mono">{{ videoModel || 'none selected' }}</span>) covers agents whose model
          can't.
        </template>
      </template>
      <template v-else-if="videoEnabled">
        Your main agent's model (<span class="font-mono">{{ mainAgent.modelId }}</span>) can't do video, so
        videos are interpreted by <span class="font-mono">{{ videoModel || 'no model selected' }}</span>
        via {{ videoProvider }} → text. Falls back to
        {{ defaultVideoStrategy === 'multiImage' ? 'frames as still images' : 'a captioned summary' }}
        if that call fails.
      </template>
      <template v-else-if="defaultVideoStrategy === 'multiImage'">
        Off — your main agent's model (<span class="font-mono">{{ mainAgent.modelId }}</span>) supports
        vision, so sampled frames are sent to it as still images.
      </template>
      <template v-else>
        Off — your main agent's model (<span class="font-mono">{{ mainAgent.modelId }}</span>) is text-only,
        so frames are captioned into a text summary (needs an Image Captioning provider).
      </template>
    </div>

    <!-- Provider + model picker, when the dedicated model is enabled. -->
    <template v-if="videoEnabled">
      <fieldset class="bg-surface-elevated border border-border">
        <legend class="sr-only">
          Video interpretation backend
        </legend>
        <div class="divide-y divide-border">
          <label
            for="video-provider-openrouter"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="openrouterApiKeyConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="openrouterApiKeyConfigured ? '' : 'Add an OpenRouter API key in LLM Providers above to enable.'"
          >
            <input
              id="video-provider-openrouter"
              type="radio"
              name="video-provider"
              value="openrouter"
              :checked="videoProvider === 'openrouter'"
              :disabled="!openrouterApiKeyConfigured"
              class="accent-emerald-600"
              @change="setVideoProvider('openrouter')"
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
            for="video-provider-vllm"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="vllmReachable
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="vllmReachable ? '' : 'A self-hosted vLLM must be running and reachable at provider.vllm.baseUrl to use it for video.'"
          >
            <input
              id="video-provider-vllm"
              type="radio"
              name="video-provider"
              value="vllm"
              :checked="videoProvider === 'vllm'"
              :disabled="!vllmReachable"
              class="accent-emerald-600"
              @change="setVideoProvider('vllm')"
            >
            <span
              class="flex-1 text-sm"
              :class="vllmReachable
                ? 'text-fg-primary'
                : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >vLLM (self-hosted)</span>
            <button
              v-if="vllmConfigured && !vllmReachable"
              type="button"
              class="text-xs text-fg-muted underline hover:text-fg-strong disabled:opacity-50"
              :disabled="vllmProbing"
              title="Re-check whether vLLM is reachable now"
              @click.prevent="probeVllm"
            >{{ vllmProbing ? 'checking…' : 'recheck' }}</button>
            <span
              v-if="!vllmConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no base URL — configure in LLM Providers</span>
            <span
              v-else-if="!vllmReachable"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
              :title="vllmReachableReason || ''"
            >not reachable</span>
            <span
              v-else
              class="text-[10px] px-1 border"
              :class="videoProvider === 'vllm' ? 'text-green-700 dark:text-green-400 border-green-400/30' : 'text-fg-muted border-input'"
            >reachable</span>
          </label>
          <label
            for="video-provider-ollama-local"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="ollamaLocalConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="ollamaLocalConfigured ? '' : 'Configure provider.ollama-local.baseUrl in LLM Providers above to enable.'"
          >
            <input
              id="video-provider-ollama-local"
              type="radio"
              name="video-provider"
              value="ollama-local"
              :checked="videoProvider === 'ollama-local'"
              :disabled="!ollamaLocalConfigured"
              class="accent-emerald-600"
              @change="setVideoProvider('ollama-local')"
            >
            <span
              class="flex-1 text-sm"
              :class="ollamaLocalConfigured
                ? 'text-fg-primary'
                : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >Ollama (local)</span>
            <span
              v-if="!ollamaLocalConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no base URL — configure in LLM Providers</span>
          </label>
          <label
            for="video-provider-ollama-cloud"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="ollamaCloudConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="ollamaCloudConfigured ? '' : 'Configure provider.ollama-cloud.baseUrl in LLM Providers above to enable.'"
          >
            <input
              id="video-provider-ollama-cloud"
              type="radio"
              name="video-provider"
              value="ollama-cloud"
              :checked="videoProvider === 'ollama-cloud'"
              :disabled="!ollamaCloudConfigured"
              class="accent-emerald-600"
              @change="setVideoProvider('ollama-cloud')"
            >
            <span
              class="flex-1 text-sm"
              :class="ollamaCloudConfigured
                ? 'text-fg-primary'
                : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >Ollama (cloud)</span>
            <span
              v-if="!ollamaCloudConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no base URL — configure in LLM Providers</span>
          </label>
        </div>
      </fieldset>

      <!-- Video-model picker — video-capable models discovered live from the chosen provider. -->
      <div class="bg-surface-elevated border border-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Video model</span>
          <select
            :value="videoModelSelectValue"
            aria-label="Video model"
            :disabled="videoModelsLoading"
            class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden disabled:opacity-50"
            @change="setVideoModel(($event.target as HTMLSelectElement).value)"
          >
            <option value="">
              {{ videoModelsLoading ? 'loading…' : '(select a model)' }}
            </option>
            <option
              v-for="m in videoModelOptions"
              :key="m.id"
              :value="m.id"
            >
              {{ m.label }}
            </option>
            <!-- Keep a saved-but-not-discovered model selectable so it isn't silently dropped. -->
            <option
              v-if="videoModelOrphaned"
              :value="videoModel"
            >
              {{ videoModel }} (saved)
            </option>
          </select>
          <button
            type="button"
            class="text-xs text-fg-muted underline hover:text-fg-strong disabled:opacity-50 shrink-0"
            :disabled="videoModelsLoading"
            title="Re-fetch video models from the provider"
            @click="discoverVideoModels"
          >
            {{ videoModelsLoading ? 'loading…' : 'refresh' }}
          </button>
        </div>
        <p
          v-if="videoModelsError"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-amber-700 dark:text-amber-400"
        >
          Couldn't load video models from {{ videoProvider }}: {{ videoModelsError }}
        </p>
        <p
          v-else-if="!videoModelsLoading && videoModelOptions.length === 0"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
        >
          No video-capable models found on {{ videoProvider }}. {{ videoProvider === 'vllm'
            ? 'Make sure vLLM is serving a model that accepts video input.'
            : 'On OpenRouter these are models with a video input modality (e.g. Gemini, Qwen3.5).' }}
          (Note: OpenRouter's Qwen-VL routes are image-only — they don't accept video.)
        </p>
      </div>
    </template>

    <!-- Sampling controls — apply to the dedicated model and the chat-model fallback alike.
           Effective frame count = clamp(round(duration / secondsPerFrame), 2, framesCeiling). -->
    <label
      for="video-seconds-per-frame"
      class="bg-surface-elevated border border-border px-4 py-3 flex items-center gap-3"
    >
      <span class="flex-1">
        <span class="block text-sm font-medium text-fg-strong">Seconds per frame</span>
        <span class="block text-[11px] text-fg-muted mt-0.5">
          Sampling density — grab one frame per this many seconds of video (1–60). Lower means more
          frames, finer detail, higher cost. This is what densifies <span class="text-fg-muted">short</span>
          clips, which otherwise floor at 2 frames; the ceiling below caps long ones.
        </span>
      </span>
      <input
        id="video-seconds-per-frame"
        type="number"
        :min="1"
        :max="60"
        :value="videoSecondsPerFrame"
        :disabled="saving"
        class="w-20 px-2 py-1 text-sm text-right bg-surface border border-border text-fg-primary"
        aria-label="Seconds per frame"
        @change="saveVideoSecondsPerFrame(($event.target as HTMLInputElement).value)"
      >
    </label>

    <!-- Hard ceiling on total frames, regardless of duration. -->
    <label
      for="video-sample-frames"
      class="bg-surface-elevated border border-border px-4 py-3 flex items-center gap-3"
    >
      <span class="flex-1">
        <span class="block text-sm font-medium text-fg-strong">Max frames per video</span>
        <span class="block text-[11px] text-fg-muted mt-0.5">
          Hard ceiling on how many frames are ever extracted from one video (2–32), regardless of
          length. A long clip is sampled at the density above up to this cap; raising it lets long
          videos be sampled more finely.
        </span>
      </span>
      <input
        id="video-sample-frames"
        type="number"
        :min="2"
        :max="32"
        :value="videoSampleFrames"
        :disabled="saving"
        class="w-20 px-2 py-1 text-sm text-right bg-surface border border-border text-fg-primary"
        aria-label="Max frames per video"
        @change="saveVideoSampleFrames(($event.target as HTMLInputElement).value)"
      >
    </label>
  </div>
</template>
