<script setup lang="ts">
// Video Generation settings panel (JCLAW-680 second pass).
// Moved verbatim from pages/settings.vue. Agents produce videos via the
// generate_video tool; the operator picks Replicate cloud (reusing the
// Replicate API key from Image Generation) or a self-hosted WAN/LTX engine
// chosen by an adaptive GPU-capability probe. Config reads/writes go through
// the shared store; the inline config-row editor + API-key checks injected.
const { configData, saving, refresh, saveField, apiKeyConfigured } = useSettingsConfig()

const replicateApiKeyConfigured = computed(() => apiKeyConfigured('replicate'))

// Cloud video generation via Replicate (JCLAW-231/235). Mirrors Image Generation: a non-empty
// videogen.provider IS the "enabled" state. Replicate is the only cloud backend today (the second
// cloud provider was dropped in SV-1); self-hosted WAN 2 / LTX 2 land in JCLAW-232/233.
const videogenProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'videogen.provider')?.value ?? '',
)
const videogenEnabled = computed(() => videogenProvider.value.trim().length > 0)
const videogenModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'videogen.cloud.model')?.value ?? '',
)
const videogenMaxJobMinutes = computed(() =>
  configData.value?.entries?.find(e => e.key === 'videogen.maxJobMinutes')?.value ?? '30',
)
async function setVideogenProvider(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'videogen.provider', value } })
    refresh()
  }
  finally { saving.value = false }
}
async function toggleVideogenEnabled() {
  // Only Replicate is wired today; enabling selects it (its API key gates the toggle).
  await setVideogenProvider(videogenEnabled.value ? '' : 'replicate')
}
// Model dropdown: Replicate curates a `text-to-video` collection; GET /api/videogen/models returns its
// owner/model slugs (server-discovered, not hardcoded). The dropdown is the only way to pick a model —
// no free-text entry — so the saved value is always surfaced (see videogenModelOptions) even if
// discovery is empty or the saved model has since left the collection.
interface VideoModel {
  slug: string
  name: string
  description: string | null
}
// Lazy: same as imagegen models — Replicate's text-to-video collection is a slow outbound call, so don't
// block the page render on it (this is the bulk of the old Settings-load lag).
const { data: videogenModels, refresh: refreshVideogenModels, status: videogenModelsStatus }
  = useLazyFetch<VideoModel[]>('/api/videogen/models')
const videogenModelOptions = computed<VideoModel[]>(() => {
  const discovered = videogenModels.value ?? []
  const saved = videogenModel.value
  // Surface a saved model that discovery didn't return (collection drift, missing key, transient
  // error) so the select shows it as the active choice instead of silently snapping to the default.
  if (saved && !discovered.some(m => m.slug === saved)) {
    return [{ slug: saved, name: saved, description: null }, ...discovered]
  }
  return discovered
})

// ──── Self-hosted adaptive capability picker (SV-2 / JCLAW-232/233) ────
// The sidecar's one-shot `--probe` reports the host GPU + free VRAM and tiers every local engine, so the
// dropdown only offers what THIS machine can run (and greys out WAN off NVIDIA). State machine mirrors the
// local-Flux download: POST starts a background probe, GET polls the snapshot until it settles.
interface VideoEngine {
  id: string
  label: string
  provider: string
  minVramGb: number
  tier: 'ready' | 'fits' | 'no'
  runnable: boolean
  reason: string | null
}
interface VideoCapability {
  kind: string
  gpu: string
  freeVramGb: number
  totalVramGb: number
  models: VideoEngine[]
}
interface VideoCapabilitySnapshot {
  uvAvailable: boolean
  uvReason: string | null
  state: 'NEEDS_PROBE' | 'PROBING' | 'READY' | 'UNAVAILABLE' | 'ERROR'
  capability: VideoCapability | null
  error: string | null
}
// Lazy (non-blocking): don't suspend the whole panel behind the settings
// "Loading…" fallback while /api/videogen/capability resolves. The computeds
// below default gracefully until it arrives (mirrors the transcription panel).
const { data: videoCapability, refresh: refreshVideoCapability }
  = useLazyFetch<VideoCapabilitySnapshot>('/api/videogen/capability')
const videoCapState = computed(() => videoCapability.value?.state ?? 'NEEDS_PROBE')
const videoEngines = computed<VideoEngine[]>(() => videoCapability.value?.capability?.models ?? [])
const videogenLocalModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'videogen.local.model')?.value ?? '',
)
const videoCapDetectLabel = computed(() => {
  switch (videoCapState.value) {
    case 'PROBING': return 'detecting…'
    case 'READY': return 're-detect'
    default: return 'detect GPU'
  }
})
function isLocalEngineActive(e: VideoEngine): boolean {
  if (videogenProvider.value !== e.provider) return false
  // Match the exact variant the operator picked (videogen.local.model) — both families now have a
  // VRAM-tiered spectrum (ltx int4/int8/bf16, wan 5b/14b). Fall back to the smallest in the family for a
  // freshly-selected provider that hasn't recorded a model yet.
  if (videogenLocalModel.value) return videogenLocalModel.value === e.id
  return e.id === (e.provider === 'wan-local' ? 'wan-5b' : 'ltx')
}
async function selectLocalEngine(e: VideoEngine) {
  if (!e.runnable) return
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'videogen.local.model', value: e.id } })
    await $fetch('/api/config', { method: 'POST', body: { key: 'videogen.provider', value: e.provider } })
    refresh()
  }
  finally { saving.value = false }
}
async function probeVideoCapability() {
  await $fetch('/api/videogen/capability/probe', { method: 'POST' })
  await refreshVideoCapability()
  startVideoCapPolling()
}
let videoCapPollTimer: ReturnType<typeof setInterval> | null = null
function startVideoCapPolling() {
  if (videoCapPollTimer != null) return
  videoCapPollTimer = setInterval(async () => {
    await refreshVideoCapability()
    if (videoCapState.value !== 'PROBING') stopVideoCapPolling()
  }, 2000)
}
function stopVideoCapPolling() {
  if (videoCapPollTimer != null) {
    clearInterval(videoCapPollTimer)
    videoCapPollTimer = null
  }
}
// Top-level Self-Hosted vs Replicate choice. "Local" is any provider that isn't the lone cloud backend
// (replicate) — i.e. an ltx-local / wan-local engine. The header radio shows checked whenever local is
// active, even before a GPU probe, so the current backend is always clear.
const videogenIsLocal = computed(() => {
  const p = videogenProvider.value
  return p !== '' && p !== 'replicate'
})
// Hardware verdict from the probe: once READY, "unsupported" means no engine can run on this machine
// (no GPU, or too little free VRAM for even the smallest tier). Drives disabling the Self-Hosted radio.
const videoLocalUnsupported = computed(() =>
  videoCapState.value === 'READY' && !videoEngines.value.some(e => e.runnable),
)
// Picking "Self-Hosted" needs a concrete engine, which needs a probe first. If engines are already known,
// select the best runnable one immediately; otherwise probe and auto-select once it settles.
const pendingLocalAutoSelect = ref(false)
async function selectSelfHosted() {
  if (videogenIsLocal.value) return // already on a local engine
  const best = videoEngines.value.find(e => e.runnable)
  if (videoCapState.value === 'READY' && best) {
    await selectLocalEngine(best)
    return
  }
  pendingLocalAutoSelect.value = true
  await probeVideoCapability()
}
watch(videoCapState, (s) => {
  if (s !== 'READY' || !pendingLocalAutoSelect.value) return
  pendingLocalAutoSelect.value = false
  const best = videoEngines.value.find(e => e.runnable)
  if (best) void selectLocalEngine(best) // nothing runnable -> leave provider as-is; the list shows why
})
// State loads lazily, so a probe already PROBING when the panel opens only
// becomes visible once the fetch resolves — resume the capability poll then.
watch(videoCapability, () => {
  if (videoCapState.value === 'PROBING') startVideoCapPolling()
})
onUnmounted(() => stopVideoCapPolling())
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Video Generation
    </h2>
    <p class="text-xs text-fg-muted">
      Let agents produce short videos with the <span class="font-mono">generate_video</span> tool, then
      enable the tool per agent in the agent editor (it is off by default). Generation is asynchronous —
      a “generating” card appears in chat and swaps to the finished clip when the job completes (minutes).
      Replicate reuses the API key set in <span class="text-fg-muted">Image Generation</span> above.
    </p>

    <!-- Active-backend status line. -->
    <div
      class="px-3 py-2 text-[11px] border"
      :class="videogenEnabled
        ? 'bg-emerald-50/50 dark:bg-emerald-900/15 border-emerald-200 dark:border-emerald-800/50 text-emerald-800 dark:text-emerald-300'
        : 'bg-muted border-border text-fg-muted'"
    >
      <template v-if="videogenEnabled">
        Active: video generation via {{ videogenProvider }}.
      </template>
      <template v-else>
        Video generation is off — enable it below. The generate_video tool stays hidden from agents
        until a backend is set.
      </template>
    </div>

    <!-- Master toggle: ON when videogen.provider is non-empty (mirrors Image Generation). Gated on
           the Replicate API key since that's the only backend today. -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3">
        <button
          type="button"
          :aria-pressed="videogenEnabled"
          aria-label="Enable video generation"
          :disabled="!videogenEnabled && !replicateApiKeyConfigured"
          :class="[
            videogenEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted',
            (!videogenEnabled && !replicateApiKeyConfigured) ? 'opacity-50 cursor-not-allowed' : '',
          ]"
          class="relative w-9 h-5 rounded-full transition-colors"
          :title="(!videogenEnabled && !replicateApiKeyConfigured) ? 'Set a Replicate API key in Image Generation above first.' : ''"
          @click="toggleVideogenEnabled"
        >
          <span
            :class="videogenEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-sm font-medium text-fg-strong">Enable video generation</span>
        <span class="ml-auto text-[11px] text-fg-muted">{{ videogenEnabled ? 'on' : 'off' }}</span>
      </div>
    </div>

    <template v-if="videogenEnabled">
      <fieldset class="space-y-3">
        <legend class="sr-only">
          Video generation backend
        </legend>

        <!-- Replicate — the cloud backend; reuses the Replicate API key from Image Generation. -->
        <div class="bg-surface-elevated border border-border">
          <label
            for="videogen-provider-replicate"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="replicateApiKeyConfigured ? 'cursor-pointer' : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="replicateApiKeyConfigured ? '' : 'Set a Replicate API key in Image Generation above to enable.'"
          >
            <input
              id="videogen-provider-replicate"
              type="radio"
              name="videogen-provider"
              value="replicate"
              :checked="videogenProvider === 'replicate'"
              :disabled="!replicateApiKeyConfigured"
              class="accent-emerald-600"
              @change="setVideogenProvider('replicate')"
            >
            <span
              class="flex-1 text-sm"
              :class="replicateApiKeyConfigured ? 'text-fg-primary' : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >Replicate (WAN 2, LTX, …)</span>
            <span
              v-if="!replicateApiKeyConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no API key — set in Image Generation</span>
          </label>
          <!-- Model — picked from Replicate's curated text-to-video collection (GET /api/videogen/models). -->
          <div class="border-t border-border px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">Model</span>
            <select
              :value="videogenModel"
              :disabled="saving"
              aria-label="Replicate video model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              @change="saveField('videogen.cloud.model', ($event.target as HTMLSelectElement).value)"
            >
              <option value="">
                Provider default (wan-video/wan-2.2-t2v-fast)
              </option>
              <option
                v-for="m in videogenModelOptions"
                :key="m.slug"
                :value="m.slug"
                :title="m.description ?? ''"
              >
                {{ m.slug }}
              </option>
            </select>
            <button
              type="button"
              class="shrink-0 text-xs text-fg-muted hover:text-fg-strong disabled:opacity-50"
              :disabled="videogenModelsStatus === 'pending'"
              @click="refreshVideogenModels()"
            >
              {{ videogenModelsStatus === 'pending' ? 'discovering…' : 'refresh' }}
            </button>
          </div>
          <!-- Empty-state hint when discovery returned nothing (no API key set, or a transient error). -->
          <div
            v-if="videogenModelsStatus !== 'pending' && !videogenModels?.length"
            class="border-t border-border px-4 py-2 text-[11px] text-fg-muted"
          >
            No models discovered — set a Replicate API key in Image Generation above, then refresh.
          </div>
        </div>

        <!-- Self-hosted engines — adaptive picker (SV-2 / JCLAW-232/233): the host's GPU + free VRAM
               decide what's offered. WAN is greyed off NVIDIA; a fits-but-slow engine is still selectable. -->
        <div class="bg-surface-elevated border border-border">
          <div class="px-4 py-2.5 flex items-center gap-3 border-b border-border">
            <!-- Top-level Self-Hosted radio: a peer of Replicate so local-vs-cloud is one clear choice.
                   Checked whenever a local engine is active (even before a GPU probe). Picking it probes
                   and selects the best runnable engine; the per-engine radios below refine the tier. -->
            <label
              for="videogen-provider-local"
              class="flex-1 flex items-center gap-3"
              :class="videoCapability?.uvAvailable ? 'cursor-pointer' : 'cursor-not-allowed'"
            >
              <input
                id="videogen-provider-local"
                type="radio"
                name="videogen-provider"
                :checked="videogenIsLocal"
                :disabled="!videoCapability?.uvAvailable || videoCapState === 'PROBING' || videoLocalUnsupported || saving"
                class="accent-emerald-600"
                @change="selectSelfHosted()"
              >
              <span
                class="text-sm"
                :class="(videoCapability?.uvAvailable && !videoLocalUnsupported) ? 'text-fg-primary' : 'text-fg-muted'"
              >Self-Hosted (on this machine)</span>
            </label>
            <span
              v-if="!videoCapability?.uvAvailable"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 px-1"
              :title="videoCapability?.uvReason ?? ''"
            >requires uv on PATH</span>
            <button
              v-else
              type="button"
              class="shrink-0 text-xs text-fg-muted hover:text-fg-strong disabled:opacity-50"
              :disabled="videoCapState === 'PROBING'"
              @click="probeVideoCapability()"
            >
              {{ videoCapDetectLabel }}
            </button>
          </div>

          <!-- PROBING — the first run installs the Python video deps. -->
          <div
            v-if="videoCapState === 'PROBING'"
            class="px-4 py-2.5 text-[11px] text-fg-muted"
          >
            Detecting GPU capability… the first run installs the Python video deps and can take a few minutes.
          </div>

          <!-- ERROR — probe failed. -->
          <div
            v-else-if="videoCapState === 'ERROR'"
            class="px-4 py-2.5 text-[11px] text-rose-700 dark:text-rose-400"
          >
            {{ videoCapability?.error ?? 'Capability probe failed.' }}
          </div>

          <!-- READY — host summary + one radio per engine, tiered for this machine. -->
          <template v-else-if="videoCapState === 'READY' && videoCapability?.capability">
            <div class="px-4 py-1.5 text-[11px] text-fg-muted border-b border-border">
              {{ videoCapability.capability.gpu }} ·
              {{ videoCapability.capability.freeVramGb }} GB free / {{ videoCapability.capability.totalVramGb }} GB total
            </div>
            <label
              v-for="e in videoEngines"
              :key="e.id"
              :for="`videogen-engine-${e.id}`"
              class="px-4 py-2.5 flex items-center gap-3 border-b border-border last:border-b-0"
              :class="e.runnable ? 'cursor-pointer' : 'cursor-not-allowed opacity-55'"
              :title="e.reason ?? ''"
            >
              <input
                :id="`videogen-engine-${e.id}`"
                type="radio"
                name="videogen-engine"
                :checked="isLocalEngineActive(e)"
                :disabled="!e.runnable || saving"
                class="accent-emerald-600"
                @change="selectLocalEngine(e)"
              >
              <span
                class="flex-1 text-sm"
                :class="e.runnable ? 'text-fg-primary' : 'text-fg-muted'"
              >{{ e.label }}</span>
              <span
                v-if="e.tier === 'ready'"
                class="text-[10px] text-emerald-700 dark:text-emerald-400 border border-emerald-300 dark:border-emerald-600/60 px-1"
              >ready</span>
              <span
                v-else-if="e.tier === 'fits'"
                class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 px-1"
              >runs slow</span>
              <span
                v-else
                class="text-[10px] text-fg-muted border border-border px-1"
              >{{ e.reason ?? 'unavailable' }}</span>
            </label>
            <!-- No engine fits this machine — Self-Hosted is disabled above; say why. -->
            <div
              v-if="videoLocalUnsupported"
              class="px-4 py-2 text-[11px] text-amber-700 dark:text-amber-400 border-t border-border"
            >
              This machine can't run local video generation — no engine fits the detected GPU / free VRAM. Use Replicate instead.
            </div>
          </template>

          <!-- NEEDS_PROBE — idle hint. -->
          <div
            v-else-if="videoCapState === 'NEEDS_PROBE' && videoCapability?.uvAvailable"
            class="px-4 py-2.5 text-[11px] text-fg-muted"
          >
            Run local WAN 2 / LTX on your own GPU — detect to see what this machine can run.
          </div>
        </div>
      </fieldset>

      <!-- Job timeout — jobs RUNNING longer than this are failed by the runner. -->
      <label
        for="videogen-max-job-minutes"
        class="flex items-center gap-3 px-1"
      >
        <span class="text-sm text-fg-strong">Job timeout (minutes)</span>
        <input
          id="videogen-max-job-minutes"
          :value="videogenMaxJobMinutes"
          type="number"
          min="1"
          :disabled="saving"
          class="w-20 px-2 py-1 text-sm text-right bg-surface border border-border text-fg-primary"
          aria-label="Video job timeout in minutes"
          @change="saveField('videogen.maxJobMinutes', ($event.target as HTMLInputElement).value)"
        >
      </label>
    </template>
  </div>
</template>
