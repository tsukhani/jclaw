<script setup lang="ts">
/**
 * Reranking: a second pass that re-orders the shortlist recall found.
 *
 * Restricted to a local provider for the same reason embeddings are (JCLAW-939):
 * memory.MemoryReranker renders the whole candidate shortlist into its prompt, so
 * whatever serves a rerank sees memory text. ConfigService rejects a non-local value
 * for the provider key, so this picker narrows the choice rather than being the thing
 * that enforces it — the key is reachable through POST /api/config directly.
 */
import type { ProviderModelDef, ProviderModelsResponse } from '~/types/api'

const { configValue, saveField, saving, providersData, getProviderModels } = useSettingsConfig()

/** Mirrors memory.MemoryReranker's keys. */
const MemoryRerankKeys = {
  enabled: 'memory.rerank.enabled',
  provider: 'memory.rerank.provider',
  model: 'memory.rerank.model',
} as const

const enabled = computed(() => configValue(MemoryRerankKeys.enabled, 'false') === 'true')
const savedProvider = computed(() => configValue(MemoryRerankKeys.provider))
const savedModel = computed(() => configValue(MemoryRerankKeys.model))

const selectedProvider = ref(savedProvider.value)
const selectedModel = ref(savedModel.value)

const providerNames = computed(() =>
  (providersData.value ?? []).filter(p => p.local).map(p => p.name))

const discovered = ref<ProviderModelDef[] | null>(null)
const discovering = ref(false)

/**
 * Chat models, not embedding models: the reranker asks a model to reorder a numbered
 * shortlist and parses back an index array, so it needs an instruct-following model.
 * discover-models is the endpoint that excludes embedding / TTS / STT for that reason.
 */
async function discoverModels() {
  if (!selectedProvider.value) return
  discovering.value = true
  discovered.value = null
  try {
    const r = await $fetch<ProviderModelsResponse>(
      `/api/providers/${encodeURIComponent(selectedProvider.value)}/discover-models`,
    )
    discovered.value = (r?.models ?? []) as unknown as ProviderModelDef[]
  }
  catch {
    discovered.value = null // fall back to the stored catalog
  }
  finally {
    discovering.value = false
  }
}

const models = computed<ProviderModelDef[]>(() => {
  const base = discovered.value ?? getProviderModels(selectedProvider.value)
  // The saved model must always be representable, or the panel cannot show the
  // configuration it is editing.
  if (savedModel.value && selectedProvider.value === savedProvider.value
    && !base.some(m => m.id === savedModel.value)) {
    return [{ id: savedModel.value, name: savedModel.value }, ...base]
  }
  return base
})

const isDirty = computed(() =>
  selectedProvider.value !== savedProvider.value || selectedModel.value !== savedModel.value)

/** On until a provider AND model are saved, reranking silently no-ops. Say so. */
const incomplete = computed(() => enabled.value && (!savedProvider.value || !savedModel.value))

watch(selectedProvider, (p, prev) => {
  if (prev !== undefined) selectedModel.value = ''
  if (p) discoverModels()
})

onMounted(() => {
  if (selectedProvider.value) discoverModels()
})

const { saveError, attempt } = useSaveAttempt()

async function toggleEnabled() {
  await attempt(() => saveField(MemoryRerankKeys.enabled, enabled.value ? 'false' : 'true'))
}

async function saveSelection() {
  if (!isDirty.value) return
  // saveField refreshes after each write, so one that lands before another fails still shows.
  await attempt(async () => {
    await saveField(MemoryRerankKeys.provider, selectedProvider.value)
    await saveField(MemoryRerankKeys.model, selectedModel.value)
  })
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Reranker
    </h2>
    <p class="text-xs text-fg-muted">
      A second pass that re-orders the shortlist recall found, judging each candidate against the
      question rather than by keyword and vector score alone. It costs one extra model call per
      recall, so it is off by default.
    </p>
    <ApiErrorAlert
      :error="saveError"
    />

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Rerank recalled memories</span>
          <span
            v-if="enabled"
            class="ml-2 text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          >on</span>
          <span
            v-else
            class="ml-2 text-[10px] text-fg-muted border border-input px-1"
          >off</span>
        </div>
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
          :disabled="saving"
          data-testid="memory-rerank-toggle"
          @click="toggleEnabled"
        >
          {{ enabled ? 'Disable' : 'Enable' }}
        </button>
      </div>
    </div>

    <div
      v-if="enabled"
      class="bg-surface-elevated border border-border"
    >
      <div class="px-4 py-3 space-y-3">
        <div>
          <label
            for="memory-rerank-provider"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Provider</span>
            <select
              id="memory-rerank-provider"
              v-model="selectedProvider"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-rerank-provider"
            >
              <option value="">
                Select a provider…
              </option>
              <option
                v-for="p in providerNames"
                :key="p"
                :value="p"
              >
                {{ p }}
              </option>
            </select>
          </label>
          <p
            v-if="!providerNames.length"
            class="mt-1 text-xs text-fg-muted"
            data-testid="memory-rerank-no-local-provider"
          >
            No local provider is configured. Reranking sends the candidate memories to the model,
            so it must run on this machine — add a provider with a local base URL (for example
            Ollama or LM Studio) to enable it.
          </p>
        </div>

        <div v-if="selectedProvider">
          <label
            for="memory-rerank-model"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Model</span>
            <select
              id="memory-rerank-model"
              v-model="selectedModel"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-rerank-model"
            >
              <option value="">
                Select a model…
              </option>
              <option
                v-for="m in models"
                :key="m.id"
                :value="m.id"
              >
                {{ m.name || m.id }}
              </option>
            </select>
          </label>
          <p
            v-if="discovering"
            class="mt-1 text-[11px] text-fg-muted"
            data-testid="memory-rerank-discovering"
          >
            Loading models from {{ selectedProvider }}…
          </p>
          <p
            v-else
            class="mt-1 text-xs text-fg-muted"
          >
            A small instruct model is enough — it only has to return the shortlist in a new order.
          </p>
        </div>

        <div class="flex items-center gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
            :disabled="!isDirty || !selectedProvider || !selectedModel || saving"
            data-testid="memory-rerank-save"
            @click="saveSelection"
          >
            Save
          </button>
          <span
            v-if="incomplete"
            class="text-xs text-amber-700 dark:text-amber-400"
            data-testid="memory-rerank-incomplete"
          >Reranking stays off until a provider and model are saved.</span>
        </div>
      </div>
    </div>
  </div>
</template>
