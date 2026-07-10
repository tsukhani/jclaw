<script setup lang="ts">
// Image Captioning settings panel (JCLAW-680 second pass).
// Moved verbatim from pages/settings.vue. Non-vision chat models get a
// generated image description; the operator picks a cloud provider/model or
// a local Ollama VLM. Reads from the shared config store, writes via
// /api/config; provider API-key checks + vision-model catalog injected from
// the shared settings-config context.
import type { ProviderModelDef } from '~/types/api'

const { configData, saving, refresh, getProviderModels, apiKeyConfigured } = useSettingsConfig()

const openrouterApiKeyConfigured = computed(() => apiKeyConfigured('openrouter'))
const openaiApiKeyConfigured = computed(() => apiKeyConfigured('openai'))

// Non-vision chat models get a generated image description. The operator picks
// one backend: a CLOUD provider/model, or a LOCAL VLM they run in Ollama
// (provider.ollama-local.* → localhost:11434/v1). Selection reads from
// configData and writes via /api/config.
const captionProvider = computed(() =>
  configData.value?.entries?.find(e => e.key === 'caption.provider')?.value ?? '',
)
const captionModel = computed(() =>
  configData.value?.entries?.find(e => e.key === 'caption.model')?.value ?? '',
)
// Master toggle: presence of a non-empty caption.provider IS the "enabled" state (mirrors
// transcription). Single-select — the operator picks one backend.
const captionEnabled = computed(() => captionProvider.value.trim().length > 0)
const captionIsCloud = computed(() =>
  captionProvider.value === 'openai' || captionProvider.value === 'openrouter',
)
// Vision-capable models the operator configured for the chosen provider (cloud or ollama-local) —
// the picker filters provider.{name}.models to the vision-tagged ones (the same "supports vision"
// gate the LLM Providers section sets), so the operator selects rather than free-types.
const captionVisionModels = computed<ProviderModelDef[]>(() => {
  if (!captionIsCloud.value && captionProvider.value !== 'ollama-local') return []
  return getProviderModels(captionProvider.value).filter(m => m.supportsVision === true)
})
// Dropdown options: ONLY vision-tagged models — never surface a model that might not support vision.
const captionVisionModelOptions = computed(() =>
  captionVisionModels.value.map(m => ({ id: m.id, label: m.name || m.id })),
)
// A saved caption.model that isn't among the vision-tagged options (set before it was tagged, or via
// raw config) is "orphaned": hide it from the picker (show the escape-hatch option instead) and flag
// it, so the operator re-picks a vision model rather than captioning with a possibly-non-vision one.
const captionModelOrphaned = computed(() =>
  captionModel.value !== '' && !captionVisionModelOptions.value.some(o => o.id === captionModel.value),
)
const captionModelSelectValue = computed(() => (captionModelOrphaned.value ? '' : captionModel.value))
// Which backend is active, for the status line.
const captionActiveBackend = computed(() => {
  if (captionIsCloud.value) return 'cloud'
  if (captionProvider.value === 'ollama-local') return 'local'
  return 'none'
})

// Master toggle: off clears the provider; on defaults to the local Ollama VLM (no cloud key needed,
// mirroring transcription defaulting to whisper-local). Requires a vision model pulled in Ollama.
async function toggleCaptionEnabled() {
  saving.value = true
  try {
    const next = captionEnabled.value ? '' : 'ollama-local'
    await $fetch('/api/config', { method: 'POST', body: { key: 'caption.provider', value: next } })
    refresh()
  }
  finally { saving.value = false }
}
async function setCaptionProvider(value: string) {
  saving.value = true
  try {
    // Reset the model on a provider switch — a model from the previous provider isn't valid for the
    // new one (and would otherwise linger as "(not marked vision)"). Independent keys, fire in parallel.
    await Promise.all([
      $fetch('/api/config', { method: 'POST', body: { key: 'caption.provider', value } }),
      $fetch('/api/config', { method: 'POST', body: { key: 'caption.model', value: '' } }),
    ])
    refresh()
  }
  finally { saving.value = false }
}
async function setCaptionModel(value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: 'caption.model', value } })
    refresh()
  }
  finally { saving.value = false }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Image Captioning
    </h2>
    <p class="text-xs text-fg-muted">
      Let chat models without vision still "see" an uploaded image — it's turned into a short
      text description before it reaches the LLM (vision-capable models still receive the image
      natively). Pick one backend: a cloud provider/model (reusing the API keys configured in
      <span class="text-fg-muted">LLM Providers</span> above), or a local VLM you run in Ollama.
      Text-only models then receive the description; with this off they get a
      "description unavailable" note for images.
    </p>

    <!-- Active-backend status line. -->
    <div
      class="px-3 py-2 text-[11px] border"
      :class="captionEnabled
        ? 'bg-emerald-50/50 dark:bg-emerald-900/15 border-emerald-200 dark:border-emerald-800/50 text-emerald-800 dark:text-emerald-300'
        : 'bg-muted border-border text-fg-muted'"
    >
      <template v-if="captionActiveBackend === 'cloud'">
        Active: cloud captioning via {{ captionProvider }} ({{ captionModel || 'default model' }}).
      </template>
      <template v-else-if="captionActiveBackend === 'local'">
        Active: local VLM via Ollama ({{ captionModel || 'no model selected' }}).
      </template>
      <template v-else>
        Image captioning is off — enable it and pick a backend below. Until then, non-vision
        models receive a "description unavailable" note for images.
      </template>
    </div>

    <!-- Master toggle: ON when caption.provider is non-empty (mirrors Transcription). -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3 cursor-pointer">
        <button
          type="button"
          :aria-pressed="captionEnabled"
          aria-label="Enable image captioning"
          :class="captionEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="toggleCaptionEnabled"
        >
          <span
            :class="captionEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-sm font-medium text-fg-strong">Enable image captioning</span>
        <span class="ml-auto text-[11px] text-fg-muted">
          {{ captionEnabled ? 'on' : 'off' }}
        </span>
      </div>
    </div>

    <template v-if="captionEnabled">
      <fieldset class="bg-surface-elevated border border-border">
        <legend class="sr-only">
          Image captioning backend
        </legend>
        <div class="divide-y divide-border">
          <label
            for="caption-provider-openrouter"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="openrouterApiKeyConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="openrouterApiKeyConfigured ? '' : 'Add an OpenRouter API key in LLM Providers above to enable.'"
          >
            <input
              id="caption-provider-openrouter"
              type="radio"
              name="caption-provider"
              value="openrouter"
              :checked="captionProvider === 'openrouter'"
              :disabled="!openrouterApiKeyConfigured"
              class="accent-emerald-600"
              @change="setCaptionProvider('openrouter')"
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
            for="caption-provider-openai"
            class="px-4 py-2.5 flex items-center gap-3"
            :class="openaiApiKeyConfigured
              ? 'cursor-pointer'
              : 'cursor-not-allowed bg-amber-50/40 dark:bg-amber-900/10'"
            :title="openaiApiKeyConfigured ? '' : 'Add an OpenAI API key in LLM Providers above to enable.'"
          >
            <input
              id="caption-provider-openai"
              type="radio"
              name="caption-provider"
              value="openai"
              :checked="captionProvider === 'openai'"
              :disabled="!openaiApiKeyConfigured"
              class="accent-emerald-600"
              @change="setCaptionProvider('openai')"
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
            for="caption-provider-ollama-local"
            class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
          >
            <input
              id="caption-provider-ollama-local"
              type="radio"
              name="caption-provider"
              value="ollama-local"
              :checked="captionProvider === 'ollama-local'"
              class="accent-emerald-600"
              @change="setCaptionProvider('ollama-local')"
            >
            <span class="flex-1 text-sm text-fg-primary">Local VLM (Ollama)</span>
            <span
              class="text-[10px] px-1 border"
              :class="captionProvider === 'ollama-local'
                ? 'text-green-400 border-green-400/30'
                : 'text-fg-muted border-input'"
            >local</span>
          </label>
        </div>
      </fieldset>

      <!-- Vision-model picker — vision-tagged models from the chosen provider's LLM-Providers config
             (cloud provider or local Ollama). -->
      <div
        v-if="captionIsCloud || captionProvider === 'ollama-local'"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-32 shrink-0">Vision model</span>
          <select
            :value="captionModelSelectValue"
            :aria-label="captionProvider === 'ollama-local' ? 'Ollama vision model' : 'Caption cloud model'"
            class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
            @change="setCaptionModel(($event.target as HTMLSelectElement).value)"
          >
            <option value="">
              {{ captionProvider === 'ollama-local' ? '(select a model)' : '(provider default)' }}
            </option>
            <option
              v-for="m in captionVisionModelOptions"
              :key="m.id"
              :value="m.id"
            >
              {{ m.label }}
            </option>
          </select>
        </div>
        <p
          v-if="captionVisionModels.length === 0"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-fg-muted"
        >
          <template v-if="captionProvider === 'ollama-local'">
            No vision-capable Ollama models are configured. Add your local Ollama models under
            <span class="text-fg-muted">LLM Providers</span> above and mark the vision ones “supports
            vision”, then select one here. Runs against your local Ollama at
            <span class="font-mono">localhost:11434</span>.
          </template>
          <template v-else>
            No vision-capable models are configured for this provider. Add one under
            <span class="text-fg-muted">LLM Providers</span> above and mark it “supports vision”, or
            leave this on “provider default”.
          </template>
        </p>
        <p
          v-if="captionModelOrphaned"
          class="px-4 pb-2.5 -mt-1 text-[11px] text-amber-700 dark:text-amber-400"
        >
          Saved model “{{ captionModel }}” is hidden because it is not marked vision-capable. Pick a
          vision model above, or mark it “supports vision” under
          <span class="text-fg-muted">LLM Providers</span>.
        </p>
      </div>
    </template>
  </div>
</template>
