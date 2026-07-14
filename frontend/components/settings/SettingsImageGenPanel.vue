<script setup lang="ts">
// Image Generation settings panel (JCLAW-680 second pass).
// Moved verbatim from pages/settings.vue. Agents produce images via the
// generate_image tool; the operator picks one backend — BFL / OpenAI /
// Replicate cloud, or the self-hosted Flux 2 Klein sidecar (GPU-capability
// probe + model download). Three runtime probes (models list, local sidecar
// state, host capability) each drive their own poll loop + lifecycle. Config
// reads/writes go through the shared store; API-key checks + the shared
// inline config-row editor are injected from it.
import { CheckIcon, PencilIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const { configData, saving, refresh, saveField, apiKeyConfigured, editingKey, editValue, updateEntry } = useSettingsConfig()

const openaiApiKeyConfigured = computed(() => apiKeyConfigured('openai'))
const replicateApiKeyConfigured = computed(() => apiKeyConfigured('replicate'))

// Agents produce images with the generate_image tool (default-off per agent). The operator picks one
// backend here (OpenAI gpt-image-1, Black Forest Labs / Replicate Flux, or a self-hosted Flux 2 Klein sidecar). A
// non-empty imagegen.provider IS the "enabled" state, mirroring Image Captioning. Reads configData,
// writes via /api/config.
const imagegenProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'imagegen.provider')?.value ?? '',
)
const imagegenEnabled = computed(() => imagegenProvider.value.trim().length > 0)
const bflApiKeyConfigured = computed(() => apiKeyConfigured('bfl'))

// Master toggle: off clears the provider; on defaults to the first cloud provider that has a key
// (no keyless cloud option exists, unlike captioning's local Ollama), falling back to OpenAI.
function defaultImagegenProvider(): string {
  if (openaiApiKeyConfigured.value) return 'openai'
  if (bflApiKeyConfigured.value) return 'bfl'
  return 'openai'
}
async function toggleImagegenEnabled() {
  saving.value = true
  try {
    const next = imagegenEnabled.value ? '' : defaultImagegenProvider()
    await $fetch('/api/config', { method: 'POST', body: { key: 'imagegen.provider', value: next } })
    refresh()
  }
  finally { saving.value = false }
}
async function setImagegenProvider(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'imagegen.provider', value } })
    refresh()
  }
  finally { saving.value = false }
}
// BFL is image-gen only, so its API key is set here (not in LLM Providers). Reuses the shared
// editingKey/editValue/updateEntry flow; editValue starts blank so the operator types a fresh key
// (the stored value is masked and must not be saved back verbatim).
function startEditBflKey() {
  editingKey.value = 'provider.bfl.apiKey'
  editValue.value = ''
}
// Replicate is also image-gen only → its API key is set in this section too.
function startEditReplicateKey() {
  editingKey.value = 'provider.replicate.apiKey'
  editValue.value = ''
}

// Replicate image-model dropdown — mirrors the videogen catalog. Replicate curates a `text-to-image`
// collection; GET /api/imagegen/models returns its owner/model slugs (server-discovered, not hardcoded).
// The saved value is always surfaced (see imagegenModelOptions) even if discovery is empty or the saved
// model has since left the collection, so it's shown as the active choice rather than silently dropped.
interface ImageModel {
  slug: string
  name: string
  description: string | null
  // JCLAW-700: true for Kontext / style-transfer models that accept an uploaded reference; drives
  // the capability grouping in the dropdown.
  imageToImage: boolean
}
// Replicate's chosen model is stored provider-scoped (imagegen.replicate.model),
// not a shared cloud-model key — switching providers must not carry a Replicate
// slug into OpenAI/BFL, which reject it.
const imagegenModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'imagegen.replicate.model')?.value ?? '',
)
// Lazy: Replicate's text-to-image collection is a slow outbound call (~seconds). Awaiting it blocked the
// whole Settings page render; lazy lets the page paint immediately and the dropdown fills in when ready.
const { data: imagegenModels, refresh: refreshImagegenModels, status: imagegenModelsStatus }
  = useLazyFetch<ImageModel[]>('/api/imagegen/models')
const imagegenModelOptions = computed<ImageModel[]>(() => {
  const discovered = imagegenModels.value ?? []
  const saved = imagegenModel.value
  if (saved && !discovered.some(m => m.slug === saved)) {
    return [{ slug: saved, name: saved, description: null, imageToImage: false }, ...discovered]
  }
  return discovered
})
// JCLAW-700: split by capability so the single dropdown groups models under labeled optgroups —
// image-to-image (Kontext / style transfer, accept an uploaded reference) vs text-to-image.
const textToImageOptions = computed(() => imagegenModelOptions.value.filter(m => !m.imageToImage))
const imageToImageOptions = computed(() => imagegenModelOptions.value.filter(m => m.imageToImage))

// ──────────────────── Local Flux 2 Klein sidecar (JCLAW-226) ──────────────
// Runtime state — uv availability + Flux weight download status — comes from
// /api/imagegen/local/state. The provider selection itself rides the same
// imagegen.provider path as the cloud backends above (value 'flux-local').
type ImagegenLocalState = {
  provider: string | null
  model: string
  uvAvailable: boolean
  uvReason: string | null
  modelStatus: 'ABSENT' | 'DOWNLOADING' | 'AVAILABLE' | 'ERROR'
  bytesDownloaded: number
  totalBytes: number
  error: string | null
}
// Lazy (non-blocking): don't suspend the whole panel behind the settings
// "Loading…" fallback while /api/imagegen/local/state resolves. The computeds
// below default gracefully until it arrives (mirrors the transcription panel).
const { data: imagegenLocalState, refresh: refreshImagegenLocalState }
  = useLazyFetch<ImagegenLocalState>('/api/imagegen/local/state')

const fluxUvAvailable = computed(() => imagegenLocalState.value?.uvAvailable ?? false)
const fluxModelStatus = computed(() => imagegenLocalState.value?.modelStatus ?? 'ABSENT')

// Self-Hosted hardware gate (mirrors the videogen capability probe): a manual "detect GPU" runs the image
// sidecar's `uv run serve.py --probe` to check GPU + free VRAM; the Self-Hosted radio is disabled when the
// probe says this machine can't run Flux (no GPU, or too little free VRAM).
interface ImageCapability {
  kind: string
  gpu: string
  freeVramGb: number
  totalVramGb: number
  runnable: boolean
  tier: string
  reason: string | null
}
interface ImageCapabilitySnapshot {
  uvAvailable: boolean
  uvReason: string | null
  state: 'NEEDS_PROBE' | 'PROBING' | 'READY' | 'UNAVAILABLE' | 'ERROR'
  capability: ImageCapability | null
  error: string | null
}
const { data: imageCapability, refresh: refreshImageCapability }
  = useLazyFetch<ImageCapabilitySnapshot>('/api/imagegen/capability')
const imageCapState = computed(() => imageCapability.value?.state ?? 'NEEDS_PROBE')
const imageCapDetectLabel = computed(() => {
  switch (imageCapState.value) {
    case 'PROBING': return 'detecting…'
    case 'READY': return 're-detect'
    default: return 'detect GPU'
  }
})
const imageLocalUnsupported = computed(() =>
  imageCapState.value === 'READY' && imageCapability.value?.capability?.runnable === false,
)
let imageCapPollTimer: ReturnType<typeof setInterval> | null = null
function startImageCapPolling() {
  if (imageCapPollTimer != null) return
  imageCapPollTimer = setInterval(async () => {
    await refreshImageCapability()
    if (imageCapState.value !== 'PROBING') stopImageCapPolling()
  }, 2000)
}
function stopImageCapPolling() {
  if (imageCapPollTimer != null) {
    clearInterval(imageCapPollTimer)
    imageCapPollTimer = null
  }
}
async function probeImageCapability() {
  await $fetch('/api/imagegen/capability/probe', { method: 'POST' })
  await refreshImageCapability()
  startImageCapPolling()
}
// State loads lazily, so a probe already PROBING when the panel opens only
// becomes visible once the fetch resolves — resume the capability poll then.
watch(imageCapability, () => {
  if (imageCapState.value === 'PROBING') startImageCapPolling()
})
onUnmounted(() => stopImageCapPolling())
// Optional HF token (imagegen.local.hfToken) — passed to the sidecar as HF_TOKEN.
// Set inline here; the stored value is masked so editValue starts blank.
const hfTokenConfigured = computed(() => {
  const v = configData.value?.entries?.find(e => e.key === 'imagegen.local.hfToken')?.value
  return !!v && v.trim().length > 0
})
function startEditHfToken() {
  editingKey.value = 'imagegen.local.hfToken'
  editValue.value = ''
}
const fluxModelDownloadPct = computed(() => {
  const s = imagegenLocalState.value
  if (!s || s.totalBytes === 0) return 0
  return Math.min(100, Math.round((s.bytesDownloaded / s.totalBytes) * 100))
})

// Kick off the sidecar pull (launches the daemon on demand, then downloads the
// weights). Returns immediately; the poll loop drives the progress bar.
async function downloadFluxModel() {
  saving.value = true
  try {
    await $fetch('/api/imagegen/local/pull', { method: 'POST', body: {} })
    startImagegenLocalPolling()
  }
  finally { saving.value = false }
}

// Poll /api/imagegen/local/state every 1.5s while a pull is in flight, stop once
// it settles. Mirrors the transcription poll loop; cleans up on unmount.
let imagegenLocalPollTimer: ReturnType<typeof setInterval> | null = null
function fluxDownloadInFlight(): boolean {
  return imagegenLocalState.value?.modelStatus === 'DOWNLOADING'
}
function startImagegenLocalPolling() {
  if (imagegenLocalPollTimer != null) return
  imagegenLocalPollTimer = setInterval(async () => {
    await refreshImagegenLocalState()
    if (!fluxDownloadInFlight()) stopImagegenLocalPolling()
  }, 1500)
}
function stopImagegenLocalPolling() {
  if (imagegenLocalPollTimer != null) {
    clearInterval(imagegenLocalPollTimer)
    imagegenLocalPollTimer = null
  }
}
// Same as the capability poll: resume the download poll once the lazily-loaded
// state reveals a pull already in flight.
watch(imagegenLocalState, () => {
  if (fluxDownloadInFlight()) startImagegenLocalPolling()
})
onUnmounted(() => stopImagegenLocalPolling())
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Image Generation
    </h2>
    <p class="text-xs text-fg-muted">
      Let agents produce images with the <span class="font-mono">generate_image</span> tool. Pick a
      backend below, then enable the tool per agent in the agent editor (it is off by default). Cloud
      providers reuse the API keys configured in
      <span class="text-fg-muted">LLM Providers</span> above. Generated images appear inline in chat
      and are saved like uploaded ones.
    </p>

    <!-- Active-backend status line. -->
    <div
      class="px-3 py-2 text-[11px] border"
      :class="imagegenEnabled
        ? 'bg-emerald-50/50 dark:bg-emerald-900/15 border-emerald-200 dark:border-emerald-800/50 text-emerald-800 dark:text-emerald-300'
        : 'bg-muted border-border text-fg-muted'"
    >
      <template v-if="imagegenEnabled">
        Active: image generation via {{ imagegenProvider }}.
      </template>
      <template v-else>
        Image generation is off — enable it and pick a backend below. The generate_image tool stays
        hidden from agents until a backend is set.
      </template>
    </div>

    <!-- Master toggle: ON when imagegen.provider is non-empty (mirrors Image Captioning). -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3 cursor-pointer">
        <button
          type="button"
          :aria-pressed="imagegenEnabled"
          aria-label="Enable image generation"
          :class="imagegenEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="toggleImagegenEnabled"
        >
          <span
            :class="imagegenEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-sm font-medium text-fg-strong">Enable image generation</span>
        <span class="ml-auto text-[11px] text-fg-muted">
          {{ imagegenEnabled ? 'on' : 'off' }}
        </span>
      </div>
    </div>

    <template v-if="imagegenEnabled">
      <!-- Each backend is its own group: the radio sits at the top of a card with
             that backend's settings (API key / download / token) directly beneath, so
             it's clear which settings belong to which provider. -->
      <fieldset class="space-y-3">
        <legend class="sr-only">
          Image generation backend
        </legend>

        <!-- Black Forest Labs (Flux) — image-gen only; key set in this group. -->
        <div class="bg-surface-elevated border border-border">
          <label
            for="imagegen-provider-bfl"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="bflApiKeyConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="bflApiKeyConfigured ? '' : 'Set a Black Forest Labs API key below to enable.'"
          >
            <input
              id="imagegen-provider-bfl"
              type="radio"
              name="imagegen-provider"
              value="bfl"
              :checked="imagegenProvider === 'bfl'"
              :disabled="!bflApiKeyConfigured"
              class="accent-emerald-600"
              @change="setImagegenProvider('bfl')"
            >
            <span
              class="flex-1 text-sm"
              :class="bflApiKeyConfigured
                ? 'text-fg-primary'
                : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >Black Forest Labs (Flux)</span>
            <span
              v-if="!bflApiKeyConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no API key — set it below</span>
          </label>
          <div class="border-t border-border px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">Black Forest Labs API key</span>
            <template v-if="editingKey === 'provider.bfl.apiKey'">
              <input
                v-model="editValue"
                type="password"
                aria-label="Black Forest Labs API key"
                placeholder="Your BFL API key from bfl.ai"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry('provider.bfl.apiKey')"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ bflApiKeyConfigured ? '••••••••' : '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                :title="bflApiKeyConfigured ? 'Change key' : 'Set key'"
                aria-label="Edit Black Forest Labs API key"
                @click="startEditBflKey()"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
        </div>

        <!-- OpenAI (gpt-image-1) — reuses the OpenAI key from LLM Providers above; no inline key. -->
        <div class="bg-surface-elevated border border-border">
          <label
            for="imagegen-provider-openai"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="openaiApiKeyConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="openaiApiKeyConfigured ? '' : 'Add an OpenAI API key in LLM Providers above to enable.'"
          >
            <input
              id="imagegen-provider-openai"
              type="radio"
              name="imagegen-provider"
              value="openai"
              :checked="imagegenProvider === 'openai'"
              :disabled="!openaiApiKeyConfigured"
              class="accent-emerald-600"
              @change="setImagegenProvider('openai')"
            >
            <span
              class="flex-1 text-sm"
              :class="openaiApiKeyConfigured
                ? 'text-fg-primary'
                : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >OpenAI (gpt-image-1)</span>
            <span
              v-if="!openaiApiKeyConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no API key — configure in LLM Providers</span>
          </label>
        </div>

        <!-- Replicate — image-gen only (hosted Flux etc.); key set in this group. -->
        <div class="bg-surface-elevated border border-border">
          <label
            for="imagegen-provider-replicate"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="replicateApiKeyConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="replicateApiKeyConfigured ? '' : 'Set a Replicate API key below to enable.'"
          >
            <input
              id="imagegen-provider-replicate"
              type="radio"
              name="imagegen-provider"
              value="replicate"
              :checked="imagegenProvider === 'replicate'"
              :disabled="!replicateApiKeyConfigured"
              class="accent-emerald-600"
              @change="setImagegenProvider('replicate')"
            >
            <span
              class="flex-1 text-sm"
              :class="replicateApiKeyConfigured
                ? 'text-fg-primary'
                : 'text-amber-800 dark:text-amber-300 opacity-80'"
            >Replicate</span>
            <span
              v-if="!replicateApiKeyConfigured"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >no API key — set it below</span>
          </label>
          <div class="border-t border-border px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">Replicate API key</span>
            <template v-if="editingKey === 'provider.replicate.apiKey'">
              <input
                v-model="editValue"
                type="password"
                aria-label="Replicate API key"
                placeholder="Your Replicate API token from replicate.com"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry('provider.replicate.apiKey')"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ replicateApiKeyConfigured ? '••••••••' : '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                :title="replicateApiKeyConfigured ? 'Change key' : 'Set key'"
                aria-label="Edit Replicate API key"
                @click="startEditReplicateKey()"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- Model — picked from Replicate's curated text-to-image collection (GET /api/imagegen/models).
                 Shown when Replicate is the active backend, mirroring the Self-Hosted download UI below. -->
          <div
            v-if="imagegenProvider === 'replicate'"
            class="border-t border-border px-4 py-2.5 flex items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">Model</span>
            <select
              :value="imagegenModel"
              :disabled="saving"
              aria-label="Replicate image model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              @change="saveField('imagegen.replicate.model', ($event.target as HTMLSelectElement).value)"
            >
              <option value="">
                Provider default (black-forest-labs/flux-schnell)
              </option>
              <!-- JCLAW-700: image-to-image (Kontext) models grouped + labeled so the operator can
                   pick a style-transfer backend for use_reference_image; a text-to-image model
                   ignores an uploaded reference. -->
              <optgroup
                v-if="imageToImageOptions.length"
                label="Image-to-image (style transfer)"
              >
                <option
                  v-for="m in imageToImageOptions"
                  :key="m.slug"
                  :value="m.slug"
                  :title="m.description ?? ''"
                >
                  {{ m.slug }}
                </option>
              </optgroup>
              <optgroup
                v-if="textToImageOptions.length"
                label="Text-to-image"
              >
                <option
                  v-for="m in textToImageOptions"
                  :key="m.slug"
                  :value="m.slug"
                  :title="m.description ?? ''"
                >
                  {{ m.slug }}
                </option>
              </optgroup>
            </select>
            <button
              type="button"
              class="shrink-0 text-[11px] text-fg-muted hover:text-fg-strong disabled:opacity-50"
              :disabled="imagegenModelsStatus === 'pending'"
              @click="refreshImagegenModels()"
            >
              {{ imagegenModelsStatus === 'pending' ? 'discovering…' : 'refresh' }}
            </button>
          </div>
          <!-- Empty-state hint when discovery returned nothing (no API key set, or a transient error). -->
          <div
            v-if="imagegenProvider === 'replicate' && imagegenModelsStatus !== 'pending' && !imagegenModels?.length"
            class="border-t border-border px-4 py-2 text-[11px] text-fg-muted"
          >
            No models discovered — set a Replicate API key above, then refresh.
          </div>
        </div>

        <!-- Self-Hosted (Flux 2 Klein) — local Python sidecar; gated by uv presence,
               not an API key. Download + HF token live in this group (JCLAW-226). -->
        <div class="bg-surface-elevated border border-border">
          <div
            class="px-4 py-2.5 flex items-center gap-3 border-b border-border"
            :class="fluxUvAvailable ? '' : 'bg-amber-50/40 dark:bg-amber-900/10'"
          >
            <label
              for="imagegen-provider-flux-local"
              class="flex-1 flex items-center gap-3"
              :class="(fluxUvAvailable && !imageLocalUnsupported) ? 'cursor-pointer' : 'cursor-not-allowed'"
              :title="fluxUvAvailable ? '' : 'Install uv (astral.sh/uv) on the server to enable local generation.'"
            >
              <input
                id="imagegen-provider-flux-local"
                type="radio"
                name="imagegen-provider"
                value="flux-local"
                :checked="imagegenProvider === 'flux-local'"
                :disabled="!fluxUvAvailable || imageCapState === 'PROBING' || imageLocalUnsupported"
                class="accent-emerald-600"
                @change="setImagegenProvider('flux-local')"
              >
              <span
                class="text-sm"
                :class="(fluxUvAvailable && !imageLocalUnsupported)
                  ? 'text-fg-primary'
                  : 'text-amber-800 dark:text-amber-300 opacity-80'"
              >Self-Hosted (Flux 2 Klein)</span>
            </label>
            <span
              v-if="!fluxUvAvailable"
              class="text-[10px] text-amber-700 dark:text-amber-300 border border-amber-300 dark:border-amber-600/60 bg-amber-100/60 dark:bg-amber-900/30 px-1"
            >uv not found — install uv</span>
            <button
              v-else
              type="button"
              class="shrink-0 text-[11px] text-fg-muted hover:text-fg-strong disabled:opacity-50"
              :disabled="imageCapState === 'PROBING'"
              @click="probeImageCapability()"
            >
              {{ imageCapDetectLabel }}
            </button>
          </div>

          <!-- PROBING — the first run installs the Python image deps. -->
          <div
            v-if="imageCapState === 'PROBING'"
            class="px-4 py-2.5 text-[11px] text-fg-muted"
          >
            Detecting GPU capability… the first run installs the Python image deps and can take a few minutes.
          </div>
          <!-- ERROR — probe failed. -->
          <div
            v-else-if="imageCapState === 'ERROR'"
            class="px-4 py-2.5 text-[11px] text-rose-700 dark:text-rose-400"
          >
            {{ imageCapability?.error ?? 'Capability probe failed.' }}
          </div>
          <!-- READY — host summary + (when it can't run) why Self-Hosted is disabled. -->
          <template v-else-if="imageCapState === 'READY' && imageCapability?.capability">
            <div class="px-4 py-1.5 text-[11px] text-fg-muted">
              {{ imageCapability.capability.gpu }} ·
              {{ imageCapability.capability.freeVramGb }} GB free / {{ imageCapability.capability.totalVramGb }} GB total
            </div>
            <div
              v-if="imageLocalUnsupported"
              class="px-4 pb-2 text-[11px] text-amber-700 dark:text-amber-400"
            >
              This machine can't run local image generation — {{ imageCapability.capability.reason }}. Use a cloud provider instead.
            </div>
          </template>

          <!-- Model download status + button (shown when Self-Hosted is the active backend). -->
          <div
            v-if="imagegenProvider === 'flux-local'"
            class="border-t border-border"
          >
            <div
              v-if="!fluxUvAvailable"
              class="px-4 py-2.5 text-[11px] text-amber-800 dark:text-amber-300 bg-amber-50/50 dark:bg-amber-900/15 border-b border-amber-200 dark:border-amber-800/50"
            >
              {{ imagegenLocalState?.uvReason || 'uv is required to run the local Flux sidecar.' }}
              Install uv from astral.sh/uv and restart jclaw.
            </div>
            <div class="px-4 py-2.5 flex items-center gap-3">
              <div class="flex-1 min-w-0">
                <div class="text-sm font-mono text-fg-strong truncate">
                  {{ imagegenLocalState?.model }}
                </div>
                <div class="text-[11px] text-fg-muted">
                  <template v-if="fluxModelStatus === 'AVAILABLE'">
                    Ready — weights downloaded.
                  </template>
                  <template v-else-if="fluxModelStatus === 'DOWNLOADING'">
                    Downloading weights…
                  </template>
                  <template v-else-if="fluxModelStatus === 'ERROR'">
                    Download failed.
                  </template>
                  <template v-else>
                    Not downloaded (~13 GB). The first image needs the weights.
                  </template>
                </div>
              </div>
              <template v-if="fluxModelStatus === 'DOWNLOADING'">
                <div class="flex items-center gap-2">
                  <div class="w-32 h-2 bg-muted border border-input overflow-hidden">
                    <div
                      class="h-full bg-emerald-600 transition-[width] duration-300"
                      :style="{ width: fluxModelDownloadPct + '%' }"
                    />
                  </div>
                  <span class="text-xs font-mono text-fg-muted tabular-nums w-10 text-right">
                    {{ fluxModelDownloadPct }}%
                  </span>
                </div>
              </template>
              <template v-else-if="fluxModelStatus === 'AVAILABLE'">
                <span class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1">Ready</span>
              </template>
              <template v-else>
                <button
                  type="button"
                  class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors"
                  :disabled="saving || !fluxUvAvailable"
                  @click="downloadFluxModel()"
                >
                  {{ fluxModelStatus === 'ERROR' ? 'Retry' : 'Download' }}
                </button>
              </template>
            </div>
            <div
              v-if="fluxModelStatus === 'ERROR' && imagegenLocalState?.error"
              class="px-4 pb-2.5 -mt-1 text-[11px] text-red-700 dark:text-red-400 break-words"
            >
              {{ imagegenLocalState.error }}
            </div>
          </div>

          <!-- Optional Hugging Face token (shown when Self-Hosted is the active backend):
                 lifts rate limits, speeds downloads, unlocks gated models. Passed as HF_TOKEN. -->
          <div
            v-if="imagegenProvider === 'flux-local'"
            class="border-t border-border"
          >
            <div class="px-4 py-2.5 text-xs text-fg-muted leading-relaxed border-b border-border">
              Optional — a Read token lifts Hugging Face rate limits, speeds downloads, and unlocks gated
              models. Not required for klein 4B (it downloads anonymously). Create one (shown once), then
              paste it below.
              <a
                href="https://huggingface.co/settings/tokens"
                target="_blank"
                rel="noopener"
                class="text-fg-primary hover:text-fg-strong underline ml-1"
              >Get a token → huggingface.co/settings/tokens</a>
            </div>
            <div class="px-4 py-2.5 flex items-center gap-3">
              <span class="text-xs font-mono text-fg-muted w-48 shrink-0">Hugging Face token (optional)</span>
              <template v-if="editingKey === 'imagegen.local.hfToken'">
                <input
                  v-model="editValue"
                  type="password"
                  aria-label="Hugging Face token"
                  placeholder="hf_… — higher rate limits and gated models"
                  class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
                >
                <button
                  class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                  title="Save"
                  @click="updateEntry('imagegen.local.hfToken')"
                >
                  <CheckIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                </button>
                <button
                  class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                  title="Cancel"
                  @click="editingKey = null"
                >
                  <XMarkIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                </button>
              </template>
              <template v-else>
                <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ hfTokenConfigured ? '••••••••' : '(not set)' }}</span>
                <button
                  class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                  :title="hfTokenConfigured ? 'Change token' : 'Set token'"
                  aria-label="Edit Hugging Face token"
                  @click="startEditHfToken()"
                >
                  <PencilIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                </button>
              </template>
            </div>
          </div>
        </div>
      </fieldset>
    </template>
  </div>
</template>
