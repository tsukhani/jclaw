<script setup lang="ts">
import type {
  Agent,
  ConfigEntry,
  ConfigResponse,
  DiscoveredModel,
  DiscoverModelsResponse,
  ProviderModelDef,
} from '~/types/api'

const { data: configData, refresh } = await useFetch<ConfigResponse>('/api/config')
const saving = ref(false)
const editingKey = ref<string | null>(null)
const editValue = ref('')

async function updateEntry(key: string) {
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key, value: editValue.value },
    })
    editingKey.value = null
    refresh()
  }
  catch (e) {
    console.error('Failed to update config:', e)
  }
  finally {
    saving.value = false
  }
}

function startEdit(entry: ConfigEntry) {
  editingKey.value = entry.key
  editValue.value = entry.value
}

function isSensitive(key: string) {
  const lower = key.toLowerCase()
  return ['key', 'secret', 'password', 'token'].some(s => lower.includes(s))
}

// Top-level Config DB prefixes claimed by a UI domain. Any row whose key starts
// with one of these is owned somewhere in the app and should not surface in the
// Unmanaged diagnostic list — regardless of which page actually manages it.
// Keeps Settings free of exact-key knowledge about other pages' config.
const MANAGED_PREFIXES = [
  'provider.', // LLM providers — Settings
  'search.', // Search providers — Settings
  'scanner.', // Malware scanners — Settings
  'chat.', // Chat settings — Settings
  'shell.', // Shell execution defaults + enabled toggle — Settings
  'playwright.', // Playwright browser tool — Settings
  'skillsPromotion.', // Skills promotion sanitization — Settings
  'agent.', // Per-agent config (shell privileges, queue mode, etc.) — Agents page
  'ollama.', // Ollama provider-specific settings — Settings
]

function isManagedKey(key: string): boolean {
  return MANAGED_PREFIXES.some(p => key.startsWith(p))
}

// Chat config
const chatMaxToolRounds = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'chat.maxToolRounds')?.value ?? '10'
})

const chatMaxContextMessages = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'chat.maxContextMessages')?.value ?? '50'
})

const editingChatField = ref<string | null>(null)
const chatFieldEdit = ref('')

async function saveChatField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingChatField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}

// Skills Promotion config
const { data: agentsList } = await useFetch<Agent[]>('/api/agents')
const mainAgent = computed(() => agentsList.value?.find(a => a.name === 'main') ?? null)

const spProviderRaw = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.provider')?.value ?? ''
})
const spModelRaw = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.model')?.value ?? ''
})

// Effective provider/model — resolve defaults from main agent
const spEffectiveProvider = computed(() => spProviderRaw.value || mainAgent.value?.modelProvider || '')
const spEffectiveModel = computed(() => spModelRaw.value || mainAgent.value?.modelId || '')

const spTimeout = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.timeoutSeconds')?.value ?? '300'
})
const spBatchKb = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'skillsPromotion.batchSizeKb')?.value ?? '100'
})

// Ollama-specific settings (rendered inside the provider section when provider name contains "ollama")
const ollamaKeepAlive = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'ollama.keepAlive')?.value ?? '30m'
})

const editingSPField = ref<string | null>(null)
const spFieldEdit = ref('')

const availableProviderNames = computed(() => {
  return [...(providerEntries.value?.providers?.keys() ?? [])]
})

const spAvailableModels = computed(() => {
  // When editing provider, use the edit value; otherwise use the effective provider
  const name = editingSPField.value === 'provider' ? spFieldEdit.value : spEffectiveProvider.value
  if (!name) return []
  return getProviderModels(name)
})

// Whether an explicit (non-default) provider is selected
const spHasExplicitProvider = computed(() => !!spProviderRaw.value)

async function saveSPField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    // When provider changes, also clear the saved model — the old model likely
    // belongs to the previous provider and would be invalid for the new one.
    if (configKey === 'skillsPromotion.provider') {
      await $fetch('/api/config', { method: 'POST', body: { key: 'skillsPromotion.model', value: '' } })
    }
    editingSPField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}

// Playwright config
const playwrightEnabled = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'playwright.enabled')?.value === 'true'
})

const playwrightHeadless = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'playwright.headless')?.value !== 'false'
})

async function togglePlaywrightEnabled() {
  const newVal = playwrightEnabled.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'playwright.enabled', value: newVal } })
  refresh()
}

async function togglePlaywrightHeadless() {
  const newVal = playwrightHeadless.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'playwright.headless', value: newVal } })
  refresh()
}

// Shell execution config
const SHELL_KEYS = ['shell.allowlist', 'shell.defaultTimeoutSeconds', 'shell.maxTimeoutSeconds', 'shell.maxOutputBytes'] as const

const shellEnabled = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'shell.enabled')?.value === 'true'
})

const shellConfig = computed(() => {
  const entries = configData.value?.entries ?? []
  const map = new Map<string, string>()
  for (const e of entries) {
    if ((SHELL_KEYS as readonly string[]).includes(e.key)) {
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
  }
  finally {
    saving.value = false
  }
}

async function toggleShellEnabled() {
  const newVal = shellEnabled.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'shell.enabled', value: newVal } })
  refresh()
}

// --- Search providers ---
// Display metadata only. All runtime state (enabled, apiKey, baseUrl) lives in the
// Config DB under `search.{id}.*`, seeded by DefaultConfigJob. A provider is active
// only when both enabled is on and its API key is configured, matching the
// isEnabled() contract in WebSearchTool.SearchProvider.
const SEARCH_PROVIDERS: Record<string, {
  label: string
  description: string
  signupUrl: string
  signupLabel: string
  apiKeyPlaceholder: string
}> = {
  exa: {
    label: 'Exa',
    description: 'Neural search engine optimized for research — ranks by semantic similarity and returns highlighted passages rather than blue-link snippets. Strong on recent technical content.',
    signupUrl: 'https://exa.ai/',
    signupLabel: 'exa.ai',
    apiKeyPlaceholder: 'Your Exa API key from exa.ai',
  },
  brave: {
    label: 'Brave Search',
    description: 'Independent web index (not a Bing/Google reseller) with a generous free tier. Good general-purpose fallback when Exa misses broad web content.',
    signupUrl: 'https://brave.com/search/api/',
    signupLabel: 'brave.com/search/api',
    apiKeyPlaceholder: 'Your Brave Search API key from brave.com/search/api',
  },
  tavily: {
    label: 'Tavily',
    description: 'LLM-optimized search API that returns cleaned content snippets. Designed for agent workflows; handles rate limits and result normalization server-side.',
    signupUrl: 'https://tavily.com/',
    signupLabel: 'tavily.com',
    apiKeyPlaceholder: 'Your Tavily API key from tavily.com',
  },
  perplexity: {
    label: 'Perplexity',
    description: 'Perplexity\'s own web index via the dedicated /search endpoint. Flat per-request pricing (no token fees), rich snippets, and absolute date-range filters. Good fit when you want recent, citation-ready results without an LLM synthesis step.',
    signupUrl: 'https://www.perplexity.ai/settings/api',
    signupLabel: 'perplexity.ai/settings/api',
    apiKeyPlaceholder: 'Your Perplexity API key from perplexity.ai',
  },
  ollama: {
    label: 'Ollama',
    description: 'Hosted web search via ollama.com. Uses the same account as Ollama Cloud LLMs — one key covers both. Free tier available; returns title/URL/content for each result.',
    signupUrl: 'https://ollama.com/settings/keys',
    signupLabel: 'ollama.com/settings/keys',
    apiKeyPlaceholder: 'Your Ollama API key from ollama.com',
  },
  felo: {
    label: 'Felo',
    description: 'AI-powered search API that returns an LLM-generated summary alongside source links. Simple query interface — good when you want a synthesized answer plus citations in one call.',
    signupUrl: 'https://openapi.felo.ai/',
    signupLabel: 'openapi.felo.ai',
    apiKeyPlaceholder: 'Your Felo API key from openapi.felo.ai',
  },
}

function searchApiKey(providerId: string): string {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === `search.${providerId}.apiKey`)?.value ?? ''
}

function searchBaseUrl(providerId: string): string {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === `search.${providerId}.baseUrl`)?.value ?? ''
}

function searchApiKeyEntry(providerId: string) {
  const def = SEARCH_PROVIDERS[providerId]!
  const key = `search.${providerId}.apiKey`
  const entries = configData.value?.entries ?? []
  const existing = entries.find(e => e.key === key)
  return existing
    ? { ...existing, label: 'apiKey', placeholder: def.apiKeyPlaceholder }
    : { key, value: '', label: 'apiKey', placeholder: def.apiKeyPlaceholder }
}

function searchBaseUrlEntry(providerId: string) {
  const key = `search.${providerId}.baseUrl`
  const entries = configData.value?.entries ?? []
  const existing = entries.find(e => e.key === key)
  return existing
    ? { ...existing, label: 'baseUrl', placeholder: '' }
    : { key, value: '', label: 'baseUrl', placeholder: '' }
}

function searchEnabled(providerId: string): boolean {
  const entries = configData.value?.entries ?? []
  // Default to true when the key is absent — matches WebSearchTool default.
  const entry = entries.find(e => e.key === `search.${providerId}.enabled`)
  return entry ? entry.value === 'true' : true
}

function searchActive(providerId: string): boolean {
  // A provider is actually usable only when enabled AND an API key is present.
  // Matches the isEnabled + apiKey check in WebSearchTool.execute().
  const key = searchApiKey(providerId)
  const hasKey = !!key && key !== '(empty)' && !key.startsWith('****')
  const maskedKeySet = !!key && (key.startsWith('****') || /\*\*\*\*/.test(key))
  return searchEnabled(providerId) && (hasKey || maskedKeySet)
}

async function toggleSearchEnabled(providerId: string) {
  const next = searchEnabled(providerId) ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: `search.${providerId}.enabled`, value: next } })
  refresh()
}

// Perplexity-only: server-side recency filter for /search. Valid values are
// hour|day|week|month|year, or "none" to disable. Defaults to "month" to match
// the DefaultConfigJob seed. Other providers don't expose a comparable knob,
// so this UI row is gated to id === 'perplexity'.
function searchRecencyFilter(providerId: string): string {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === `search.${providerId}.recencyFilter`)?.value ?? 'month'
}

async function updateSearchRecencyFilter(providerId: string, value: string) {
  await $fetch('/api/config', { method: 'POST', body: { key: `search.${providerId}.recencyFilter`, value } })
  refresh()
}

function searchPriority(providerId: string): number {
  const entries = configData.value?.entries ?? []
  const entry = entries.find(e => e.key === `search.${providerId}.priority`)
  return entry ? parseInt(entry.value, 10) : 99
}

/** Provider IDs sorted by their configured priority. */
const sortedSearchProviderIds = computed(() => {
  return Object.keys(SEARCH_PROVIDERS).sort((a, b) => searchPriority(a) - searchPriority(b))
})

// --- Search provider drag-and-drop reordering ---
const dragSearchProvider = ref<string | null>(null)
const dropSearchTarget = ref<string | null>(null)

function onSearchDragStart(ev: DragEvent, id: string) {
  dragSearchProvider.value = id
  if (ev.dataTransfer) {
    ev.dataTransfer.effectAllowed = 'move'
    ev.dataTransfer.setData('text/plain', id)
  }
}

function onSearchDragOver(ev: DragEvent, id: string) {
  ev.preventDefault()
  if (ev.dataTransfer) ev.dataTransfer.dropEffect = 'move'
  dropSearchTarget.value = id
}

function onSearchDragLeave() {
  dropSearchTarget.value = null
}

async function onSearchDrop(ev: DragEvent, targetId: string) {
  ev.preventDefault()
  dropSearchTarget.value = null
  const sourceId = dragSearchProvider.value
  dragSearchProvider.value = null
  if (!sourceId || sourceId === targetId) return

  // Reorder: move source to target's position in the sorted list
  const ids = [...sortedSearchProviderIds.value]
  const fromIdx = ids.indexOf(sourceId)
  const toIdx = ids.indexOf(targetId)
  if (fromIdx < 0 || toIdx < 0) return
  ids.splice(fromIdx, 1)
  ids.splice(toIdx, 0, sourceId)

  // Persist new priorities
  await Promise.all(ids.map((id, i) =>
    $fetch('/api/config', { method: 'POST', body: { key: `search.${id}.priority`, value: String(i) } }),
  ))
  refresh()
}

function onSearchDragEnd() {
  dragSearchProvider.value = null
  dropSearchTarget.value = null
}

// --- Malware scanners ---
// Each scanner hashes every binary in a skill install and asks an external reputation
// service whether that hash is in its catalog. Runs independently; if multiple scanners
// are enabled, a file is rejected when any of them flag it (OR composition).
const SCANNER_PROVIDERS: Record<string, {
  label: string
  description: string
  signupUrl: string
  signupLabel: string
  enabledKey: string
  apiKey: { key: string, label: string, placeholder: string }
}> = {
  malwarebazaar: {
    label: 'MalwareBazaar (abuse.ch)',
    description: 'Community malware repository curated by abuse.ch. Returns family labels (e.g. "Mirai", "Emotet") for hashes that have been submitted by researchers. Research-grade coverage — sometimes catches fresh-campaign samples before commercial AV vendors. Free under fair use.',
    signupUrl: 'https://auth.abuse.ch/',
    signupLabel: 'auth.abuse.ch',
    enabledKey: 'scanner.malwarebazaar.enabled',
    apiKey: {
      key: 'scanner.malwarebazaar.authKey',
      label: 'authKey',
      placeholder: 'Auth-Key from https://auth.abuse.ch/',
    },
  },
  metadefender: {
    label: 'MetaDefender Cloud (OPSWAT)',
    description: 'Multi-engine aggregator combining verdicts from dozens of commercial AV engines (ESET, Kaspersky, Sophos, Bitdefender, and more). Broader coverage than any single catalog. Free tier: 4,000 requests/day with no per-minute throttling.',
    signupUrl: 'https://metadefender.opswat.com/',
    signupLabel: 'metadefender.opswat.com',
    enabledKey: 'scanner.metadefender.enabled',
    apiKey: {
      key: 'scanner.metadefender.apiKey',
      label: 'apiKey',
      placeholder: 'API key from https://metadefender.opswat.com/',
    },
  },
  virustotal: {
    label: 'VirusTotal',
    description: 'Hash reputation across ~70 AV engines (Kaspersky, ESET, Microsoft, Sophos, Bitdefender, and more). Google-owned, the de-facto industry reference for multi-engine file intelligence. Free public API: 500 requests/day at 4 req/minute.',
    signupUrl: 'https://www.virustotal.com/gui/join-us',
    signupLabel: 'virustotal.com',
    enabledKey: 'scanner.virustotal.enabled',
    apiKey: {
      key: 'scanner.virustotal.apiKey',
      label: 'apiKey',
      placeholder: 'API key from https://www.virustotal.com/gui/my-apikey',
    },
  },
}

function scannerApiKeyValue(scannerId: string): string {
  const def = SCANNER_PROVIDERS[scannerId]!
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === def.apiKey.key)?.value ?? ''
}

function scannerApiKeyEntry(scannerId: string) {
  const def = SCANNER_PROVIDERS[scannerId]!
  const entries = configData.value?.entries ?? []
  const existing = entries.find(e => e.key === def.apiKey.key)
  return existing
    ? { ...existing, label: def.apiKey.label, placeholder: def.apiKey.placeholder }
    : { key: def.apiKey.key, value: '', label: def.apiKey.label, placeholder: def.apiKey.placeholder }
}

function scannerEnabled(scannerId: string): boolean {
  const def = SCANNER_PROVIDERS[scannerId]!
  const entries = configData.value?.entries ?? []
  // Default to true when the key is absent — matches the Play config default.
  const entry = entries.find(e => e.key === def.enabledKey)
  return entry ? entry.value === 'true' : true
}

function scannerActive(scannerId: string): boolean {
  // A scanner is actually running only when enabled AND an API key is present.
  // Matches the isEnabled() logic in the Java scanners: blank key → inert.
  const key = scannerApiKeyValue(scannerId)
  const hasKey = !!key && key !== '(empty)' && !key.startsWith('****')
  // Masked values from the backend still indicate a key is set.
  const maskedKeySet = !!key && (key.startsWith('****') || /\*\*\*\*/.test(key))
  return scannerEnabled(scannerId) && (hasKey || maskedKeySet)
}

async function toggleScannerEnabled(scannerId: string) {
  const def = SCANNER_PROVIDERS[scannerId]!
  const next = scannerEnabled(scannerId) ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: def.enabledKey, value: next } })
  refresh()
}

// --- Model management ---
const expandedModelsProvider = ref<string | null>(null)
const editingModelIdx = ref<number | null>(null)
const modelForm = ref({ id: '', name: '', contextWindow: 131072, maxTokens: 8192, supportsThinking: false, supportsVision: false, supportsAudio: false, promptPrice: -1, completionPrice: -1, cachedReadPrice: -1, cacheWritePrice: -1 })
const addingModel = ref(false)

function getProviderModels(providerName: string): ProviderModelDef[] {
  const entries = configData.value?.entries ?? []
  const modelsEntry = entries.find(e => e.key === `provider.${providerName}.models`)
  if (!modelsEntry?.value) return []
  try {
    return JSON.parse(modelsEntry.value) as ProviderModelDef[]
  }
  catch { return [] }
}

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

async function fetchRanksForProvider(providerName: string) {
  if (configuredModelRanks.value.has(providerName)) return
  try {
    const res = await $fetch<DiscoverModelsResponse>(`/api/providers/${providerName}/discover-models`, { method: 'POST' })
    const rankMap = new Map<string, number>()
    const discoveredMap = new Map<string, DiscoveredModel>()
    for (const m of (res.models || [])) {
      if (m.leaderboardRank) rankMap.set(m.id, m.leaderboardRank)
      discoveredMap.set(m.id, m)
    }
    configuredModelRanks.value = new Map(configuredModelRanks.value).set(providerName, rankMap)

    // Backfill pricing on configured models that are missing it
    const configured = getProviderModels(providerName)
    let updated = false
    for (const model of configured) {
      const discovered = discoveredMap.get(model.id)
      if (discovered) {
        if (model.promptPrice == null && (discovered.promptPrice ?? -1) >= 0) {
          model.promptPrice = discovered.promptPrice
          updated = true
        }
        if (model.completionPrice == null && (discovered.completionPrice ?? -1) >= 0) {
          model.completionPrice = discovered.completionPrice
          updated = true
        }
        if (model.cachedReadPrice == null && (discovered.cachedReadPrice ?? -1) >= 0) {
          model.cachedReadPrice = discovered.cachedReadPrice
          updated = true
        }
        if (model.cacheWritePrice == null && (discovered.cacheWritePrice ?? -1) >= 0) {
          model.cacheWritePrice = discovered.cacheWritePrice
          updated = true
        }
      }
    }
    if (updated) await saveModels(providerName, configured)
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
    contextWindow: m.contextWindow || 131072,
    maxTokens: m.maxTokens || 8192,
    supportsThinking: m.supportsThinking || false,
    supportsVision: m.supportsVision || false,
    supportsAudio: m.supportsAudio || false,
    promptPrice: m.promptPrice ?? -1,
    completionPrice: m.completionPrice ?? -1,
    cachedReadPrice: m.cachedReadPrice ?? -1,
    cacheWritePrice: m.cacheWritePrice ?? -1,
  }
  editingModelIdx.value = idx
  addingModel.value = false
}

function startAddModel() {
  modelForm.value = { id: '', name: '', contextWindow: 131072, maxTokens: 8192, supportsThinking: false, supportsVision: false, supportsAudio: false, promptPrice: -1, completionPrice: -1, cachedReadPrice: -1, cacheWritePrice: -1 }
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
    supportsVision: f.supportsVision,
    supportsAudio: f.supportsAudio,
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

// Whether pricing data is available for this provider's models
const discoveryHasPricing = computed(() =>
  discoveredModels.value.some(m => (m.promptPrice ?? -1) >= 0),
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
      supportsVision: m.visionDetectedFromProvider ? m.supportsVision : false,
      supportsAudio: m.audioDetectedFromProvider ? m.supportsAudio : false,
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

// Group config entries by LLM provider; everything else (non-managed) falls through
// to the generic Configuration list.
const providerEntries = computed(() => {
  const entries = configData.value?.entries ?? []
  const providers = new Map<string, ConfigEntry[]>()
  const other: ConfigEntry[] = []

  for (const e of entries) {
    if (e.key.startsWith('provider.')) {
      const parts = e.key.split('.')
      const name = parts[1]!
      if (!providers.has(name)) providers.set(name, [])
      providers.get(name)!.push(e)
    }
    else if (!isManagedKey(e.key)) {
      other.push(e)
    }
  }
  return { providers, other }
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Settings
    </h1>

    <!-- Provider sections -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        LLM Providers
      </h2>
      <p class="text-xs text-fg-muted">
        Enter an API key for at least one provider to enable chat. Base URLs and models are pre-configured.
      </p>
      <div
        v-for="[name, entries] in providerEntries.providers"
        :key="name"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 border-b border-border">
          <span class="text-sm font-medium text-fg-strong">{{ name }}</span>
          <span
            v-if="entries.find((e: any) => e.key.endsWith('.apiKey') && e.value && !e.value.startsWith('****') && e.value !== '****')"
            class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1"
          >configured</span>
        </div>
        <div class="divide-y divide-border">
          <!-- Non-models entries (baseUrl, apiKey) -->
          <div
            v-for="entry in entries.filter((e: any) => !e.key.endsWith('.models'))"
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
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ entry.value || '(empty)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(entry)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
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
                <svg
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><circle
                  cx="12"
                  cy="12"
                  r="10"
                  stroke-width="2"
                /><path
                  stroke-linecap="round"
                  stroke-width="2"
                  d="M12 16v-4m0-4h.01"
                /></svg>
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
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ ollamaKeepAlive }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingKey = 'ollama.keepAlive'; editValue = ollamaKeepAlive"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
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
              <svg
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"
              /></svg>
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              :title="expandedModelsProvider === name ? 'Close models' : 'Manage models'"
              @click="toggleModelsPanel(name)"
            >
              <svg
                v-if="expandedModelsProvider === name"
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M5 15l7-7 7 7"
              /></svg>
              <svg
                v-else
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
              /><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
              /></svg>
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
                  </div>
                  <div class="flex items-center gap-1">
                    <button
                      class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                      title="Save"
                      @click="saveEditedModel(name)"
                    >
                      <svg
                        class="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M5 13l4 4L19 7"
                      /></svg>
                    </button>
                    <button
                      class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                      title="Cancel"
                      @click="editingModelIdx = null"
                    >
                      <svg
                        class="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M6 18L18 6M6 6l12 12"
                      /></svg>
                    </button>
                    <button
                      class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                      title="Delete model"
                      @click="deleteModel(name, idx)"
                    >
                      <svg
                        class="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                      /></svg>
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
                      :class="getModelRank(name, model.id)! <= 3 ? 'text-amber-400 bg-amber-400/10 border border-amber-400/30' : 'text-fg-muted bg-muted border border-input'"
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
                        v-if="model.supportsThinking"
                        class="ml-2 text-[10px] text-blue-400 border border-blue-400/30 px-1"
                      >thinking</span>
                      <span
                        v-if="model.supportsVision"
                        class="ml-2 text-[10px] text-amber-400 border border-amber-400/30 px-1"
                      >vision</span>
                      <span
                        v-if="model.supportsAudio"
                        class="ml-2 text-[10px] text-violet-400 border border-violet-400/30 px-1"
                      >audio</span>
                    </div>
                  </div>
                  <div class="flex items-center gap-2">
                    <span
                      v-if="(model.promptPrice ?? 0) > 0"
                      class="text-xs text-amber-500/70 font-mono"
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
                      <svg
                        class="w-3.5 h-3.5"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                      /></svg>
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
                </div>
                <div class="flex items-center gap-1">
                  <button
                    :disabled="!modelForm.id.trim()"
                    class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 disabled:opacity-40 transition-colors"
                    title="Add model"
                    @click="saveNewModel(name)"
                  >
                    <svg
                      class="w-3.5 h-3.5"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    ><path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M5 13l4 4L19 7"
                    /></svg>
                  </button>
                  <button
                    class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                    title="Cancel"
                    @click="addingModel = false"
                  >
                    <svg
                      class="w-3.5 h-3.5"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    ><path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="2"
                      d="M6 18L18 6M6 6l12 12"
                    /></svg>
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
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M12 4v16m8-8H4"
                /></svg>
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
            <div class="flex items-center gap-2">
              <span class="text-xs font-medium text-blue-700 dark:text-blue-400">Discover Models</span>
              <span
                v-if="!discoveryLoading && discoveredModels.length"
                class="text-[10px] text-fg-muted"
              >
                {{ discoveredModels.length }} available
              </span>
            </div>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Close"
              @click="closeDiscovery"
            >
              <svg
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M6 18L18 6M6 6l12 12"
              /></svg>
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
            <span class="text-xs text-red-400">{{ discoveryError }}</span>
          </div>

          <!-- Results -->
          <template v-else-if="discoveredModels.length">
            <!-- Search + filters -->
            <div class="px-4 py-2 flex items-center gap-2 border-b border-border">
              <svg
                class="w-3.5 h-3.5 text-fg-muted shrink-0"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
              /></svg>
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
                v-if="discoveryHasPricing"
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
                  :class="model.leaderboardRank <= 3 ? 'text-amber-400 bg-amber-400/10 border border-amber-400/30' : 'text-fg-muted bg-muted border border-input'"
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
                    class="text-[10px] text-green-400 border border-green-400/30 px-1"
                  >free</span>
                  <span
                    v-else-if="(model.promptPrice ?? -1) >= 0"
                    class="text-[10px] text-fg-muted font-mono"
                    :title="`$${(model.promptPrice ?? 0).toFixed(2)}/M in, $${(model.completionPrice ?? 0).toFixed(2)}/M out`"
                  >
                    ${{ (model.promptPrice ?? 0) < 1 ? (model.promptPrice ?? 0).toFixed(2) : (model.promptPrice ?? 0).toFixed(0) }}/M
                  </span>
                  <span
                    v-if="model.supportsThinking && model.thinkingDetectedFromProvider"
                    class="text-[10px] text-blue-400 border border-blue-400/30 px-1"
                    title="Thinking support confirmed by provider"
                  >thinking</span>
                  <span
                    v-else-if="model.supportsThinking"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Thinking support guessed from model name (not confirmed by provider)"
                  >thinking?</span>
                  <span
                    v-if="model.supportsVision && model.visionDetectedFromProvider"
                    class="text-[10px] text-amber-400 border border-amber-400/30 px-1"
                    title="Vision support confirmed by provider"
                  >vision</span>
                  <span
                    v-else-if="model.supportsVision"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Vision support guessed from model name (not confirmed by provider)"
                  >vision?</span>
                  <span
                    v-if="model.supportsAudio && model.audioDetectedFromProvider"
                    class="text-[10px] text-violet-400 border border-violet-400/30 px-1"
                    title="Audio support confirmed by provider"
                  >audio</span>
                  <span
                    v-else-if="model.supportsAudio"
                    class="text-[10px] text-fg-muted border border-input px-1"
                    title="Audio support guessed from model name (not confirmed by provider)"
                  >audio?</span>
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
    </div>

    <!-- Search Providers -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Search Providers
      </h2>
      <p class="text-xs text-fg-muted">
        Web search engines that agents can call via the <span class="text-fg-muted">web_search</span> tool.
        Providers are tried in order — drag to reorder. If a provider fails, the next one is tried automatically.
        A provider is only active when both <span class="text-fg-muted">enabled</span> is on and its API key is configured.
      </p>
      <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- drag-drop reorder: HTML5 drag events have no keyboard equivalent; rule does not differentiate them from click -->
      <div
        v-for="id in sortedSearchProviderIds"
        :key="id"
        draggable="true"
        :class="[
          'bg-surface-elevated border transition-colors',
          dropSearchTarget === id && dragSearchProvider !== id
            ? 'border-emerald-600 dark:border-emerald-500/50'
            : dragSearchProvider === id
              ? 'border-input opacity-50'
              : 'border-border',
        ]"
        @dragstart="onSearchDragStart($event, id)"
        @dragover="onSearchDragOver($event, id)"
        @dragleave="onSearchDragLeave()"
        @drop="onSearchDrop($event, id)"
        @dragend="onSearchDragEnd()"
      >
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span
              class="cursor-grab active:cursor-grabbing text-fg-muted hover:text-fg-muted select-none"
              title="Drag to reorder priority"
            >⠿</span>
            <span class="text-sm font-medium text-fg-strong">{{ SEARCH_PROVIDERS[id]!.label }}</span>
            <span
              v-if="searchActive(id)"
              class="text-[10px] text-green-400 border border-green-400/30 px-1"
            >active</span>
            <span
              v-else-if="searchEnabled(id)"
              class="text-[10px] text-amber-400 border border-amber-400/30 px-1"
            >needs API key</span>
            <span
              v-else
              class="text-[10px] text-fg-muted border border-input px-1"
            >disabled</span>
          </div>
          <button
            :class="searchEnabled(id) ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
            class="relative w-9 h-5 rounded-full transition-colors"
            :title="searchEnabled(id) ? 'Disable provider' : 'Enable provider'"
            @click="toggleSearchEnabled(id)"
          >
            <span
              :class="searchEnabled(id) ? 'translate-x-4' : 'translate-x-0.5'"
              class="block w-4 h-4 bg-white rounded-full transition-transform"
            />
          </button>
        </div>
        <div class="px-4 py-2.5 text-xs text-fg-muted leading-relaxed border-b border-border">
          {{ SEARCH_PROVIDERS[id]!.description }}
          <a
            :href="SEARCH_PROVIDERS[id]!.signupUrl"
            target="_blank"
            rel="noopener"
            class="text-fg-primary hover:text-fg-strong underline ml-1"
          >Get an API key → {{ SEARCH_PROVIDERS[id]!.signupLabel }}</a>
        </div>
        <div class="divide-y divide-border">
          <!-- apiKey -->
          <div class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">apiKey</span>
            <template v-if="editingKey === `search.${id}.apiKey`">
              <input
                v-model="editValue"
                type="password"
                :placeholder="SEARCH_PROVIDERS[id]!.apiKeyPlaceholder"
                aria-label="API key"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry(`search.${id}.apiKey`)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ searchApiKeyEntry(id).value || '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(searchApiKeyEntry(id))"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <!-- baseUrl -->
          <div class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">baseUrl</span>
            <template v-if="editingKey === `search.${id}.baseUrl`">
              <input
                v-model="editValue"
                type="text"
                aria-label="Base URL"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="updateEntry(`search.${id}.baseUrl`)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ searchBaseUrl(id) || '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(searchBaseUrlEntry(id))"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <!-- recencyFilter (Perplexity only) -->
          <div
            v-if="id === 'perplexity'"
            class="px-4 py-2 flex items-center gap-3"
          >
            <span
              class="text-xs font-mono text-fg-muted w-48 shrink-0"
              title="Server-side recency filter on Perplexity /search. Narrows results to content indexed within the selected window; 'none' disables filtering. Narrower windows prevent the LLM from echoing stale snippets."
            >recencyFilter</span>
            <select
              :value="searchRecencyFilter(id)"
              aria-label="Recency filter"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              @change="updateSearchRecencyFilter(id, ($event.target as HTMLSelectElement).value)"
            >
              <option value="hour">
                hour
              </option>
              <option value="day">
                day
              </option>
              <option value="week">
                week
              </option>
              <option value="month">
                month
              </option>
              <option value="year">
                year
              </option>
              <option value="none">
                none (disable filter)
              </option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <!-- Chat Settings -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Chat
      </h2>
      <p class="text-xs text-fg-muted">
        Configure chat behavior limits.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxToolRounds
              <span class="relative group/tip">
                <svg
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><circle
                  cx="12"
                  cy="12"
                  r="10"
                  stroke-width="2"
                /><path
                  stroke-linecap="round"
                  stroke-width="2"
                  d="M12 16v-4m0-4h.01"
                /></svg>
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-56 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Max tool calls the agent can make per turn. Once reached, it must give a final answer without calling more tools.
                </span>
              </span>
            </span>
            <template v-if="editingChatField === 'maxToolRounds'">
              <input
                v-model="chatFieldEdit"
                type="number"
                min="1"
                max="50"
                aria-label="Max tool rounds"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveChatField('chat.maxToolRounds', chatFieldEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingChatField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ chatMaxToolRounds }} rounds</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingChatField = 'maxToolRounds'; chatFieldEdit = chatMaxToolRounds"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxContextMessages
              <span class="relative group/tip">
                <svg
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><circle
                  cx="12"
                  cy="12"
                  r="10"
                  stroke-width="2"
                /><path
                  stroke-linecap="round"
                  stroke-width="2"
                  d="M12 16v-4m0-4h.01"
                /></svg>
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  How many recent messages are sent with each LLM request. Older messages are dropped when the limit is reached to stay within the context window.
                </span>
              </span>
            </span>
            <template v-if="editingChatField === 'maxContextMessages'">
              <input
                v-model="chatFieldEdit"
                type="number"
                min="1"
                max="500"
                aria-label="Max context messages"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveChatField('chat.maxContextMessages', chatFieldEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingChatField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ chatMaxContextMessages }} messages</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingChatField = 'maxContextMessages'; chatFieldEdit = chatMaxContextMessages"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Skills Promotion -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Skills Promotion
      </h2>
      <p class="text-xs text-fg-muted">
        LLM sanitization during skill promotion. Uses the main agent's model by default if not configured.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <!-- Provider -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">provider</span>
            <template v-if="editingSPField === 'provider'">
              <select
                v-model="spFieldEdit"
                aria-label="Skills promotion provider"
                class="w-48 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
                <option value="">
                  Default (main agent)
                </option>
                <option
                  v-for="name in availableProviderNames"
                  :key="name"
                  :value="name"
                >
                  {{ name }}
                </option>
              </select>
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSPField('skillsPromotion.provider', spFieldEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">
                {{ spProviderRaw ? spProviderRaw : spEffectiveProvider ? spEffectiveProvider + ' (from main agent)' : 'Not configured' }}
              </span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSPField = 'provider'; spFieldEdit = spProviderRaw"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <!-- Model -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">model</span>
            <template v-if="editingSPField === 'model'">
              <select
                v-if="spAvailableModels.length"
                v-model="spFieldEdit"
                aria-label="Skills promotion model"
                class="w-64 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
                <option
                  v-if="!spHasExplicitProvider"
                  value=""
                >
                  Default (main agent)
                </option>
                <option
                  v-for="m in spAvailableModels"
                  :key="m.id"
                  :value="m.id"
                >
                  {{ m.name || m.id }}
                </option>
              </select>
              <input
                v-else
                v-model="spFieldEdit"
                type="text"
                placeholder="model-id"
                aria-label="Skills promotion model id"
                class="w-64 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSPField('skillsPromotion.model', spFieldEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span
                class="flex-1 text-sm font-mono"
                :class="spModelRaw || !spHasExplicitProvider ? 'text-fg-primary' : 'text-amber-500'"
              >
                {{ spModelRaw ? spModelRaw : spHasExplicitProvider ? 'Select a model' : spEffectiveModel ? spEffectiveModel + ' (from main agent)' : 'Not configured' }}
              </span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSPField = 'model'; spFieldEdit = spModelRaw || (spHasExplicitProvider && spAvailableModels.length ? (spAvailableModels[0]?.id ?? '') : '')"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <!-- Timeout -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">timeoutSeconds</span>
            <template v-if="editingSPField === 'timeout'">
              <input
                v-model="spFieldEdit"
                type="number"
                min="30"
                max="900"
                aria-label="Skills promotion timeout seconds"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSPField('skillsPromotion.timeoutSeconds', spFieldEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ spTimeout }}s</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSPField = 'timeout'; spFieldEdit = spTimeout"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <!-- Batch Size KB -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">batchSizeKb</span>
            <template v-if="editingSPField === 'batchKb'">
              <input
                v-model="spFieldEdit"
                type="number"
                min="10"
                max="1000"
                aria-label="Batch size KB"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSPField('skillsPromotion.batchSizeKb', spFieldEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ spBatchKb }} KB</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSPField = 'batchKb'; spFieldEdit = spBatchKb"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Browser (Playwright) -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Browser (Playwright)
      </h2>
      <p class="text-xs text-fg-muted">
        Headless browser automation for JS-heavy pages. Requires the Playwright driver bundle.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">Enabled</span>
            <span
              v-if="playwrightEnabled"
              class="text-[10px] text-green-400 border border-green-400/30 px-1"
            >active</span>
            <span
              v-else
              class="text-[10px] text-fg-muted border border-input px-1"
            >disabled</span>
          </div>
          <button
            :class="playwrightEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
            class="relative w-9 h-5 rounded-full transition-colors"
            @click="togglePlaywrightEnabled"
          >
            <span
              :class="playwrightEnabled ? 'translate-x-4' : 'translate-x-0.5'"
              class="block w-4 h-4 bg-white rounded-full transition-transform"
            />
          </button>
        </div>
        <div class="px-4 py-2.5 flex items-center justify-between">
          <div>
            <span class="text-xs font-mono text-fg-muted">headless</span>
            <p
              v-if="!playwrightHeadless"
              class="text-[10px] text-amber-400 mt-0.5"
            >
              Browser window will be visible on the host
            </p>
          </div>
          <button
            :class="playwrightHeadless ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
            class="relative w-9 h-5 rounded-full transition-colors"
            @click="togglePlaywrightHeadless"
          >
            <span
              :class="playwrightHeadless ? 'translate-x-4' : 'translate-x-0.5'"
              class="block w-4 h-4 bg-white rounded-full transition-transform"
            />
          </button>
        </div>
      </div>
    </div>

    <!-- Shell Execution -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Shell Execution
      </h2>
      <p class="text-xs text-fg-muted">
        Allow agents to execute shell commands on the host.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">Enabled</span>
            <span
              v-if="shellEnabled"
              class="text-[10px] text-green-400 border border-green-400/30 px-1"
            >active</span>
            <span
              v-else
              class="text-[10px] text-fg-muted border border-input px-1"
            >disabled</span>
          </div>
          <button
            :class="shellEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
            class="relative w-9 h-5 rounded-full transition-colors"
            @click="toggleShellEnabled"
          >
            <span
              :class="shellEnabled ? 'translate-x-4' : 'translate-x-0.5'"
              class="block w-4 h-4 bg-white rounded-full transition-transform"
            />
          </button>
        </div>
        <div class="divide-y divide-border">
          <!-- Allowlist -->
          <div class="px-4 py-2.5 flex items-start gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 pt-0.5">allowlist</span>
            <template v-if="editingShellField === 'allowlist'">
              <textarea
                v-model="shellAllowlistEdit"
                rows="3"
                aria-label="Shell allowlist"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden resize-none"
              />
              <div class="flex flex-col gap-1">
                <button
                  class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                  title="Save"
                  @click="saveShellField('shell.allowlist', shellAllowlistEdit)"
                >
                  <svg
                    class="w-3.5 h-3.5"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  ><path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M5 13l4 4L19 7"
                  /></svg>
                </button>
                <button
                  class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                  title="Cancel"
                  @click="editingShellField = null"
                >
                  <svg
                    class="w-3.5 h-3.5"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  ><path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2"
                    d="M6 18L18 6M6 6l12 12"
                  /></svg>
                </button>
              </div>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ shellConfig.allowlist || '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startShellEdit('allowlist', shellConfig.allowlist)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
          <!-- Default timeout -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">defaultTimeoutSeconds</span>
            <template v-if="editingShellField === 'timeout'">
              <input
                v-model="shellTimeoutEdit"
                type="number"
                min="1"
                max="300"
                aria-label="Shell default timeout seconds"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveShellField('shell.defaultTimeoutSeconds', shellTimeoutEdit)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M5 13l4 4L19 7"
                /></svg>
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingShellField = null"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M6 18L18 6M6 6l12 12"
                /></svg>
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ shellConfig.defaultTimeout }}s</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startShellEdit('timeout', shellConfig.defaultTimeout)"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
                /></svg>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Malware Scanners -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Malware and Virus Scanners
      </h2>
      <p class="text-xs text-fg-muted">
        Hash-based reputation lookups that scan every binary inside a skill before it's installed.
        Each scanner hashes the file with SHA-256 and asks an external service whether that hash
        appears in its known-malware catalog — file bytes never leave the host. Multiple scanners
        run independently and compose under OR: a skill is rejected if any enabled scanner flags
        any binary. A scanner is only active when both <span class="text-fg-muted">enabled</span>
        is on and its API key is configured.
      </p>
      <div
        v-for="(def, id) in SCANNER_PROVIDERS"
        :key="id"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">{{ def.label }}</span>
            <span
              v-if="scannerActive(id)"
              class="text-[10px] text-green-400 border border-green-400/30 px-1"
            >active</span>
            <span
              v-else-if="scannerEnabled(id)"
              class="text-[10px] text-amber-400 border border-amber-400/30 px-1"
            >needs API key</span>
            <span
              v-else
              class="text-[10px] text-fg-muted border border-input px-1"
            >disabled</span>
          </div>
          <button
            :class="scannerEnabled(id) ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
            class="relative w-9 h-5 rounded-full transition-colors"
            :title="scannerEnabled(id) ? 'Disable scanner' : 'Enable scanner'"
            @click="toggleScannerEnabled(id)"
          >
            <span
              :class="scannerEnabled(id) ? 'translate-x-4' : 'translate-x-0.5'"
              class="block w-4 h-4 bg-white rounded-full transition-transform"
            />
          </button>
        </div>
        <div class="px-4 py-2.5 text-xs text-fg-muted leading-relaxed border-b border-border">
          {{ def.description }}
          <a
            :href="def.signupUrl"
            target="_blank"
            rel="noopener"
            class="text-fg-primary hover:text-fg-strong underline ml-1"
          >Get a free key → {{ def.signupLabel }}</a>
        </div>
        <div class="px-4 py-2 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">{{ def.apiKey.label }}</span>
          <template v-if="editingKey === def.apiKey.key">
            <input
              v-model="editValue"
              type="password"
              :placeholder="def.apiKey.placeholder"
              :aria-label="def.apiKey.label"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="updateEntry(def.apiKey.key)"
            >
              <svg
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M5 13l4 4L19 7"
              /></svg>
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingKey = null"
            >
              <svg
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M6 18L18 6M6 6l12 12"
              /></svg>
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ scannerApiKeyEntry(id).value || '(not set)' }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="startEdit(scannerApiKeyEntry(id))"
            >
              <svg
                class="w-3.5 h-3.5"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
              /></svg>
            </button>
          </template>
        </div>
      </div>
    </div>

    <!-- Unmanaged config entries (diagnostic) -->
    <!-- Only rendered when the Config DB contains keys not owned by any managed UI
         section above. Typically shows stale rows from a prior schema or mid-migration
         state — a signal that something needs cleanup, not a place to add new config. -->
    <div
      v-if="providerEntries.other.length"
      class="bg-surface-elevated border border-border"
    >
      <div class="px-4 py-3 border-b border-border">
        <h2 class="text-sm font-medium text-fg-primary">
          Unmanaged keys
        </h2>
        <p class="text-[11px] text-fg-muted mt-0.5">
          Config DB rows not owned by any section above — usually stale keys from a prior
          version. Safe to ignore; they're shown here so migrations don't hide data.
        </p>
      </div>
      <div class="divide-y divide-border">
        <div
          v-for="entry in providerEntries.other"
          :key="entry.key"
          class="px-4 py-2.5 flex items-center gap-3"
        >
          <span class="text-xs font-mono text-fg-muted w-64 shrink-0 truncate">{{ entry.key }}</span>
          <span class="flex-1 text-sm text-fg-muted font-mono truncate">{{ entry.value }}</span>
        </div>
      </div>
    </div>
  </div>
</template>
