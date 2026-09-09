<script setup lang="ts">
/**
 * Memory embedding settings (JCLAW-932).
 *
 * Vector memory needs a provider and a model, and the model's dimension has to
 * match what the model actually returns. There is nothing to look these up from:
 * ProviderModelDef carries supportsVision/Audio/Video but no embedding flag and no
 * dimension, and no provider's /v1/models marks which models embed. So the panel
 * shortlists by name to keep the dropdown usable and settles the question by
 * probing (JCLAW-931) — the probe is what accepts a model and where the read-only
 * dimension comes from.
 */
import type { EmbeddingProbeResponse, MemoryReembedStatus, ProviderModelDef, ProviderModelsResponse } from '~/types/api'
import { MemoryVectorKeys, looksLikeEmbeddingModel } from '~/utils/embeddingModels'

const { configValue, saveField, saving, providersData, getProviderModels } = useSettingsConfig()

const enabled = computed(() => configValue(MemoryVectorKeys.enabled, 'false') === 'true')
const savedProvider = computed(() => configValue(MemoryVectorKeys.provider))
const savedModel = computed(() => configValue(MemoryVectorKeys.model))
const savedDimensions = computed(() => configValue(MemoryVectorKeys.dimensions))

const selectedProvider = ref(savedProvider.value)
const selectedModel = ref(savedModel.value)
const probe = ref<EmbeddingProbeResponse | null>(null)
const probing = ref(false)
const showAllModels = ref(false)

/**
 * Local providers only (JCLAW-939). Embedding a memory sends its full text to the
 * provider, so a cloud one would ship the whole corpus off the machine. Local means the
 * operator's Remote/Local flag (`provider.<name>.local`), not the base URL — a cloud API
 * behind a loopback proxy has a local address. The backend rejects a remote provider on
 * both probe and save, so this list narrows the choice rather than enforcing it.
 */
const providerNames = computed(() =>
  (providersData.value ?? []).filter(p => p.local).map(p => p.name))

/**
 * Models discovered live from the provider, which is where embedding models
 * actually live. {@code provider.<name>.models} is the operator's curated chat
 * catalog — embedding models are not added to it, so on a real instance it lists
 * only chat models and the embedding model in active use is not even selectable.
 *
 * Uses /embedding-models, not /discover-models: the latter drops embedding, TTS and STT
 * models so a chat agent cannot be bound to one (JCLAW-183), which removes exactly what
 * this picker needs — against a live ollama serving ten models it returned nine, and the
 * one omitted was the embedding model. The stored list is the fallback.
 */
const discovered = ref<ProviderModelDef[] | null>(null)
const discovering = ref(false)

async function discoverModels() {
  if (!selectedProvider.value) return
  discovering.value = true
  discovered.value = null
  try {
    const r = await $fetch<ProviderModelsResponse>(
      `/api/providers/${encodeURIComponent(selectedProvider.value)}/embedding-models`,
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

const catalog = computed<ProviderModelDef[]>(() => {
  const base = discovered.value ?? getProviderModels(selectedProvider.value)
  // The saved model must always be representable, or the panel cannot show the
  // configuration it is editing.
  if (savedModel.value && selectedProvider.value === savedProvider.value
    && !base.some(m => m.id === savedModel.value)) {
    return [{ id: savedModel.value, name: savedModel.value }, ...base]
  }
  return base
})

/**
 * The shortlist narrows; it never decides. A provider we have not seen may name a
 * valid embedding model in a way the heuristic misses, so "show all" stays
 * available and the probe is the only thing that accepts a model.
 */
const models = computed(() => {
  if (showAllModels.value) return catalog.value
  const shortlisted = catalog.value.filter(m => looksLikeEmbeddingModel(m.id, m.name))
  return shortlisted.length > 0 ? shortlisted : catalog.value
})

const hiddenModelCount = computed(() =>
  showAllModels.value ? 0 : catalog.value.length - models.value.length,
)

/** A change is only committable once the probe has confirmed the exact model. */
const canSave = computed(() =>
  !!selectedProvider.value && !!selectedModel.value && probe.value?.ok === true && !saving.value,
)

const isDirty = computed(() =>
  selectedProvider.value !== savedProvider.value || selectedModel.value !== savedModel.value,
)

watch(selectedProvider, (p, prev) => {
  // Keep the saved model selected on first render; only a real change clears it.
  if (prev !== undefined) selectedModel.value = ''
  probe.value = null
  showAllModels.value = false
  if (p) discoverModels()
})

// The panel opens on the saved provider, so discover its catalog without waiting
// for the operator to re-pick it.
onMounted(() => {
  if (selectedProvider.value) discoverModels()
})
watch(selectedModel, () => {
  probe.value = null
})

async function runProbe() {
  if (!selectedProvider.value || !selectedModel.value) return
  probing.value = true
  probe.value = null
  try {
    probe.value = await $fetch<EmbeddingProbeResponse>(
      `/api/providers/${encodeURIComponent(selectedProvider.value)}/embedding-probe`,
      { method: 'POST', body: { model: selectedModel.value } },
    )
  }
  catch (e) {
    probe.value = {
      provider: selectedProvider.value,
      model: selectedModel.value,
      ok: false,
      dimensions: 0,
      error: e instanceof Error ? e.message : 'Probe failed',
    }
  }
  finally {
    probing.value = false
  }
}

// --- re-embed (JCLAW-933) ---
const reembed = ref<MemoryReembedStatus | null>(null)
const reembedError = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

async function refreshReembed() {
  try {
    reembed.value = await $fetch<MemoryReembedStatus>('/api/memories/reembed')
  }
  catch { /* transient — the next poll retries */ }
}

async function startReembed() {
  reembedError.value = ''
  try {
    reembed.value = await $fetch<MemoryReembedStatus>('/api/memories/reembed', { method: 'POST' })
  }
  catch (e) {
    // 409 carries the refusal reason: disabled, already running, or a dimension the
    // index cannot store. Surfacing it matters — the button otherwise looks inert.
    // Two shapes reach here: the backend's own {type, code, message} body, and the
    // Nitro-wrapped {data: {...}} when the proxy layer generates the error.
    const d = (e as { data?: { message?: string, data?: { message?: string } } })?.data
    reembedError.value = d?.message ?? d?.data?.message ?? 'Could not start re-embedding.'
  }
}

// Poll only while a run is in flight; the status is otherwise static.
watch(() => reembed.value?.running, (isRunning) => {
  if (isRunning && !pollTimer) pollTimer = setInterval(refreshReembed, 1500)
  if (!isRunning && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})
onMounted(refreshReembed)
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})

async function toggleEnabled() {
  await saveField(MemoryVectorKeys.enabled, enabled.value ? 'false' : 'true')
}

/**
 * Dimensions are written from the probe, never from typing — a hand-entered value
 * that disagrees with the model is undetectable at runtime on the Lucene backend,
 * where the real dimension comes from the returned array.
 */
async function saveSelection() {
  if (!canSave.value || !probe.value) return
  await saveField(MemoryVectorKeys.provider, selectedProvider.value)
  await saveField(MemoryVectorKeys.model, selectedModel.value)
  await saveField(MemoryVectorKeys.dimensions, String(probe.value.dimensions))
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Embeddings
    </h2>
    <p class="text-xs text-fg-muted">
      Vector memory lets recall find a memory by meaning rather than wording, and lets capture
      recognise a fact you have already stored even when you phrase it differently. With it off,
      memory still works — recall and duplicate detection fall back to keyword matching.
    </p>

    <!-- Enable toggle -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Vector memory</span>
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
          data-testid="memory-vector-toggle"
          @click="toggleEnabled"
        >
          {{ enabled ? 'Disable' : 'Enable' }}
        </button>
      </div>
    </div>

    <!-- Provider + model selection -->
    <div
      v-if="enabled"
      class="bg-surface-elevated border border-border"
    >
      <div class="px-4 py-3 space-y-3">
        <div>
          <label
            for="memory-embedding-provider"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Provider</span>
            <select
              id="memory-embedding-provider"
              v-model="selectedProvider"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-embedding-provider"
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
            data-testid="memory-embedding-no-local-provider"
          >
            No local provider is configured. Memory embeddings must run on this machine so
            memory text never leaves it — add a provider with a local base URL (for example
            Ollama or LM Studio) to enable vector memory.
          </p>
        </div>

        <div v-if="selectedProvider">
          <label
            for="memory-embedding-model"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Model</span>
            <select
              id="memory-embedding-model"
              v-model="selectedModel"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-embedding-model"
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
            data-testid="memory-embedding-discovering"
          >
            Loading models from {{ selectedProvider }}…
          </p>
          <button
            v-else-if="hiddenModelCount > 0"
            type="button"
            class="mt-1 text-[11px] text-fg-muted underline hover:text-fg-strong"
            data-testid="memory-embedding-show-all"
            @click="showAllModels = true"
          >
            Show all {{ hiddenModelCount }} other model(s)
          </button>
        </div>

        <!-- Probe -->
        <div
          v-if="selectedProvider && selectedModel"
          class="flex items-center gap-2"
        >
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
            :disabled="probing"
            data-testid="memory-embedding-probe"
            @click="runProbe"
          >
            {{ probing ? 'Checking…' : 'Check model' }}
          </button>
          <span
            v-if="probe?.ok"
            class="text-xs text-green-700 dark:text-green-400"
            data-testid="memory-embedding-probe-ok"
          >Serves embeddings at {{ probe.dimensions }} dimensions</span>
          <span
            v-else-if="probe && !probe.ok"
            class="text-xs text-red-700 dark:text-red-400"
            data-testid="memory-embedding-probe-error"
          >{{ probe.error }}</span>
        </div>

        <!-- Dimensions: probe-derived, never typed -->
        <div>
          <label
            for="memory-embedding-dimensions"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Dimensions</span>
            <input
              id="memory-embedding-dimensions"
              :value="probe?.ok ? probe.dimensions : savedDimensions"
              type="text"
              readonly
              class="w-full px-2 py-1.5 text-sm bg-muted/30 border border-input text-fg-muted"
              data-testid="memory-embedding-dimensions"
            >
          </label>
          <p class="mt-1 text-[11px] text-fg-muted">
            Read from the model itself when you check it — a typed value that disagrees with the
            model cannot be detected at runtime.
          </p>
        </div>

        <!-- Re-embed warning -->
        <div
          v-if="isDirty && savedModel"
          class="border border-amber-400/40 bg-amber-50/50 dark:bg-amber-900/10 px-3 py-2"
          data-testid="memory-embedding-reembed-warning"
        >
          <p class="text-xs text-amber-800 dark:text-amber-300">
            Changing the embedding model makes every existing memory's vector unusable — they were
            produced by a different model and cannot be compared with the new one. Semantic recall
            and duplicate detection stay degraded until the memories are re-embedded.
          </p>
        </div>

        <div class="flex items-center gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
            :disabled="!canSave || !isDirty"
            data-testid="memory-embedding-save"
            @click="saveSelection"
          >
            Save
          </button>
          <span
            v-if="isDirty && !probe?.ok"
            class="text-[11px] text-fg-muted"
          >Check the model before saving.</span>
        </div>
      </div>
    </div>

    <!-- Re-embed: the action that resolves a model switch -->
    <div
      v-if="enabled"
      class="bg-surface-elevated border border-border"
    >
      <div class="px-4 py-3 space-y-2">
        <div class="flex items-center justify-between gap-3">
          <div class="min-w-0">
            <span class="text-sm font-medium text-fg-strong">Stored memories</span>
            <span
              v-if="reembed?.running"
              class="ml-2 text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/40 px-1"
            >re-embedding</span>
            <span
              v-else-if="reembed && !reembed.upToDate"
              class="ml-2 text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/40 px-1"
            >needs re-embedding</span>
            <span
              v-else-if="reembed"
              class="ml-2 text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
            >up to date</span>
          </div>
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50 shrink-0"
            :disabled="reembed?.running"
            data-testid="memory-reembed-start"
            @click="startReembed"
          >
            {{ reembed?.running ? 'Re-embedding…' : 'Re-embed now' }}
          </button>
        </div>
        <p
          v-if="reembed?.running"
          class="text-xs text-fg-muted"
          data-testid="memory-reembed-progress"
        >
          {{ reembed.processed }} / {{ reembed.total }} — new memories are still being saved;
          duplicate detection and semantic recall are reduced until this finishes.
        </p>
        <p
          v-else-if="reembed && !reembed.upToDate"
          class="text-xs text-fg-muted"
        >
          Existing memories were embedded with a different model, so semantic recall and
          duplicate detection will not work correctly until they are rebuilt.
        </p>
        <p
          v-if="reembedError"
          class="text-xs text-red-700 dark:text-red-400"
          data-testid="memory-reembed-error"
        >
          {{ reembedError }}
        </p>
        <p
          v-else-if="reembed?.error"
          class="text-xs text-red-700 dark:text-red-400"
        >
          Last run failed: {{ reembed.error }}
        </p>
      </div>
    </div>
  </div>
</template>
