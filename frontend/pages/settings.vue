<script setup lang="ts">
const { data: configData, refresh } = await useFetch<{ entries: any[] }>('/api/config')
const newKey = ref('')
const newValue = ref('')
const saving = ref(false)
const editingKey = ref<string | null>(null)
const editValue = ref('')

async function saveNew() {
  if (!newKey.value.trim()) return
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: newKey.value, value: newValue.value }
    })
    newKey.value = ''
    newValue.value = ''
    refresh()
  } catch (e) {
    console.error('Failed to save config:', e)
  } finally {
    saving.value = false
  }
}

async function updateEntry(key: string) {
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key, value: editValue.value }
    })
    editingKey.value = null
    refresh()
  } catch (e) {
    console.error('Failed to update config:', e)
  } finally {
    saving.value = false
  }
}

async function deleteEntry(key: string) {
  await $fetch(`/api/config/${encodeURIComponent(key)}`, { method: 'DELETE' })
  refresh()
}

function startEdit(entry: any) {
  editingKey.value = entry.key
  editValue.value = entry.value
}

function isSensitive(key: string) {
  const lower = key.toLowerCase()
  return ['key', 'secret', 'password', 'token'].some(s => lower.includes(s))
}

// Config keys managed by dedicated UI sections (excluded from general Configuration)
const MANAGED_CONFIG_KEYS = new Set([
  'agent.maxToolRounds',
  'jclaw.tools.playwright.enabled', 'jclaw.tools.playwright.headless',
  'jclaw.tools.shell.enabled', 'shell.allowlist', 'shell.defaultTimeoutSeconds',
  'shell.maxTimeoutSeconds', 'shell.maxOutputBytes',
])

// Agent config
const agentMaxToolRounds = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'agent.maxToolRounds')?.value ?? '10'
})

const editingAgentField = ref<string | null>(null)
const agentFieldEdit = ref('')

async function saveAgentField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingAgentField.value = null
    refresh()
  } finally {
    saving.value = false
  }
}

// Playwright config
const playwrightEnabled = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'jclaw.tools.playwright.enabled')?.value === 'true'
})

const playwrightHeadless = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'jclaw.tools.playwright.headless')?.value !== 'false'
})

async function togglePlaywrightEnabled() {
  const newVal = playwrightEnabled.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'jclaw.tools.playwright.enabled', value: newVal } })
  refresh()
}

async function togglePlaywrightHeadless() {
  const newVal = playwrightHeadless.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'jclaw.tools.playwright.headless', value: newVal } })
  refresh()
}

// Shell execution config
const SHELL_KEYS = ['shell.allowlist', 'shell.defaultTimeoutSeconds', 'shell.maxTimeoutSeconds', 'shell.maxOutputBytes'] as const

const shellEnabled = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'jclaw.tools.shell.enabled')?.value === 'true'
})

const shellConfig = computed(() => {
  const entries = configData.value?.entries ?? []
  const map = new Map<string, string>()
  for (const e of entries) {
    if (SHELL_KEYS.includes(e.key as any)) {
      map.set(e.key, e.value)
    }
  }
  return {
    allowlist: map.get('shell.allowlist') ?? '',
    defaultTimeout: map.get('shell.defaultTimeoutSeconds') ?? '30',
    maxTimeout: map.get('shell.maxTimeoutSeconds') ?? '300',
    maxOutput: map.get('shell.maxOutputBytes') ?? '102400',
  }
})

const shellAllowlistEdit = ref('')
const shellTimeoutEdit = ref('')
const editingShellField = ref<string | null>(null)

function startShellEdit(field: string, value: string) {
  editingShellField.value = field
  if (field === 'allowlist') shellAllowlistEdit.value = value
  else shellTimeoutEdit.value = value
}

async function saveShellField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingShellField.value = null
    refresh()
  } finally {
    saving.value = false
  }
}

async function toggleShellEnabled() {
  const newVal = shellEnabled.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'jclaw.tools.shell.enabled', value: newVal } })
  refresh()
}


const SEARCH_PROVIDERS: Record<string, { label: string, keys: { key: string, label: string, placeholder: string }[] }> = {
  exa: {
    label: 'Exa',
    keys: [
      { key: 'exa.apiKey', label: 'apiKey', placeholder: 'Your Exa API key from exa.ai' }
    ]
  },
  brave: {
    label: 'Brave',
    keys: [
      { key: 'brave.apiKey', label: 'apiKey', placeholder: 'Your Brave Search API key from brave.com/search/api' }
    ]
  },
  tavily: {
    label: 'Tavily',
    keys: [
      { key: 'tavily.apiKey', label: 'apiKey', placeholder: 'Your Tavily API key from tavily.com' }
    ]
  }
}

// --- Model management ---
const expandedModelsProvider = ref<string | null>(null)
const editingModelIdx = ref<number | null>(null)
const modelForm = ref({ id: '', name: '', contextWindow: 131072, maxTokens: 8192, supportsThinking: false })
const addingModel = ref(false)

function getProviderModels(providerName: string): any[] {
  const entries = configData.value?.entries ?? []
  const modelsEntry = entries.find((e: any) => e.key === `provider.${providerName}.models`)
  if (!modelsEntry?.value) return []
  try { return JSON.parse(modelsEntry.value) } catch { return [] }
}

// Rankings cache for configured models (providerName -> { modelId -> rank })
const configuredModelRanks = ref<Map<string, Map<string, number>>>(new Map())

function toggleModelsPanel(providerName: string) {
  if (expandedModelsProvider.value === providerName) {
    expandedModelsProvider.value = null
  } else {
    expandedModelsProvider.value = providerName
    fetchRanksForProvider(providerName)
  }
  editingModelIdx.value = null
  addingModel.value = false
}

async function fetchRanksForProvider(providerName: string) {
  if (configuredModelRanks.value.has(providerName)) return
  try {
    const res = await $fetch<any>(`/api/providers/${providerName}/discover-models`, { method: 'POST' })
    const rankMap = new Map<string, number>()
    for (const m of (res.models || [])) {
      if (m.leaderboardRank) rankMap.set(m.id, m.leaderboardRank)
    }
    configuredModelRanks.value = new Map(configuredModelRanks.value).set(providerName, rankMap)
  } catch {
    // Best-effort — no ranks if discovery fails
  }
}

function getModelRank(providerName: string, modelId: string): number | null {
  return configuredModelRanks.value.get(providerName)?.get(modelId) ?? null
}

function startEditModel(providerName: string, idx: number) {
  const models = getProviderModels(providerName)
  const m = models[idx]
  modelForm.value = {
    id: m.id || '',
    name: m.name || '',
    contextWindow: m.contextWindow || 131072,
    maxTokens: m.maxTokens || 8192,
    supportsThinking: m.supportsThinking || false
  }
  editingModelIdx.value = idx
  addingModel.value = false
}

function startAddModel() {
  modelForm.value = { id: '', name: '', contextWindow: 131072, maxTokens: 8192, supportsThinking: false }
  addingModel.value = true
  editingModelIdx.value = null
}

async function saveModels(providerName: string, models: any[]) {
  await $fetch('/api/config', {
    method: 'POST',
    body: { key: `provider.${providerName}.models`, value: JSON.stringify(models) }
  })
  refresh()
}

async function saveEditedModel(providerName: string) {
  const models = getProviderModels(providerName)
  if (editingModelIdx.value !== null) {
    models[editingModelIdx.value] = { ...modelForm.value }
  }
  await saveModels(providerName, models)
  editingModelIdx.value = null
}

async function saveNewModel(providerName: string) {
  if (!modelForm.value.id.trim()) return
  const models = getProviderModels(providerName)
  models.push({ ...modelForm.value })
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
const discoveredModels = ref<any[]>([])
const discoverySearch = ref('')
const discoverySelected = ref<Set<string>>(new Set())
const discoveryFilterThinking = ref('all') // 'all' | 'yes' | 'no'
const discoveryFilterCost = ref('all')     // 'all' | 'free' | 'paid'
const discoveryFilterPopular = ref('all')  // 'all' | 'ranked' | 'top10' | 'top25'

const filteredDiscoveredModels = computed(() => {
  let list = discoveredModels.value

  // Text search
  const q = discoverySearch.value.toLowerCase().trim()
  if (q) {
    list = list.filter((m: any) =>
      m.id.toLowerCase().includes(q) || (m.name || '').toLowerCase().includes(q)
    )
  }

  // Thinking filter
  if (discoveryFilterThinking.value === 'yes') {
    list = list.filter((m: any) => m.supportsThinking)
  } else if (discoveryFilterThinking.value === 'no') {
    list = list.filter((m: any) => !m.supportsThinking)
  }

  // Cost filter
  if (discoveryFilterCost.value === 'free') {
    list = list.filter((m: any) => m.isFree)
  } else if (discoveryFilterCost.value === 'paid') {
    list = list.filter((m: any) => !m.isFree)
  }

  // Popularity filter
  if (discoveryFilterPopular.value === 'ranked') {
    list = list.filter((m: any) => m.leaderboardRank)
  } else if (discoveryFilterPopular.value === 'top10') {
    list = list.filter((m: any) => m.leaderboardRank && m.leaderboardRank <= 10)
  } else if (discoveryFilterPopular.value === 'top25') {
    list = list.filter((m: any) => m.leaderboardRank && m.leaderboardRank <= 25)
  }

  return list
})

// Whether pricing data is available for this provider's models
const discoveryHasPricing = computed(() =>
  discoveredModels.value.some((m: any) => m.promptPrice >= 0)
)

// Whether leaderboard ranking data is available
const discoveryHasRankings = computed(() =>
  discoveredModels.value.some((m: any) => m.leaderboardRank)
)

async function startDiscovery(providerName: string) {
  discoveryProvider.value = providerName
  discoveryLoading.value = true
  discoveryError.value = ''
  discoveredModels.value = []
  discoverySearch.value = ''
  discoverySelected.value = new Set()
  discoveryFilterThinking.value = 'all'
  discoveryFilterCost.value = 'all'
  discoveryFilterPopular.value = 'all'
  expandedModelsProvider.value = null

  try {
    const res = await $fetch<any>(`/api/providers/${providerName}/discover-models`, { method: 'POST' })
    // Filter out models already configured
    const existing = new Set(getProviderModels(providerName).map((m: any) => m.id))
    discoveredModels.value = (res.models || []).filter((m: any) => !existing.has(m.id))
  } catch (e: any) {
    discoveryError.value = e.data?.message || e.message || 'Failed to fetch models'
  } finally {
    discoveryLoading.value = false
  }
}

function toggleDiscoverySelect(modelId: string) {
  const s = new Set(discoverySelected.value)
  if (s.has(modelId)) s.delete(modelId); else s.add(modelId)
  discoverySelected.value = s
}

function selectAllDiscovered() {
  if (discoverySelected.value.size === filteredDiscoveredModels.value.length) {
    discoverySelected.value = new Set()
  } else {
    discoverySelected.value = new Set(filteredDiscoveredModels.value.map((m: any) => m.id))
  }
}

async function addDiscoveredModels() {
  if (!discoveryProvider.value || discoverySelected.value.size === 0) return
  const existing = getProviderModels(discoveryProvider.value)
  const toAdd = discoveredModels.value
    .filter((m: any) => discoverySelected.value.has(m.id))
    .map((m: any) => ({
      id: m.id,
      name: m.name,
      contextWindow: m.contextWindow,
      maxTokens: m.maxTokens,
      // Only auto-enable thinking if the provider confirmed it via metadata
      supportsThinking: m.thinkingDetectedFromProvider ? m.supportsThinking : false
    }))
  const merged = [...existing, ...toAdd]
  await saveModels(discoveryProvider.value, merged)
  discoveryProvider.value = null
}

function closeDiscovery() {
  discoveryProvider.value = null
}

// Group config entries by provider
const providerEntries = computed(() => {
  const entries = configData.value?.entries ?? []
  const providers = new Map<string, any[]>()
  const searchEntries = new Map<string, Map<string, any>>()
  const other: any[] = []

  // Initialize search providers with all expected keys
  for (const [id, def] of Object.entries(SEARCH_PROVIDERS)) {
    const keyMap = new Map<string, any>()
    for (const k of def.keys) {
      keyMap.set(k.key, { key: k.key, value: '', label: k.label, placeholder: k.placeholder })
    }
    searchEntries.set(id, keyMap)
  }

  for (const e of entries) {
    if (e.key.startsWith('provider.')) {
      const parts = e.key.split('.')
      const name = parts[1]
      if (!providers.has(name)) providers.set(name, [])
      providers.get(name)!.push(e)
    } else {
      // Check if it belongs to a search provider
      let matched = false
      for (const [id, def] of Object.entries(SEARCH_PROVIDERS)) {
        for (const k of def.keys) {
          if (e.key === k.key) {
            searchEntries.get(id)!.set(k.key, { ...e, label: k.label, placeholder: k.placeholder })
            matched = true
          }
        }
      }
      if (!matched && !MANAGED_CONFIG_KEYS.has(e.key)) other.push(e)
    }
  }
  return { providers, searchEntries, other }
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Settings</h1>

    <!-- Provider sections -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">LLM Providers</h2>
      <p class="text-xs text-neutral-600">Enter an API key for at least one provider to enable chat. Base URLs and models are pre-configured.</p>
      <div v-for="[name, entries] in providerEntries.providers" :key="name"
           class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">{{ name }}</span>
          <span v-if="entries.find((e: any) => e.key.endsWith('.apiKey') && e.value && !e.value.startsWith('****') && e.value !== '****')"
                class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">configured</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <!-- Non-models entries (baseUrl, apiKey) -->
          <div v-for="entry in entries.filter((e: any) => !e.key.endsWith('.models'))" :key="entry.key" class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">{{ entry.key.split('.').slice(2).join('.') }}</span>
            <template v-if="editingKey === entry.key">
              <input v-model="editValue"
                     :type="isSensitive(entry.key) ? 'password' : 'text'"
                     class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
              <button @click="updateEntry(entry.key)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
              </button>
              <button @click="editingKey = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ entry.value || '(empty)' }}</span>
              <button @click="startEdit(entry)" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
              </button>
            </template>
          </div>
          <!-- Models row -->
          <div class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">models</span>
            <span class="flex-1 text-sm text-neutral-300">{{ getProviderModels(name).length }} model{{ getProviderModels(name).length !== 1 ? 's' : '' }}</span>
            <button @click="startDiscovery(name)"
                    :disabled="discoveryLoading && discoveryProvider === name"
                    class="p-1 text-neutral-500 hover:text-blue-400 disabled:animate-spin transition-colors"
                    title="Discover models from provider">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>
            </button>
            <button @click="toggleModelsPanel(name)"
                    class="p-1 text-neutral-500 hover:text-white transition-colors"
                    :title="expandedModelsProvider === name ? 'Close models' : 'Manage models'">
              <svg v-if="expandedModelsProvider === name" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7" /></svg>
              <svg v-else class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
            </button>
          </div>
        </div>

        <!-- Expanded model management panel -->
        <div v-if="expandedModelsProvider === name" class="border-t border-neutral-700">
          <div class="divide-y divide-neutral-800/50">
            <div v-for="(model, idx) in getProviderModels(name)" :key="model.id"
                 class="px-4 py-2.5">
              <!-- Editing a model -->
              <template v-if="editingModelIdx === idx">
                <div class="grid grid-cols-2 gap-2 mb-2">
                  <div>
                    <label class="block text-[10px] text-neutral-600 mb-0.5">ID</label>
                    <input v-model="modelForm.id" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white font-mono focus:outline-none" />
                  </div>
                  <div>
                    <label class="block text-[10px] text-neutral-600 mb-0.5">Display Name</label>
                    <input v-model="modelForm.name" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white focus:outline-none" />
                  </div>
                  <div>
                    <label class="block text-[10px] text-neutral-600 mb-0.5">Context Window</label>
                    <input v-model.number="modelForm.contextWindow" type="number" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white font-mono focus:outline-none" />
                  </div>
                  <div>
                    <label class="block text-[10px] text-neutral-600 mb-0.5">Max Tokens</label>
                    <input v-model.number="modelForm.maxTokens" type="number" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white font-mono focus:outline-none" />
                  </div>
                </div>
                <div class="flex items-center justify-between">
                  <label class="flex items-center gap-1.5 text-xs text-neutral-400">
                    <input type="checkbox" v-model="modelForm.supportsThinking" class="accent-white" /> Supports Thinking
                  </label>
                  <div class="flex items-center gap-1">
                    <button @click="saveEditedModel(name)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
                      <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
                    </button>
                    <button @click="editingModelIdx = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                      <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
                    </button>
                    <button @click="deleteModel(name, idx)" class="p-1 text-neutral-600 hover:text-red-400 transition-colors" title="Delete model">
                      <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
                    </button>
                  </div>
                </div>
              </template>
              <!-- Display a model -->
              <template v-else>
                <div class="flex items-center justify-between">
                  <div class="flex items-center gap-2">
                    <span v-if="getModelRank(name, model.id)" class="shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded"
                          :class="getModelRank(name, model.id)! <= 3 ? 'text-amber-400 bg-amber-400/10 border border-amber-400/30' : 'text-neutral-400 bg-neutral-800 border border-neutral-700'"
                          :title="`#${getModelRank(name, model.id)} on provider leaderboard`">
                      #{{ getModelRank(name, model.id) }}
                    </span>
                    <div>
                      <span class="text-sm text-white font-mono">{{ model.id }}</span>
                      <span v-if="model.name" class="ml-2 text-xs text-neutral-500">{{ model.name }}</span>
                      <span v-if="model.supportsThinking" class="ml-2 text-[10px] text-blue-400 border border-blue-400/30 px-1">thinking</span>
                    </div>
                  </div>
                  <div class="flex items-center gap-3">
                    <span class="text-[10px] text-neutral-600 font-mono">{{ (model.contextWindow / 1024).toFixed(0) }}K ctx / {{ (model.maxTokens / 1024).toFixed(0) }}K out</span>
                    <button @click="startEditModel(name, idx)" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit model">
                      <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
                    </button>
                  </div>
                </div>
              </template>
            </div>
          </div>

          <!-- Add model form -->
          <div class="px-4 py-2.5 border-t border-neutral-800/50">
            <template v-if="addingModel">
              <div class="grid grid-cols-2 gap-2 mb-2">
                <div>
                  <label class="block text-[10px] text-neutral-600 mb-0.5">ID</label>
                  <input v-model="modelForm.id" placeholder="model-id" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white font-mono focus:outline-none" />
                </div>
                <div>
                  <label class="block text-[10px] text-neutral-600 mb-0.5">Display Name</label>
                  <input v-model="modelForm.name" placeholder="Model Name" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white focus:outline-none" />
                </div>
                <div>
                  <label class="block text-[10px] text-neutral-600 mb-0.5">Context Window</label>
                  <input v-model.number="modelForm.contextWindow" type="number" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white font-mono focus:outline-none" />
                </div>
                <div>
                  <label class="block text-[10px] text-neutral-600 mb-0.5">Max Tokens</label>
                  <input v-model.number="modelForm.maxTokens" type="number" class="w-full px-2 py-1 bg-neutral-800 border border-neutral-700 text-xs text-white font-mono focus:outline-none" />
                </div>
              </div>
              <div class="flex items-center justify-between">
                <label class="flex items-center gap-1.5 text-xs text-neutral-400">
                  <input type="checkbox" v-model="modelForm.supportsThinking" class="accent-white" /> Supports Thinking
                </label>
                <div class="flex items-center gap-1">
                  <button @click="saveNewModel(name)" :disabled="!modelForm.id.trim()" class="p-1 text-neutral-500 hover:text-emerald-400 disabled:opacity-40 transition-colors" title="Add model">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
                  </button>
                  <button @click="addingModel = false" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
                  </button>
                </div>
              </div>
            </template>
            <template v-else>
              <button @click="startAddModel" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Add model">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" /></svg>
              </button>
            </template>
          </div>

          <!-- No models -->
          <div v-if="!getProviderModels(name).length && !addingModel" class="px-4 py-4 text-xs text-neutral-600 text-center">
            No models configured
          </div>
        </div>

        <!-- Model discovery panel -->
        <div v-if="discoveryProvider === name" class="border-t border-blue-800/40 bg-blue-950/20">
          <div class="px-4 py-3 flex items-center justify-between border-b border-neutral-800/50">
            <div class="flex items-center gap-2">
              <span class="text-xs font-medium text-blue-400">Discover Models</span>
              <span v-if="!discoveryLoading && discoveredModels.length" class="text-[10px] text-neutral-500">
                {{ discoveredModels.length }} available
              </span>
            </div>
            <button @click="closeDiscovery" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Close">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>

          <!-- Loading -->
          <div v-if="discoveryLoading" class="px-4 py-6 text-center">
            <span class="text-xs text-neutral-500 animate-pulse">Fetching models from {{ name }}...</span>
          </div>

          <!-- Error -->
          <div v-else-if="discoveryError" class="px-4 py-4 text-center">
            <span class="text-xs text-red-400">{{ discoveryError }}</span>
          </div>

          <!-- Results -->
          <template v-else-if="discoveredModels.length">
            <!-- Search + filters -->
            <div class="px-4 py-2 flex items-center gap-2 border-b border-neutral-800/50">
              <svg class="w-3.5 h-3.5 text-neutral-600 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
              <input v-model="discoverySearch" placeholder="Search models..."
                     class="flex-1 px-2 py-1 bg-transparent text-xs text-white placeholder-neutral-600 focus:outline-none" />
              <select v-model="discoveryFilterThinking"
                      class="bg-neutral-800 border border-neutral-700 text-[10px] text-neutral-400 px-1.5 py-0.5 focus:outline-none">
                <option value="all">Thinking: All</option>
                <option value="yes">Thinking: Yes</option>
                <option value="no">Thinking: No</option>
              </select>
              <select v-if="discoveryHasPricing" v-model="discoveryFilterCost"
                      class="bg-neutral-800 border border-neutral-700 text-[10px] text-neutral-400 px-1.5 py-0.5 focus:outline-none">
                <option value="all">Cost: All</option>
                <option value="free">Free</option>
                <option value="paid">Paid</option>
              </select>
              <select v-if="discoveryHasRankings" v-model="discoveryFilterPopular"
                      class="bg-neutral-800 border border-neutral-700 text-[10px] text-neutral-400 px-1.5 py-0.5 focus:outline-none">
                <option value="all">Rank: All</option>
                <option value="top10">Top 10</option>
                <option value="top25">Top 25</option>
                <option value="ranked">All Ranked</option>
              </select>
              <span class="text-[10px] text-neutral-600 shrink-0">{{ filteredDiscoveredModels.length }}</span>
              <button @click="selectAllDiscovered" class="text-[10px] text-neutral-500 hover:text-white transition-colors shrink-0">
                {{ discoverySelected.size === filteredDiscoveredModels.length ? 'None' : 'All' }}
              </button>
            </div>

            <!-- Model list -->
            <div class="max-h-72 overflow-y-auto divide-y divide-neutral-800/30">
              <div v-for="model in filteredDiscoveredModels" :key="model.id"
                   @click="toggleDiscoverySelect(model.id)"
                   :class="discoverySelected.has(model.id) ? 'bg-blue-900/20' : ''"
                   class="px-4 py-1.5 flex items-center gap-3 hover:bg-neutral-800/30 cursor-pointer transition-colors">
                <span class="shrink-0 w-3.5 h-3.5 border border-neutral-600 flex items-center justify-center text-[10px]"
                      :class="discoverySelected.has(model.id) ? 'bg-blue-500 border-blue-500 text-white' : ''">
                  <span v-if="discoverySelected.has(model.id)">&#10003;</span>
                </span>
                <span v-if="model.leaderboardRank" class="shrink-0 text-[10px] font-bold px-1.5 py-0.5 rounded"
                      :class="model.leaderboardRank <= 3 ? 'text-amber-400 bg-amber-400/10 border border-amber-400/30' : 'text-neutral-400 bg-neutral-800 border border-neutral-700'"
                      :title="`#${model.leaderboardRank} on provider leaderboard`">
                  #{{ model.leaderboardRank }}
                </span>
                <div class="flex-1 min-w-0">
                  <span class="text-xs text-white font-mono truncate block">{{ model.id }}</span>
                  <span v-if="model.name && model.name !== model.id" class="text-[10px] text-neutral-500">{{ model.name }}</span>
                </div>
                <div class="flex items-center gap-2 shrink-0">
                  <span v-if="model.isFree" class="text-[10px] text-green-400 border border-green-400/30 px-1">free</span>
                  <span v-else-if="model.promptPrice >= 0" class="text-[10px] text-neutral-600 font-mono" :title="`$${model.promptPrice.toFixed(2)}/M in, $${model.completionPrice.toFixed(2)}/M out`">
                    ${{ model.promptPrice < 1 ? model.promptPrice.toFixed(2) : model.promptPrice.toFixed(0) }}/M
                  </span>
                  <span v-if="model.supportsThinking && model.thinkingDetectedFromProvider"
                        class="text-[10px] text-blue-400 border border-blue-400/30 px-1" title="Thinking support confirmed by provider">thinking</span>
                  <span v-else-if="model.supportsThinking"
                        class="text-[10px] text-neutral-500 border border-neutral-700 px-1" title="Thinking support guessed from model name (not confirmed by provider)">thinking?</span>
                  <span v-if="model.contextWindow" class="text-[10px] text-neutral-600 font-mono">{{ (model.contextWindow / 1024).toFixed(0) }}K</span>
                </div>
              </div>
            </div>

            <!-- Add selected -->
            <div class="px-4 py-2.5 border-t border-neutral-800/50 flex items-center justify-between">
              <span class="text-[10px] text-neutral-500">{{ discoverySelected.size }} selected</span>
              <button @click="addDiscoveredModels" :disabled="discoverySelected.size === 0"
                      class="px-3 py-1 bg-blue-600 text-white text-xs font-medium hover:bg-blue-500 disabled:opacity-40 transition-colors">
                Add Selected
              </button>
            </div>
          </template>

          <!-- Empty results -->
          <div v-else-if="!discoveryLoading" class="px-4 py-6 text-center">
            <span class="text-xs text-neutral-500">All available models are already configured</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Search Providers -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Search Providers</h2>
      <p class="text-xs text-neutral-600">Configure API keys for web search tools used by agents.</p>
      <div v-for="[id, keyMap] in providerEntries.searchEntries" :key="id"
           class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">{{ SEARCH_PROVIDERS[id].label }}</span>
          <span v-if="[...keyMap.values()].find(e => e.key.endsWith('apiKey') && e.value && e.value !== '(empty)')"
                class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">configured</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div v-for="entry in [...keyMap.values()]" :key="entry.key" class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">{{ entry.label }}</span>
            <template v-if="editingKey === entry.key">
              <input v-model="editValue"
                     :type="isSensitive(entry.key) ? 'password' : 'text'"
                     :placeholder="entry.placeholder"
                     class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
              <button @click="updateEntry(entry.key)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
              </button>
              <button @click="editingKey = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ entry.value || '(not set)' }}</span>
              <button @click="startEdit(entry)" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Agent Settings -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Agent</h2>
      <p class="text-xs text-neutral-600">Configure agent behavior limits.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div class="divide-y divide-neutral-800/50">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">maxToolRounds</span>
            <template v-if="editingAgentField === 'maxToolRounds'">
              <input v-model="agentFieldEdit" type="number" min="1" max="50"
                     class="w-24 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none" />
              <button @click="saveAgentField('agent.maxToolRounds', agentFieldEdit)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
              </button>
              <button @click="editingAgentField = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono">{{ agentMaxToolRounds }} rounds</span>
              <button @click="editingAgentField = 'maxToolRounds'; agentFieldEdit = agentMaxToolRounds" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Browser (Playwright) -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Browser (Playwright)</h2>
      <p class="text-xs text-neutral-600">Headless browser automation for JS-heavy pages. Requires the Playwright driver bundle.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-white">Enabled</span>
            <span v-if="playwrightEnabled" class="text-[10px] text-green-400 border border-green-400/30 px-1">active</span>
            <span v-else class="text-[10px] text-neutral-500 border border-neutral-700 px-1">disabled</span>
          </div>
          <button @click="togglePlaywrightEnabled"
                  :class="playwrightEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                  class="relative w-9 h-5 rounded-full transition-colors">
            <span :class="playwrightEnabled ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform" />
          </button>
        </div>
        <div class="px-4 py-2.5 flex items-center justify-between">
          <div>
            <span class="text-xs font-mono text-neutral-500">headless</span>
            <p v-if="!playwrightHeadless" class="text-[10px] text-amber-400 mt-0.5">Browser window will be visible on the host</p>
          </div>
          <button @click="togglePlaywrightHeadless"
                  :class="playwrightHeadless ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                  class="relative w-9 h-5 rounded-full transition-colors">
            <span :class="playwrightHeadless ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform" />
          </button>
        </div>
      </div>
    </div>

    <!-- Shell Execution -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Shell Execution</h2>
      <p class="text-xs text-neutral-600">Allow agents to execute shell commands on the host.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-white">Enabled</span>
            <span v-if="shellEnabled" class="text-[10px] text-green-400 border border-green-400/30 px-1">active</span>
            <span v-else class="text-[10px] text-neutral-500 border border-neutral-700 px-1">disabled</span>
          </div>
          <button @click="toggleShellEnabled"
                  :class="shellEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                  class="relative w-9 h-5 rounded-full transition-colors">
            <span :class="shellEnabled ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform" />
          </button>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <!-- Allowlist -->
          <div class="px-4 py-2.5 flex items-start gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0 pt-0.5">allowlist</span>
            <template v-if="editingShellField === 'allowlist'">
              <textarea v-model="shellAllowlistEdit" rows="3"
                        class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none resize-none" />
              <div class="flex flex-col gap-1">
                <button @click="saveShellField('shell.allowlist', shellAllowlistEdit)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
                </button>
                <button @click="editingShellField = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
                </button>
              </div>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ shellConfig.allowlist || '(not set)' }}</span>
              <button @click="startShellEdit('allowlist', shellConfig.allowlist)" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
              </button>
            </template>
          </div>
          <!-- Default timeout -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">defaultTimeoutSeconds</span>
            <template v-if="editingShellField === 'timeout'">
              <input v-model="shellTimeoutEdit" type="number" min="1" max="300"
                     class="w-24 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none" />
              <button @click="saveShellField('shell.defaultTimeoutSeconds', shellTimeoutEdit)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
              </button>
              <button @click="editingShellField = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono">{{ shellConfig.defaultTimeout }}s</span>
              <button @click="startShellEdit('timeout', shellConfig.defaultTimeout)" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit">
                <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Config entries -->
    <div class="bg-neutral-900 border border-neutral-800">
      <div class="px-4 py-3 border-b border-neutral-800">
        <h2 class="text-sm font-medium text-neutral-300">Configuration</h2>
      </div>
      <div class="divide-y divide-neutral-800/50">
        <div v-for="entry in providerEntries.other" :key="entry.key"
             class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-neutral-400 w-64 shrink-0 truncate">{{ entry.key }}</span>
          <template v-if="editingKey === entry.key">
            <input v-model="editValue"
                   :type="isSensitive(entry.key) ? 'password' : 'text'"
                   class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
            <button @click="updateEntry(entry.key)" class="p-1 text-neutral-500 hover:text-emerald-400 transition-colors" title="Save">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" /></svg>
            </button>
            <button @click="editingKey = null" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Cancel">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ entry.value }}</span>
            <button @click="startEdit(entry)" class="p-1 text-neutral-500 hover:text-white transition-colors" title="Edit">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
            </button>
            <button @click="deleteEntry(entry.key)" class="p-1 text-neutral-600 hover:text-red-400 transition-colors" title="Delete">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
            </button>
          </template>
        </div>
      </div>
      <div v-if="!configData?.entries?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No configuration entries
      </div>
    </div>

    <!-- Add new -->
    <div class="mt-4 bg-neutral-900 border border-neutral-800 p-4">
      <h3 class="text-xs font-medium text-neutral-400 mb-3">Add Entry</h3>
      <div class="flex gap-2">
        <input v-model="newKey" placeholder="key" class="flex-1 px-2 py-1.5 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none" />
        <input v-model="newValue" placeholder="value" class="flex-1 px-2 py-1.5 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
        <button @click="saveNew" :disabled="saving || !newKey.trim()"
                class="p-1.5 text-neutral-500 hover:text-emerald-400 disabled:opacity-40 transition-colors" title="Add entry">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" /></svg>
        </button>
      </div>
    </div>
  </div>
</template>
