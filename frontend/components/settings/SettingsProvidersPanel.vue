<script setup lang="ts">
// LLM Providers settings panel (JCLAW-680 Stage 3).
// Moved verbatim from pages/settings.vue: the provider list (Remote/Local
// groups), per-provider enable/billing rows, the LiteLLM nightly price-refresh
// toggle, and the model-management + model-discovery panels. Reads the shared
// config store + /api/providers billing projection through the injected context;
// derives agent routing counts from its own (Nuxt-deduped) /api/agents fetch.
import {
  ArrowPathIcon,
  CheckIcon,
  ChevronUpIcon,
  Cog6ToothIcon,
  InformationCircleIcon,
  LockClosedIcon,
  MagnifyingGlassIcon,
  PencilIcon,
  PlusIcon,
  TrashIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type { Agent, ConfigEntry, DiscoveredModel, DiscoverModelsResponse, ProviderInfo, ProviderModelDef } from '~/types/api'

const { configData, saving, refresh, getProviderModels, editingKey, editValue, startEdit, updateEntry, providersData } = useSettingsConfig()

// JCLAW-113: agent routing counts come from the panel's own deduped /api/agents
// fetch (Nuxt keys by URL, so this shares the page's request rather than doubling it).
const { data: agentsList } = await useFetch<Agent[]>('/api/agents')

const providerInfoMap = computed(() => {
  const map = new Map<string, ProviderInfo>()
  for (const p of providersData.value ?? []) map.set(p.name, p)
  return map
})
function supportedModalitiesFor(name: string): ('PER_TOKEN' | 'SUBSCRIPTION')[] {
  return providerInfoMap.value.get(name)?.supportedModalities ?? []
}
function paymentModalityFor(name: string): 'PER_TOKEN' | 'SUBSCRIPTION' {
  return providerInfoMap.value.get(name)?.paymentModality ?? 'PER_TOKEN'
}
function subscriptionMonthlyFor(name: string): number {
  return providerInfoMap.value.get(name)?.subscriptionMonthlyUsd ?? 0
}

function isSensitive(key: string) {
  const lower = key.toLowerCase()
  return ['key', 'secret', 'password', 'token'].some(s => lower.includes(s))
}

// JCLAW-28 follow-up: opt-in nightly LiteLLM pricing refresh. Off by
// default — flipping on causes the LiteLlmPriceRefreshJob to fetch the
// community pricing manifest each night and backfill missing prices on
// operator-configured models. The Refresh now button below runs the
// same code path synchronously for immediate feedback.
const priceRefreshEnabled = computed(() =>
  configData.value?.entries?.find(e => e.key === 'pricing.refresh.enabled')?.value === 'true',
)
async function togglePriceRefresh() {
  saving.value = true
  try {
    const next = priceRefreshEnabled.value ? 'false' : 'true'
    await $fetch('/api/config', { method: 'POST', body: { key: 'pricing.refresh.enabled', value: next } })
    refresh()
  }
  finally { saving.value = false }
}

const priceRefreshStatus = ref<string | null>(null)
async function manuallyRefreshPrices() {
  if (!priceRefreshEnabled.value) {
    priceRefreshStatus.value = 'Enable the toggle above first.'
    return
  }
  saving.value = true
  priceRefreshStatus.value = 'Refreshing…'
  try {
    const result = await $fetch<{
      skipped: boolean
      providersScanned: number
      modelsUpdated: number
      warnings: string[]
    }>('/api/providers/refresh-prices', { method: 'POST' })
    if (result.skipped) {
      priceRefreshStatus.value = 'Skipped — toggle is off.'
    }
    else if (result.warnings.length > 0) {
      priceRefreshStatus.value = `Updated ${result.modelsUpdated} model(s) across ${result.providersScanned} provider(s) with ${result.warnings.length} warning(s): ${result.warnings.join('; ')}`
    }
    else {
      priceRefreshStatus.value = `Updated ${result.modelsUpdated} model(s) across ${result.providersScanned} provider(s).`
    }
    refresh()
  }
  catch (e: unknown) {
    const msg = e instanceof Error ? e.message : 'unknown error'
    priceRefreshStatus.value = `Refresh failed: ${msg}`
  }
  finally { saving.value = false }
}

// Ollama-specific settings (rendered inside the provider section when provider name contains "ollama")
const ollamaKeepAlive = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'ollama.keepAlive')?.value ?? '30m'
})

// JCLAW-110: per-provider enabled flag. A provider is considered enabled
// unless `provider.NAME.enabled=false` is explicitly set (case-insensitive).
// Missing key ⇒ enabled, matching the backend TelegramModelSelector filter
// so the /model keyboard on Telegram and the web chat dropdown agree.
function isProviderEnabled(name: string): boolean {
  const entries = configData.value?.entries ?? []
  const entry = entries.find(e => e.key === `provider.${name}.enabled`)
  if (!entry) return true
  return (entry.value ?? '').toLowerCase() !== 'false'
}

async function toggleProviderEnabled(name: string) {
  const newVal = isProviderEnabled(name) ? 'false' : 'true'
  await $fetch('/api/config', {
    method: 'POST',
    body: { key: `provider.${name}.enabled`, value: newVal },
  })
  refresh()
}

// JCLAW-113: count how many agents have this provider as their default.
// Surfaced on disabled cards so operators see at a glance that the toggle
// hides from selector but doesn't detach bound agents — they continue to
// route LLM calls to the disabled provider until re-assigned. Conversation
// overrides aren't counted here (settings.vue doesn't fetch conversations);
// the inline hint covers that broader case in prose.
function agentsRoutingToProvider(name: string): number {
  return (agentsList.value ?? []).filter(a => a.modelProvider === name).length
}

// --- Model management ---
const expandedModelsProvider = ref<string | null>(null)
const editingModelIdx = ref<number | null>(null)
// contextWindow/maxTokens default to 0 ("unknown — please set"). Seeding a
// plausible-but-wrong number here used to mask provider-discovery gaps —
// e.g. Ollama Cloud's /v1/models returns no context field, so the frontend
// previously silently wrote 131072 (kimi-k2.5 is actually 256K), which
// then broke /usage and compaction-budget math. Show 0 honestly instead
// and let the user enter the real value from the provider's docs.
const modelForm = ref({ id: '', name: '', contextWindow: 0, maxTokens: 0, supportsThinking: false, alwaysThinks: false, supportsVision: false, supportsAudio: false, supportsVideo: false, promptPrice: -1, completionPrice: -1, cachedReadPrice: -1, cacheWritePrice: -1 })
const addingModel = ref(false)

// Rankings cache for configured models (providerName -> { modelId -> rank })
const configuredModelRanks = ref<Map<string, Map<string, number>>>(new Map())

function toggleModelsPanel(providerName: string) {
  if (expandedModelsProvider.value === providerName) {
    expandedModelsProvider.value = null
  }
  else {
    expandedModelsProvider.value = providerName
    fetchRanksForProvider(providerName)
  }
  editingModelIdx.value = null
  addingModel.value = false
}

/**
 * Indexes discovered models by id and extracts the leaderboard-rank map
 * for {@link configuredModelRanks}. Done in one pass so we don't walk the
 * list twice.
 */
function indexDiscoveredModels(discovered: DiscoveredModel[]): {
  rankMap: Map<string, number>
  discoveredMap: Map<string, DiscoveredModel>
} {
  const rankMap = new Map<string, number>()
  const discoveredMap = new Map<string, DiscoveredModel>()
  for (const m of discovered) {
    if (m.leaderboardRank) rankMap.set(m.id, m.leaderboardRank)
    discoveredMap.set(m.id, m)
  }
  return { rankMap, discoveredMap }
}

/**
 * Backfill any missing price field on {@code model} from {@code discovered}.
 * A model field is "missing" when null/undefined; a discovered value counts
 * as present when ≥ 0 (-1 / undefined are the unset sentinels). Returns true
 * iff at least one field was filled in.
 */
function backfillModelPrices(model: ProviderModelDef, discovered: DiscoveredModel): boolean {
  const fields: Array<'promptPrice' | 'completionPrice' | 'cachedReadPrice' | 'cacheWritePrice'> = [
    'promptPrice',
    'completionPrice',
    'cachedReadPrice',
    'cacheWritePrice',
  ]
  let updated = false
  for (const field of fields) {
    if (model[field] == null && (discovered[field] ?? -1) >= 0) {
      model[field] = discovered[field]
      updated = true
    }
  }
  return updated
}

function backfillConfiguredModelPrices(
  configured: ProviderModelDef[],
  discoveredMap: Map<string, DiscoveredModel>,
): boolean {
  let updated = false
  for (const model of configured) {
    const discovered = discoveredMap.get(model.id)
    if (discovered && backfillModelPrices(model, discovered)) updated = true
  }
  return updated
}

async function fetchRanksForProvider(providerName: string) {
  if (configuredModelRanks.value.has(providerName)) return
  try {
    const res = await $fetch<DiscoverModelsResponse>(`/api/providers/${providerName}/discover-models`, { method: 'POST' })
    const { rankMap, discoveredMap } = indexDiscoveredModels(res.models || [])
    configuredModelRanks.value = new Map(configuredModelRanks.value).set(providerName, rankMap)

    // Backfill pricing on configured models that are missing it
    const configured = getProviderModels(providerName)
    if (backfillConfiguredModelPrices(configured, discoveredMap)) {
      await saveModels(providerName, configured)
    }
  }
  catch {
    // Best-effort — no ranks if discovery fails
  }
}

function getModelRank(providerName: string, modelId: string): number | null {
  return configuredModelRanks.value.get(providerName)?.get(modelId) ?? null
}

function startEditModel(providerName: string, idx: number) {
  const models = getProviderModels(providerName)
  const m = models[idx]
  if (!m) return
  modelForm.value = {
    id: m.id || '',
    name: m.name || '',
    // Preserve 0 ("unknown") honestly — don't fabricate a fallback. See
    // modelForm initializer above for context.
    contextWindow: m.contextWindow ?? 0,
    maxTokens: m.maxTokens ?? 0,
    supportsThinking: m.supportsThinking || false,
    alwaysThinks: m.alwaysThinks || false,
    supportsVision: m.supportsVision || false,
    supportsAudio: m.supportsAudio || false,
    supportsVideo: m.supportsVideo || false,
    promptPrice: m.promptPrice ?? -1,
    completionPrice: m.completionPrice ?? -1,
    cachedReadPrice: m.cachedReadPrice ?? -1,
    cacheWritePrice: m.cacheWritePrice ?? -1,
  }
  editingModelIdx.value = idx
  addingModel.value = false
}

function startAddModel() {
  modelForm.value = { id: '', name: '', contextWindow: 0, maxTokens: 0, supportsThinking: false, alwaysThinks: false, supportsVision: false, supportsAudio: false, supportsVideo: false, promptPrice: -1, completionPrice: -1, cachedReadPrice: -1, cacheWritePrice: -1 }
  addingModel.value = true
  editingModelIdx.value = null
}

async function saveModels(providerName: string, models: ProviderModelDef[]) {
  await $fetch('/api/config', {
    method: 'POST',
    body: { key: `provider.${providerName}.models`, value: JSON.stringify(models) },
  })
  refresh()
}

/**
 * Strip sentinel -1 price values from the form before persisting. -1 means "unset"
 * for any of the four price fields; letting it reach the saved JSON would confuse
 * the cost-computation fallbacks on the frontend.
 */
function modelFormToSaved(): ProviderModelDef {
  const f = modelForm.value
  const out: ProviderModelDef = {
    id: f.id,
    name: f.name,
    contextWindow: f.contextWindow,
    maxTokens: f.maxTokens,
    supportsThinking: f.supportsThinking,
    // Enforce the invariant: alwaysThinks implies supportsThinking. If the
    // operator unchecked thinking, drop the alwaysThinks flag too — saving
    // {supportsThinking:false, alwaysThinks:true} would be incoherent.
    ...(f.supportsThinking && f.alwaysThinks ? { alwaysThinks: true } : {}),
    supportsVision: f.supportsVision,
    supportsAudio: f.supportsAudio,
    supportsVideo: f.supportsVideo,
  }
  if (f.promptPrice >= 0) out.promptPrice = f.promptPrice
  if (f.completionPrice >= 0) out.completionPrice = f.completionPrice
  if (f.cachedReadPrice >= 0) out.cachedReadPrice = f.cachedReadPrice
  if (f.cacheWritePrice >= 0) out.cacheWritePrice = f.cacheWritePrice
  return out
}

async function saveEditedModel(providerName: string) {
  const models = getProviderModels(providerName)
  if (editingModelIdx.value !== null) {
    models[editingModelIdx.value] = modelFormToSaved()
  }
  await saveModels(providerName, models)
  editingModelIdx.value = null
}

async function saveNewModel(providerName: string) {
  if (!modelForm.value.id.trim()) return
  const models = getProviderModels(providerName)
  models.push(modelFormToSaved())
  await saveModels(providerName, models)
  addingModel.value = false
}

async function deleteModel(providerName: string, idx: number) {
  const models = getProviderModels(providerName)
  models.splice(idx, 1)
  await saveModels(providerName, models)
  editingModelIdx.value = null
}

// --- Model discovery ---
const discoveryProvider = ref<string | null>(null)
const discoveryLoading = ref(false)
const discoveryError = ref('')
const discoveredModels = ref<DiscoveredModel[]>([])
const discoverySearch = ref('')
const discoverySelected = ref<Set<string>>(new Set())
const discoveryFilterThinking = ref('all') // 'all' | 'yes' | 'no'
const discoveryFilterVision = ref('all') // 'all' | 'yes' | 'no'
const discoveryFilterAudio = ref('all') // 'all' | 'yes' | 'no'
const discoveryFilterVideo = ref('all') // 'all' | 'yes' | 'no'
const discoveryFilterCost = ref('all') // 'all' | 'free' | 'paid'
const discoveryFilterPopular = ref('all') // 'all' | 'ranked' | 'top10' | 'top25'

const filteredDiscoveredModels = computed(() => {
  let list = discoveredModels.value

  // Text search
  const q = discoverySearch.value.toLowerCase().trim()
  if (q) {
    list = list.filter(m =>
      m.id.toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q),
    )
  }

  // Thinking filter
  if (discoveryFilterThinking.value === 'yes') {
    list = list.filter(m => m.supportsThinking)
  }
  else if (discoveryFilterThinking.value === 'no') {
    list = list.filter(m => !m.supportsThinking)
  }

  // Vision filter
  if (discoveryFilterVision.value === 'yes') {
    list = list.filter(m => m.supportsVision)
  }
  else if (discoveryFilterVision.value === 'no') {
    list = list.filter(m => !m.supportsVision)
  }

  // Audio filter
  if (discoveryFilterAudio.value === 'yes') {
    list = list.filter(m => m.supportsAudio)
  }
  else if (discoveryFilterAudio.value === 'no') {
    list = list.filter(m => !m.supportsAudio)
  }

  // Video filter
  if (discoveryFilterVideo.value === 'yes') {
    list = list.filter(m => m.supportsVideo)
  }
  else if (discoveryFilterVideo.value === 'no') {
    list = list.filter(m => !m.supportsVideo)
  }

  // Cost filter
  if (discoveryFilterCost.value === 'free') {
    list = list.filter(m => m.isFree)
  }
  else if (discoveryFilterCost.value === 'paid') {
    list = list.filter(m => !m.isFree)
  }

  // Popularity filter
  if (discoveryFilterPopular.value === 'ranked') {
    list = list.filter(m => m.leaderboardRank)
  }
  else if (discoveryFilterPopular.value === 'top10') {
    list = list.filter(m => m.leaderboardRank != null && m.leaderboardRank <= 10)
  }
  else if (discoveryFilterPopular.value === 'top25') {
    list = list.filter(m => m.leaderboardRank != null && m.leaderboardRank <= 25)
  }

  return list
})

// Capability filters are only shown when at least one discovered model has the
// capability — no point offering a "Video: Yes/No" filter for a provider with no
// video models (mirrors the Cost/Popular gating below).
const discoveryHasVision = computed(() => discoveredModels.value.some(m => m.supportsVision))
const discoveryHasAudio = computed(() => discoveredModels.value.some(m => m.supportsAudio))
const discoveryHasVideo = computed(() => discoveredModels.value.some(m => m.supportsVideo))

// Whether the provider offers free models — gates the Cost filter, since the
// free/paid split is only meaningful when some model is actually free.
const discoveryHasFreeModels = computed(() =>
  discoveredModels.value.some(m => m.isFree),
)

// Whether leaderboard ranking data is available
const discoveryHasRankings = computed(() =>
  discoveredModels.value.some(m => m.leaderboardRank),
)

async function startDiscovery(providerName: string) {
  discoveryProvider.value = providerName
  discoveryLoading.value = true
  discoveryError.value = ''
  discoveredModels.value = []
  discoverySearch.value = ''
  discoverySelected.value = new Set()
  discoveryFilterThinking.value = 'all'
  discoveryFilterVision.value = 'all'
  discoveryFilterAudio.value = 'all'
  discoveryFilterVideo.value = 'all'
  discoveryFilterCost.value = 'all'
  discoveryFilterPopular.value = 'all'
  expandedModelsProvider.value = null

  try {
    const res = await $fetch<DiscoverModelsResponse>(`/api/providers/${providerName}/discover-models`, { method: 'POST' })
    // Filter out models already configured
    const existing = new Set(getProviderModels(providerName).map(m => m.id))
    discoveredModels.value = (res.models || []).filter(m => !existing.has(m.id))
  }
  catch (e: unknown) {
    const err = e as { data?: { message?: string }, message?: string } | undefined
    discoveryError.value = err?.data?.message || err?.message || 'Failed to fetch models'
  }
  finally {
    discoveryLoading.value = false
  }
}

function toggleDiscoverySelect(modelId: string) {
  const s = new Set(discoverySelected.value)
  if (s.has(modelId)) s.delete(modelId)
  else s.add(modelId)
  discoverySelected.value = s
}

function selectAllDiscovered() {
  if (discoverySelected.value.size === filteredDiscoveredModels.value.length) {
    discoverySelected.value = new Set()
  }
  else {
    discoverySelected.value = new Set(filteredDiscoveredModels.value.map(m => m.id))
  }
}

async function addDiscoveredModels() {
  if (!discoveryProvider.value || discoverySelected.value.size === 0) return
  const existing = getProviderModels(discoveryProvider.value)
  const toAdd: ProviderModelDef[] = discoveredModels.value
    .filter(m => discoverySelected.value.has(m.id))
    .map(m => ({
      id: m.id,
      name: m.name,
      contextWindow: m.contextWindow,
      maxTokens: m.maxTokens,
      supportsThinking: m.thinkingDetectedFromProvider ? m.supportsThinking : false,
      // alwaysThinks is saved from either source (provider metadata for R1
      // via OpenRouter's instruct_type, or id-pattern match for o-series /
      // QwQ). Patterns are tight enough that a defensive false-by-default
      // gate isn't warranted; operators can still untick it in the form.
      ...(m.alwaysThinks ? { alwaysThinks: true } : {}),
      supportsVision: m.visionDetectedFromProvider ? m.supportsVision : false,
      supportsAudio: m.audioDetectedFromProvider ? m.supportsAudio : false,
      supportsVideo: m.videoDetectedFromProvider ? m.supportsVideo : false,
      ...((m.promptPrice ?? -1) >= 0 ? { promptPrice: m.promptPrice } : {}),
      ...((m.completionPrice ?? -1) >= 0 ? { completionPrice: m.completionPrice } : {}),
      ...((m.cachedReadPrice ?? -1) >= 0 ? { cachedReadPrice: m.cachedReadPrice } : {}),
      ...((m.cacheWritePrice ?? -1) >= 0 ? { cacheWritePrice: m.cacheWritePrice } : {}),
    }))
  const merged = [...existing, ...toAdd]
  await saveModels(discoveryProvider.value, merged)
  discoveryProvider.value = null
}

function closeDiscovery() {
  discoveryProvider.value = null
}

// JCLAW-229: image-generation-only providers are NOT chat LLM providers — their keys
// are set in the Image Generation section, so skip them when grouping LLM Providers.
const IMAGE_ONLY_PROVIDERS = new Set(['bfl', 'replicate'])

// JCLAW-182: split LLM Providers into Remote and Local subsections in the
// Settings UI. Encoded as a static map rather than a backend field — only
// four providers, names are stable, no need for a generic "is this a local
// provider" property on Config. Unknown providers default to remote.
const PROVIDER_GROUPS: Record<string, 'remote' | 'local'> = {
  'ollama-cloud': 'remote',
  'openrouter': 'remote',
  'openai': 'remote',
  'together': 'remote',
  'ollama-local': 'local',
  'lm-studio': 'local',
  'vllm': 'local',
  'llama-cpp': 'local',
}

const PROVIDER_LABELS: Record<string, string> = {
  'ollama-cloud': 'Ollama Cloud',
  'openrouter': 'OpenRouter',
  'openai': 'OpenAI',
  'together': 'TogetherAI',
  'ollama-local': 'Ollama Local',
  'lm-studio': 'LM Studio',
  'vllm': 'vLLM',
  'llama-cpp': 'llama.cpp',
}

function providerGroup(name: string): 'remote' | 'local' {
  return PROVIDER_GROUPS[name] ?? 'remote'
}

function providerLabel(name: string): string {
  return PROVIDER_LABELS[name] ?? name
}

const groupedProviders = computed(() => {
  // Build provider -> entries directly from the config store. The page keeps its
  // own providerEntries computed for the Unmanaged-config diagnostic, so this panel
  // derives the same grouping locally rather than reaching across (JCLAW-680 Stage 3).
  const providers = new Map<string, ConfigEntry[]>()
  for (const e of configData.value?.entries ?? []) {
    if (!e.key.startsWith('provider.')) continue
    const parts = e.key.split('.')
    const name = parts[1]!
    if (IMAGE_ONLY_PROVIDERS.has(name)) continue // set in the Image Generation section
    if (!providers.has(name)) providers.set(name, [])
    providers.get(name)!.push(e)
  }
  const remote: Array<[string, ConfigEntry[]]> = []
  const local: Array<[string, ConfigEntry[]]> = []
  for (const [name, entries] of providers) {
    if (providerGroup(name) === 'local') local.push([name, entries])
    else remote.push([name, entries])
  }
  return [
    { group: 'remote' as const, label: 'Remote', items: remote },
    { group: 'local' as const, label: 'Local', items: local },
  ]
})
</script>

<template>
  <!-- Provider sections -->
  <div
    class="mb-6 space-y-4"
    data-tour="llm-providers"
  >
    <h2 class="text-sm font-medium text-fg-muted">
      LLM Providers
    </h2>

    <!--
        JCLAW-28 follow-up: opt-in nightly pricing refresh, its own "Pricing"
        subsection at the top of LLM Providers (above the provider groups) so it
        reads as a global pricing-data toggle for whichever providers the
        operator configures. Many provider APIs (OpenAI, Anthropic direct,
        Google direct, etc.) don't return cost data in their /v1/models response,
        so operators see "$0.00" cost tracking until prices are filled. Toggling
        this on schedules a nightly fetch from LiteLLM's community pricing
        manifest to backfill the gaps; operator-set values are never overwritten.
        Off by default — the toggle names the GitHub network call so the operator
        opts in deliberately.
      -->
    <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2 first:pt-0">
      Pricing
    </h3>
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3 cursor-pointer">
        <button
          type="button"
          :aria-pressed="priceRefreshEnabled"
          aria-label="Auto-update model prices nightly"
          :class="priceRefreshEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="togglePriceRefresh"
        >
          <span
            :class="priceRefreshEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="text-sm font-medium text-fg-strong">Auto-update model prices nightly</span>
        <span class="ml-auto text-[11px] text-fg-muted">
          {{ priceRefreshEnabled ? 'on' : 'off' }}
        </span>
      </div>
      <div class="px-4 py-2.5 border-t border-border text-xs text-fg-muted space-y-2">
        <p>
          Most provider APIs don't return pricing in their model lists. When this is on, JClaw fetches the community-maintained
          <span class="font-mono">model_prices_and_context_window.json</span>
          from
          <span class="font-mono">github.com/BerriAI/litellm</span>
          once a night and fills in missing prices on your configured models. Prices you've set manually are never overwritten.
        </p>
        <div class="flex items-center gap-3">
          <button
            type="button"
            class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            :disabled="saving || !priceRefreshEnabled"
            @click="manuallyRefreshPrices"
          >
            Refresh now
          </button>
          <span
            v-if="priceRefreshStatus"
            class="text-[11px] text-fg-muted"
          >{{ priceRefreshStatus }}</span>
        </div>
      </div>
    </div>

    <template
      v-for="group in groupedProviders"
      :key="group.group"
    >
      <div
        v-if="group.items.length > 0"
        class="pt-2 first:pt-0"
      >
        <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide">
          {{ group.label }}
        </h3>
        <p class="text-xs text-fg-muted mt-1">
          Add an API key to enable chat — URLs and models preset.
        </p>
      </div>
      <div
        v-for="[name, entries] in group.items"
        :key="name"
        :class="isProviderEnabled(name) ? '' : 'opacity-60'"
        class="bg-surface-elevated border border-border transition-opacity"
      >
        <div class="px-4 py-2.5 border-b border-border">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">{{ providerLabel(name) }}</span>
            <span
              v-if="entries.some((e: any) => e.key.endsWith('.apiKey') && e.value && !e.value.startsWith('****') && e.value !== '****')"
              class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
            >configured</span>
            <span
              v-if="!isProviderEnabled(name)"
              class="text-[10px] text-fg-muted border border-input px-1"
            >disabled</span>
            <!-- JCLAW-113: audit pill when any agent still has this disabled
                   provider as their default. Makes the "hide from selector, not
                   a kill switch" semantics visible without requiring the user
                   to read the hint text below. -->
            <span
              v-if="!isProviderEnabled(name) && agentsRoutingToProvider(name) > 0"
              class="text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/40 px-1"
              :title="`${agentsRoutingToProvider(name)} agent(s) still route LLM calls to this provider`"
            >{{ agentsRoutingToProvider(name) }} agent{{ agentsRoutingToProvider(name) === 1 ? '' : 's' }} still routing</span>
            <!-- JCLAW-110: per-provider enable/disable toggle. Hidden from
                   the /model selector and chat dropdown when off. -->
            <button
              :aria-label="`${isProviderEnabled(name) ? 'Disable' : 'Enable'} ${name} provider`"
              :title="isProviderEnabled(name)
                ? 'Hide this provider from the model selector'
                : 'Show this provider in the model selector'"
              :class="isProviderEnabled(name) ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
              class="ml-auto relative w-9 h-5 rounded-full transition-colors"
              @click="toggleProviderEnabled(name)"
            >
              <span
                :class="isProviderEnabled(name) ? 'translate-x-4' : 'translate-x-0.5'"
                class="block w-4 h-4 bg-white rounded-full transition-transform"
              />
            </button>
          </div>
          <!-- JCLAW-113: explain "disabled" means hide-from-selector, not
                 kill-switch. Hidden when the toggle is on — the default state
                 is unambiguous and needs no explanation. -->
          <p
            v-if="!isProviderEnabled(name)"
            class="mt-1.5 text-[10px] text-fg-muted leading-snug"
          >
            Hidden from the model selector. Agents and conversations already using this provider will
            continue to route here — delete the provider row below to fully disconnect it.
          </p>
        </div>
        <div class="divide-y divide-border">
          <!-- Non-models entries (baseUrl, apiKey) -->
          <!-- paymentModality + subscriptionMonthlyUsd render below in dedicated rows
                 (JCLAW-280) so the operator gets the right input affordance: dropdown
                 constrained to supportedModalities, and a $/mo numeric only when the
                 selected modality is SUBSCRIPTION. -->
          <div
            v-for="entry in entries.filter((e: any) => !e.key.endsWith('.models') && !e.key.endsWith('.paymentModality') && !e.key.endsWith('.subscriptionMonthlyUsd'))"
            :key="entry.key"
            class="px-4 py-2 flex items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">{{ entry.key.split('.').slice(2).join('.') }}</span>
            <template v-if="editingKey === entry.key">
              <input
                v-model="editValue"
                :type="isSensitive(entry.key) ? 'password' : 'text'"
                :aria-label="`Edit value for ${entry.key}`"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry(entry.key)"
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
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ entry.value || '(empty)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(entry)"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- JCLAW-280: paymentModality row — dropdown when the provider supports
                 more than one modality (OpenAI), locked label otherwise (OpenRouter,
                 Together = per-token only; Ollama Cloud = subscription only). Local
                 providers (lm-studio, ollama-local, vllm) have an empty supported set
                 and skip the row entirely — they're free at point of use so billing
                 modality is meaningless. -->
          <div
            v-if="supportedModalitiesFor(name).length > 0"
            class="px-4 py-2 flex items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              paymentModality
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-60 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  How this provider bills you. <code class="font-mono">PER_TOKEN</code> uses model pricing to estimate cost per turn. <code class="font-mono">SUBSCRIPTION</code> ignores per-token pricing and pro-rates the monthly fee instead.
                </span>
              </span>
            </span>
            <template v-if="supportedModalitiesFor(name).length > 1 && editingKey === `provider.${name}.paymentModality`">
              <select
                v-model="editValue"
                :aria-label="`Edit payment modality for ${name}`"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
                <option
                  v-for="m in supportedModalitiesFor(name)"
                  :key="m"
                  :value="m"
                >
                  {{ m }}
                </option>
              </select>
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry(`provider.${name}.paymentModality`)"
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
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ paymentModalityFor(name) }}</span>
              <button
                v-if="supportedModalitiesFor(name).length > 1"
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingKey = `provider.${name}.paymentModality`; editValue = paymentModalityFor(name)"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <span
                v-else
                class="relative group/lock p-1 text-fg-muted"
              >
                <LockClosedIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
                <span class="absolute right-0 top-7 z-20 hidden group-hover/lock:block w-56 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  {{ providerLabel(name) }} only supports {{ paymentModalityFor(name) }} billing.
                </span>
              </span>
            </template>
          </div>
          <!-- JCLAW-280: subscriptionMonthlyUsd — only when the selected modality is
                 SUBSCRIPTION. Pro-rated into the Chat Cost dashboard's subscription
                 subsection by (window_days / 30). -->
          <div
            v-if="paymentModalityFor(name) === 'SUBSCRIPTION' && supportedModalitiesFor(name).includes('SUBSCRIPTION')"
            class="px-4 py-2 flex items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              subscriptionMonthlyUsd
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-60 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Monthly USD you pay {{ providerLabel(name) }}. The Chat Cost dashboard pro-rates this to the selected time window (<code class="font-mono">monthly × window_days / 30</code>).
                </span>
              </span>
            </span>
            <template v-if="editingKey === `provider.${name}.subscriptionMonthlyUsd`">
              <span class="text-sm text-fg-muted">$</span>
              <input
                v-model="editValue"
                type="number"
                step="0.01"
                min="0"
                :aria-label="`Edit monthly subscription price for ${name}`"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry(`provider.${name}.subscriptionMonthlyUsd`)"
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
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">${{ Number(subscriptionMonthlyFor(name)).toFixed(2) }}/mo</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingKey = `provider.${name}.subscriptionMonthlyUsd`; editValue = String(subscriptionMonthlyFor(name))"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- Ollama-specific: keepAlive setting -->
          <div
            v-if="name.toLowerCase().includes('ollama')"
            class="px-4 py-2 flex items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              keepAlive
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-56 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  How long the model stays loaded between requests. Use <code class="font-mono text-fg-primary">30m</code> for 30 minutes, <code class="font-mono text-fg-primary">-1</code> to keep forever.
                </span>
              </span>
            </span>
            <template v-if="editingKey === 'ollama.keepAlive'">
              <input
                v-model="editValue"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry('ollama.keepAlive')"
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
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ ollamaKeepAlive }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingKey = 'ollama.keepAlive'; editValue = ollamaKeepAlive"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- Models row -->
          <div class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">models</span>
            <span class="flex-1 text-sm text-fg-primary">{{ getProviderModels(name).length }} model{{ getProviderModels(name).length !== 1 ? 's' : '' }}</span>
            <button
              :disabled="discoveryLoading && discoveryProvider === name"
              class="p-1 text-fg-muted hover:text-blue-400 disabled:animate-spin transition-colors"
              title="Discover models from provider"
              @click="startDiscovery(name)"
            >
              <ArrowPathIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              :title="expandedModelsProvider === name ? 'Close models' : 'Manage models'"
              @click="toggleModelsPanel(name)"
            >
              <ChevronUpIcon
                v-if="expandedModelsProvider === name"
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
              <Cog6ToothIcon
                v-else
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>

        <!-- Expanded model management panel -->
        <div
          v-if="expandedModelsProvider === name"
          class="border-t border-input"
        >
          <div class="divide-y divide-border">
            <div
              v-for="(model, idx) in getProviderModels(name)"
              :key="model.id"
              class="px-4 py-2.5"
            >
              <!-- Editing a model -->
              <template v-if="editingModelIdx === idx">
                <div class="grid grid-cols-2 gap-2 mb-2">
                  <label
                    :for="`model-id-${name}`"
                    class="block"
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">ID</span>
                    <input
                      :id="`model-id-${name}`"
                      v-model="modelForm.id"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                  </label>
                  <label
                    :for="`model-name-${name}`"
                    class="block"
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Display Name</span>
                    <input
                      :id="`model-name-${name}`"
                      v-model="modelForm.name"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong focus:outline-hidden"
                    >
                  </label>
                  <label
                    :for="`model-ctx-${name}`"
                    class="block"
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Context Window</span>
                    <input
                      :id="`model-ctx-${name}`"
                      v-model.number="modelForm.contextWindow"
                      type="number"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                    <span
                      v-if="!modelForm.contextWindow || modelForm.contextWindow <= 0"
                      class="block text-[10px] text-warning mt-0.5"
                    >Unknown — set from provider docs. Compaction and /usage depend on this.</span>
                  </label>
                  <label
                    :for="`model-maxtok-${name}`"
                    class="block"
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Max Tokens</span>
                    <input
                      :id="`model-maxtok-${name}`"
                      v-model.number="modelForm.maxTokens"
                      type="number"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                  </label>
                  <label
                    :for="`model-price-in-${name}`"
                    class="block"
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Input $/M tokens</span>
                    <input
                      :id="`model-price-in-${name}`"
                      v-model.number="modelForm.promptPrice"
                      type="number"
                      step="0.01"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                  </label>
                  <label
                    :for="`model-price-out-${name}`"
                    class="block"
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Output $/M tokens</span>
                    <input
                      :id="`model-price-out-${name}`"
                      v-model.number="modelForm.completionPrice"
                      type="number"
                      step="0.01"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                  </label>
                  <label
                    :for="`model-cache-read-${name}`"
                    class="block"
                    title="Anthropic: ~0.1× input. OpenAI: ~0.5× input. Leave -1 to auto-default."
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Cache read $/M</span>
                    <input
                      :id="`model-cache-read-${name}`"
                      v-model.number="modelForm.cachedReadPrice"
                      type="number"
                      step="0.01"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                  </label>
                  <label
                    :for="`model-cache-write-${name}`"
                    class="block"
                    title="Anthropic 5-min TTL: ~1.25× input. OpenAI: n/a. Leave -1 to auto-default."
                  >
                    <span class="block text-[10px] text-fg-muted mb-0.5">Cache write $/M</span>
                    <input
                      :id="`model-cache-write-${name}`"
                      v-model.number="modelForm.cacheWritePrice"
                      type="number"
                      step="0.01"
                      class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                    >
                  </label>
                </div>
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-3 flex-wrap">
                    <label
                      :for="`model-thinking-${name}`"
                      class="flex items-center gap-1.5 text-xs text-fg-muted"
                    >
                      <input
                        :id="`model-thinking-${name}`"
                        v-model="modelForm.supportsThinking"
                        type="checkbox"
                        class="accent-white"
                      > Supports Thinking
                    </label>
                    <label
                      v-if="modelForm.supportsThinking"
                      :for="`model-always-thinks-${name}`"
                      class="flex items-center gap-1.5 text-xs text-fg-muted"
                      title="Pure reasoning models (o1, o3, DeepSeek-R1, QwQ) that always think regardless of the off toggle"
                    >
                      <input
                        :id="`model-always-thinks-${name}`"
                        v-model="modelForm.alwaysThinks"
                        type="checkbox"
                        class="accent-white"
                      > Always Thinks
                    </label>
                    <label
                      :for="`model-vision-${name}`"
                      class="flex items-center gap-1.5 text-xs text-fg-muted"
                    >
                      <input
                        :id="`model-vision-${name}`"
                        v-model="modelForm.supportsVision"
                        type="checkbox"
                        class="accent-white"
                      > Supports Vision
                    </label>
                    <label
                      :for="`model-audio-${name}`"
                      class="flex items-center gap-1.5 text-xs text-fg-muted"
                    >
                      <input
                        :id="`model-audio-${name}`"
                        v-model="modelForm.supportsAudio"
                        type="checkbox"
                        class="accent-white"
                      > Supports Audio
                    </label>
                    <label
                      :for="`model-video-${name}`"
                      class="flex items-center gap-1.5 text-xs text-fg-muted"
                    >
                      <input
                        :id="`model-video-${name}`"
                        v-model="modelForm.supportsVideo"
                        type="checkbox"
                        class="accent-white"
                      > Supports Video
                    </label>
                  </div>
                  <div class="flex items-center gap-1">
                    <button
                      class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                      title="Save"
                      @click="saveEditedModel(name)"
                    >
                      <CheckIcon
                        class="w-3.5 h-3.5"
                        aria-hidden="true"
                      />
                    </button>
                    <button
                      class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                      title="Cancel"
                      @click="editingModelIdx = null"
                    >
                      <XMarkIcon
                        class="w-3.5 h-3.5"
                        aria-hidden="true"
                      />
                    </button>
                    <button
                      class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                      title="Delete model"
                      @click="deleteModel(name, idx)"
                    >
                      <TrashIcon
                        class="w-3.5 h-3.5"
                        aria-hidden="true"
                      />
                    </button>
                  </div>
                </div>
              </template>
              <!-- Display a model -->
              <template v-else>
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <span
                      v-if="getModelRank(name, model.id)"
                      class="shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded"
                      :class="getModelRank(name, model.id)! <= 3 ? 'text-amber-700 dark:text-amber-400 bg-amber-400/10 border border-amber-400/30' : 'text-fg-muted bg-muted border border-input'"
                      :title="`#${getModelRank(name, model.id)} on provider leaderboard`"
                    >
                      #{{ getModelRank(name, model.id) }}
                    </span>
                    <div>
                      <span class="text-sm text-fg-strong font-mono">{{ model.id }}</span>
                      <span
                        v-if="model.name"
                        class="ml-2 text-xs text-fg-muted"
                      >{{ model.name }}</span>
                      <span
                        v-if="model.supportsThinking && !model.alwaysThinks"
                        class="ml-2 text-[10px] text-emerald-700 dark:text-emerald-400 border border-emerald-400/30 px-1"
                      >thinking</span>
                      <span
                        v-else-if="model.alwaysThinks"
                        class="ml-2 inline-flex items-center gap-0.5 text-[10px] text-emerald-300 border border-emerald-500/60 bg-emerald-500/15 px-1"
                        title="Pure reasoning model — thinking is always on"
                      >thinking<LockClosedIcon
                        class="w-2 h-2"
                        aria-hidden="true"
                      /></span>
                      <span
                        v-if="model.supportsVision"
                        class="ml-2 text-[10px] text-sky-700 dark:text-sky-400 border border-sky-400/30 px-1"
                      >vision</span>
                      <span
                        v-if="model.supportsAudio"
                        class="ml-2 text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
                      >audio</span>
                      <span
                        v-if="model.supportsVideo"
                        class="ml-2 text-[10px] text-purple-400 border border-purple-400/30 px-1"
                      >video</span>
                    </div>
                  </div>
                  <div class="flex items-center gap-2">
                    <span
                      v-if="(model.promptPrice ?? 0) > 0"
                      class="text-xs text-amber-700/70 dark:text-amber-400/70 font-mono"
                      :title="`Input: $${model.promptPrice}/M tokens, Output: $${model.completionPrice}/M tokens`"
                    >
                      ${{ (model.promptPrice ?? 0) < 1 ? (model.promptPrice ?? 0).toFixed(2) : (model.promptPrice ?? 0).toFixed(0) }}/M
                    </span>
                    <span class="text-xs text-fg-muted font-mono">{{ ((model.contextWindow ?? 0) / 1024).toFixed(0) }}K ctx</span>
                    <span class="text-xs text-fg-muted font-mono">{{ ((model.maxTokens ?? 0) / 1024).toFixed(0) }}K out</span>
                    <button
                      class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                      title="Edit model"
                      @click="startEditModel(name, idx)"
                    >
                      <PencilIcon
                        class="w-3.5 h-3.5"
                        aria-hidden="true"
                      />
                    </button>
                  </div>
                </div>
              </template>
            </div>
          </div>

          <!-- Add model form -->
          <div class="px-4 py-2.5 border-t border-border">
            <template v-if="addingModel">
              <div class="grid grid-cols-2 gap-2 mb-2">
                <label
                  :for="`addmodel-id-${name}`"
                  class="block"
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">ID</span>
                  <input
                    :id="`addmodel-id-${name}`"
                    v-model="modelForm.id"
                    placeholder="model-id"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                </label>
                <label
                  :for="`addmodel-name-${name}`"
                  class="block"
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Display Name</span>
                  <input
                    :id="`addmodel-name-${name}`"
                    v-model="modelForm.name"
                    placeholder="Model Name"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong focus:outline-hidden"
                  >
                </label>
                <label
                  :for="`addmodel-ctx-${name}`"
                  class="block"
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Context Window</span>
                  <input
                    :id="`addmodel-ctx-${name}`"
                    v-model.number="modelForm.contextWindow"
                    type="number"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                  <span
                    v-if="!modelForm.contextWindow || modelForm.contextWindow <= 0"
                    class="block text-[10px] text-warning mt-0.5"
                  >Unknown — set from provider docs. Compaction and /usage depend on this.</span>
                </label>
                <label
                  :for="`addmodel-maxtok-${name}`"
                  class="block"
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Max Tokens</span>
                  <input
                    :id="`addmodel-maxtok-${name}`"
                    v-model.number="modelForm.maxTokens"
                    type="number"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                </label>
                <label
                  :for="`addmodel-price-in-${name}`"
                  class="block"
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Input $/M tokens</span>
                  <input
                    :id="`addmodel-price-in-${name}`"
                    v-model.number="modelForm.promptPrice"
                    type="number"
                    step="0.01"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                </label>
                <label
                  :for="`addmodel-price-out-${name}`"
                  class="block"
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Output $/M tokens</span>
                  <input
                    :id="`addmodel-price-out-${name}`"
                    v-model.number="modelForm.completionPrice"
                    type="number"
                    step="0.01"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                </label>
                <label
                  :for="`addmodel-cache-read-${name}`"
                  class="block"
                  title="Anthropic: ~0.1× input. OpenAI: ~0.5× input. Leave -1 to auto-default."
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Cache read $/M</span>
                  <input
                    :id="`addmodel-cache-read-${name}`"
                    v-model.number="modelForm.cachedReadPrice"
                    type="number"
                    step="0.01"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                </label>
                <label
                  :for="`addmodel-cache-write-${name}`"
                  class="block"
                  title="Anthropic 5-min TTL: ~1.25× input. OpenAI: n/a. Leave -1 to auto-default."
                >
                  <span class="block text-[10px] text-fg-muted mb-0.5">Cache write $/M</span>
                  <input
                    :id="`addmodel-cache-write-${name}`"
                    v-model.number="modelForm.cacheWritePrice"
                    type="number"
                    step="0.01"
                    class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
                  >
                </label>
              </div>
              <div class="flex items-center justify-between">
                <div class="flex items-center gap-3 flex-wrap">
                  <label
                    :for="`addmodel-thinking-${name}`"
                    class="flex items-center gap-1.5 text-xs text-fg-muted"
                  >
                    <input
                      :id="`addmodel-thinking-${name}`"
                      v-model="modelForm.supportsThinking"
                      type="checkbox"
                      class="accent-white"
                    > Supports Thinking
                  </label>
                  <label
                    v-if="modelForm.supportsThinking"
                    :for="`addmodel-always-thinks-${name}`"
                    class="flex items-center gap-1.5 text-xs text-fg-muted"
                    title="Pure reasoning models (o1, o3, DeepSeek-R1, QwQ) that always think regardless of the off toggle"
                  >
                    <input
                      :id="`addmodel-always-thinks-${name}`"
                      v-model="modelForm.alwaysThinks"
                      type="checkbox"
                      class="accent-white"
                    > Always Thinks
                  </label>
                  <label
                    :for="`addmodel-vision-${name}`"
                    class="flex items-center gap-1.5 text-xs text-fg-muted"
                  >
                    <input
                      :id="`addmodel-vision-${name}`"
                      v-model="modelForm.supportsVision"
                      type="checkbox"
                      class="accent-white"
                    > Supports Vision
                  </label>
                  <label
                    :for="`addmodel-audio-${name}`"
                    class="flex items-center gap-1.5 text-xs text-fg-muted"
                  >
                    <input
                      :id="`addmodel-audio-${name}`"
                      v-model="modelForm.supportsAudio"
                      type="checkbox"
                      class="accent-white"
                    > Supports Audio
                  </label>
                  <label
                    :for="`addmodel-video-${name}`"
                    class="flex items-center gap-1.5 text-xs text-fg-muted"
                  >
                    <input
                      :id="`addmodel-video-${name}`"
                      v-model="modelForm.supportsVideo"
                      type="checkbox"
                      class="accent-white"
                    > Supports Video
                  </label>
                </div>
                <div class="flex items-center gap-1">
                  <button
                    :disabled="!modelForm.id.trim()"
                    class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 disabled:opacity-40 transition-colors"
                    title="Add model"
                    @click="saveNewModel(name)"
                  >
                    <CheckIcon
                      class="w-3.5 h-3.5"
                      aria-hidden="true"
                    />
                  </button>
                  <button
                    class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                    title="Cancel"
                    @click="addingModel = false"
                  >
                    <XMarkIcon
                      class="w-3.5 h-3.5"
                      aria-hidden="true"
                    />
                  </button>
                </div>
              </div>
            </template>
            <template v-else>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Add model"
                @click="startAddModel"
              >
                <PlusIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>

          <!-- No models -->
          <div
            v-if="!getProviderModels(name).length && !addingModel"
            class="px-4 py-4 text-xs text-fg-muted text-center"
          >
            No models configured
          </div>
        </div>

        <!-- Model discovery panel -->
        <div
          v-if="discoveryProvider === name"
          class="border-t border-blue-200 dark:border-blue-800/40 bg-blue-50 dark:bg-blue-950/20"
        >
          <div class="px-4 py-3 flex items-center justify-between border-b border-border">
            <div class="flex flex-col gap-0.5">
              <div class="flex items-center gap-2">
                <span class="text-xs font-medium text-blue-700 dark:text-blue-400">Discover Models</span>
                <span
                  v-if="!discoveryLoading && discoveredModels.length"
                  class="text-[10px] text-fg-muted"
                >
                  {{ discoveredModels.length }} available
                </span>
              </div>
              <!-- JCLAW-183: tell operators why a model they expected to see
                     might be missing. The backend filter drops embedding /
                     audio / image-generation models because binding a chat
                     agent to one would fail at the first chat call. -->
              <span class="text-[10px] text-fg-muted">
                Embedding-only and audio-only models are hidden; bind chat agents to chat-capable models.
              </span>
            </div>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Close"
              @click="closeDiscovery"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </div>

          <!-- Loading -->
          <div
            v-if="discoveryLoading"
            class="px-4 py-6 text-center"
          >
            <span class="text-xs text-fg-muted animate-pulse">Fetching models from {{ name }}...</span>
          </div>

          <!-- Error -->
          <div
            v-else-if="discoveryError"
            class="px-4 py-4 text-center"
          >
            <span class="text-xs text-red-700 dark:text-red-400">{{ discoveryError }}</span>
          </div>

          <!-- Results -->
          <template v-else-if="discoveredModels.length">
            <!-- Search + filters -->
            <div class="px-4 py-2 flex flex-wrap items-center gap-2 border-b border-border">
              <MagnifyingGlassIcon
                class="w-3.5 h-3.5 text-fg-muted shrink-0"
                aria-hidden="true"
              />
              <input
                v-model="discoverySearch"
                placeholder="Search models..."
                aria-label="Search discovered models"
                class="flex-1 px-2 py-1 bg-transparent text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden"
              >
              <select
                v-model="discoveryFilterThinking"
                aria-label="Filter by thinking support"
                class="bg-muted border border-input text-[10px] text-fg-muted px-1.5 py-0.5 focus:outline-hidden"
              >
                <option value="all">
                  Thinking: All
                </option>
                <option value="yes">
                  Thinking: Yes
                </option>
                <option value="no">
                  Thinking: No
                </option>
              </select>
              <select
                v-if="discoveryHasVision"
                v-model="discoveryFilterVision"
                aria-label="Filter by vision support"
                class="bg-muted border border-input text-[10px] text-fg-muted px-1.5 py-0.5 focus:outline-hidden"
              >
                <option value="all">
                  Vision: All
                </option>
                <option value="yes">
                  Vision: Yes
                </option>
                <option value="no">
                  Vision: No
                </option>
              </select>
              <select
                v-if="discoveryHasAudio"
                v-model="discoveryFilterAudio"
                aria-label="Filter by audio support"
                class="bg-muted border border-input text-[10px] text-fg-muted px-1.5 py-0.5 focus:outline-hidden"
              >
                <option value="all">
                  Audio: All
                </option>
                <option value="yes">
                  Audio: Yes
                </option>
                <option value="no">
                  Audio: No
                </option>
              </select>
              <select
                v-if="discoveryHasVideo"
                v-model="discoveryFilterVideo"
                aria-label="Filter by video support"
                class="bg-muted border border-input text-[10px] text-fg-muted px-1.5 py-0.5 focus:outline-hidden"
              >
                <option value="all">
                  Video: All
                </option>
                <option value="yes">
                  Video: Yes
                </option>
                <option value="no">
                  Video: No
                </option>
              </select>
              <select
                v-if="discoveryHasFreeModels"
                v-model="discoveryFilterCost"
                aria-label="Filter by cost"
                class="bg-muted border border-input text-[10px] text-fg-muted px-1.5 py-0.5 focus:outline-hidden"
              >
                <option value="all">
                  Cost: All
                </option>
                <option value="free">
                  Free
                </option>
                <option value="paid">
                  Paid
                </option>
              </select>
              <select
                v-if="discoveryHasRankings"
                v-model="discoveryFilterPopular"
                aria-label="Filter by leaderboard rank"
                class="bg-muted border border-input text-[10px] text-fg-muted px-1.5 py-0.5 focus:outline-hidden"
              >
                <option value="all">
                  Rank: All
                </option>
                <option value="top10">
                  Top 10
                </option>
                <option value="top25">
                  Top 25
                </option>
                <option value="ranked">
                  All Ranked
                </option>
              </select>
              <span class="text-[10px] text-fg-muted shrink-0">{{ filteredDiscoveredModels.length }}</span>
              <button
                class="text-[10px] text-fg-muted hover:text-fg-strong transition-colors shrink-0"
                @click="selectAllDiscovered"
              >
                {{ discoverySelected.size === filteredDiscoveredModels.length ? 'None' : 'All' }}
              </button>
            </div>

            <!-- Model list -->
            <div class="max-h-72 overflow-y-auto divide-y divide-border">
              <button
                v-for="model in filteredDiscoveredModels"
                :key="model.id"
                type="button"
                :class="discoverySelected.has(model.id) ? 'bg-blue-100 dark:bg-blue-900/20' : ''"
                class="w-full text-left px-4 py-1.5 flex items-center gap-3 hover:bg-muted cursor-pointer transition-colors bg-transparent border-0"
                @click="toggleDiscoverySelect(model.id)"
              >
                <span
                  class="shrink-0 w-3.5 h-3.5 border border-input flex items-center justify-center text-[10px]"
                  :class="discoverySelected.has(model.id) ? 'bg-blue-500 border-blue-500 text-white' : ''"
                >
                  <span v-if="discoverySelected.has(model.id)">&#10003;</span>
                </span>
                <span
                  v-if="model.leaderboardRank"
                  class="shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded"
                  :class="model.leaderboardRank <= 3 ? 'text-amber-700 dark:text-amber-400 bg-amber-400/10 border border-amber-400/30' : 'text-fg-muted bg-muted border border-input'"
                  :title="`#${model.leaderboardRank} on provider leaderboard`"
                >
                  #{{ model.leaderboardRank }}
                </span>
                <div class="flex-1 min-w-0">
                  <span class="text-xs text-fg-strong font-mono truncate block">{{ model.id }}</span>
                  <span
                    v-if="model.name && model.name !== model.id"
                    class="text-[10px] text-fg-muted"
                  >{{ model.name }}</span>
                </div>
                <div class="flex items-center gap-2 shrink-0">
                  <span
                    v-if="model.isFree"
                    class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
                  >free</span>
                  <span
                    v-else-if="(model.promptPrice ?? -1) >= 0"
                    class="text-[10px] text-fg-muted font-mono"
                    :title="`$${(model.promptPrice ?? 0).toFixed(2)}/M in, $${(model.completionPrice ?? 0).toFixed(2)}/M out`"
                  >
                    ${{ (model.promptPrice ?? 0) < 1 ? (model.promptPrice ?? 0).toFixed(2) : (model.promptPrice ?? 0).toFixed(0) }}/M
                  </span>
                  <span
                    v-if="model.alwaysThinks"
                    class="inline-flex items-center gap-0.5 text-[10px] text-emerald-300 border border-emerald-500/60 bg-emerald-500/15 px-1"
                    :title="model.alwaysThinksDetectedFromProvider
                      ? 'Pure reasoning model (provider-confirmed) — thinking is always on'
                      : 'Pure reasoning model (id-pattern match) — thinking is always on'"
                  >thinking<LockClosedIcon
                    class="w-2 h-2"
                    aria-hidden="true"
                  /></span>
                  <span
                    v-else-if="model.supportsThinking && model.thinkingDetectedFromProvider"
                    class="text-[10px] text-emerald-700 dark:text-emerald-400 border border-emerald-400/30 px-1"
                    title="Thinking support confirmed by provider"
                  >thinking</span>
                  <span
                    v-else-if="model.supportsThinking"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Thinking support guessed from model name (not confirmed by provider)"
                  >thinking?</span>
                  <span
                    v-if="model.supportsVision && model.visionDetectedFromProvider"
                    class="text-[10px] text-sky-700 dark:text-sky-400 border border-sky-400/30 px-1"
                    title="Vision support confirmed by provider"
                  >vision</span>
                  <span
                    v-else-if="model.supportsVision"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Vision support guessed from model name (not confirmed by provider)"
                  >vision?</span>
                  <span
                    v-if="model.supportsAudio && model.audioDetectedFromProvider"
                    class="text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
                    title="Audio support confirmed by provider"
                  >audio</span>
                  <span
                    v-else-if="model.supportsAudio"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Audio support guessed from model name (not confirmed by provider)"
                  >audio?</span>
                  <span
                    v-if="model.supportsVideo && model.videoDetectedFromProvider"
                    class="text-[10px] text-purple-400 border border-purple-400/30 px-1"
                    title="Video support confirmed by provider"
                  >video</span>
                  <span
                    v-else-if="model.supportsVideo"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Video support guessed from model name (not confirmed by provider)"
                  >video?</span>
                  <span
                    v-if="model.contextWindow"
                    class="text-[10px] text-fg-muted font-mono"
                  >{{ (model.contextWindow / 1024).toFixed(0) }}K</span>
                </div>
              </button>
            </div>

            <!-- Add selected -->
            <div class="px-4 py-2.5 border-t border-border flex items-center justify-between">
              <span class="text-[10px] text-fg-muted">{{ discoverySelected.size }} selected</span>
              <button
                :disabled="discoverySelected.size === 0"
                class="px-3 py-1 bg-blue-600 text-white text-xs font-medium hover:bg-blue-500 disabled:opacity-40 transition-colors"
                @click="addDiscoveredModels"
              >
                Add Selected
              </button>
            </div>
          </template>

          <!-- Empty results -->
          <div
            v-else-if="!discoveryLoading"
            class="px-4 py-6 text-center"
          >
            <span class="text-xs text-fg-muted">All available models are already configured</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
