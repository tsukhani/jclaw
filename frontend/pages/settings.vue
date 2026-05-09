<script setup lang="ts">
import {
  ArrowPathIcon,
  CheckIcon,
  ChevronUpIcon,
  Cog6ToothIcon,
  InformationCircleIcon,
  MagnifyingGlassIcon,
  PencilIcon,
  PlusIcon,
  TrashIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type {
  Agent,
  ConfigEntry,
  ConfigResponse,
  DiscoveredModel,
  DiscoverModelsResponse,
  OcrStatusResponse,
  ProviderModelDef,
} from '~/types/api'

const { data: configData, refresh } = await useFetch<ConfigResponse>('/api/config')
// JCLAW-177 follow-up: probe state + Config DB toggle for the OCR section.
// Fetched separately from /api/config so the section can render the toggle
// as uninteractive when the binary isn't on PATH (probe.available=false),
// regardless of what the stored ocr.tesseract.enabled row says.
const { data: ocrStatus, refresh: refreshOcrStatus }
  = await useFetch<OcrStatusResponse>('/api/ocr/status')
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
  'dispatcher.', // OkHttp dispatcher caps — Settings (Performance)
  'ocr.', // OCR backends — Settings (Tesseract today; GLM-OCR planned)
  'search.', // Search providers — Settings
  'scanner.', // Malware scanners — Settings
  'chat.', // Chat settings — Settings
  'shell.', // Shell execution defaults — Settings (allowlist + timeout)
  'playwright.', // JCLAW-172: namespace retired but kept in the prefix list
  // so leftover playwright.enabled / playwright.headless rows on upgraded
  // installs don't surface as "Unmanaged" diagnostic noise.
  'skillsPromotion.', // Skills promotion sanitization — Settings
  'agent.', // Per-agent config (shell privileges, queue mode, etc.) — Agents page
  'ollama.', // Ollama provider-specific settings — Settings
  'upload.', // Per-kind attachment size caps (JCLAW-131) — Settings
  'auth.', // Admin password hash — Settings (Password section, not rendered as a row)
  'onboarding.', // First-login guided tour flag — written by ApiOnboardingController, no UI surface
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

// LLM dispatcher caps — outbound concurrency tuning. Defaults seeded by
// DefaultConfigJob using clamp(8 * cores, 64, 256) per host (total = 2×);
// live-applied via ConfigService side-effect, no restart required. Bumped
// transiently during loadtest when --concurrency exceeds the live cap.
const dispatcherMaxRequestsPerHost = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'dispatcher.llm.maxRequestsPerHost')?.value ?? '64'
})

const dispatcherMaxRequests = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'dispatcher.llm.maxRequests')?.value ?? '128'
})

const editingPerfField = ref<string | null>(null)
const perfFieldEdit = ref('')

async function savePerfField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingPerfField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}

// JCLAW-131: Uploads settings — per-kind attachment size caps. Values are
// stored in bytes in the DB (matches services/UploadLimits.java) but
// surfaced as MB in the UI, where operators actually think. Defaults match
// the server-side constants so a fresh install with no rows shows the real
// effective limits.
const DEFAULT_MAX_IMAGE_MB = 20
const DEFAULT_MAX_AUDIO_MB = 100
const DEFAULT_MAX_FILE_MB = 100

function bytesToMb(raw: string | undefined, fallback: number): string {
  if (!raw) return String(fallback)
  const bytes = Number.parseInt(raw, 10)
  if (!Number.isFinite(bytes) || bytes <= 0) return String(fallback)
  return String(Math.round(bytes / (1024 * 1024)))
}

const uploadMaxImageMb = computed(() => {
  const raw = configData.value?.entries?.find(e => e.key === 'upload.maxImageBytes')?.value
  return bytesToMb(raw, DEFAULT_MAX_IMAGE_MB)
})
const uploadMaxAudioMb = computed(() => {
  const raw = configData.value?.entries?.find(e => e.key === 'upload.maxAudioBytes')?.value
  return bytesToMb(raw, DEFAULT_MAX_AUDIO_MB)
})
const uploadMaxFileMb = computed(() => {
  const raw = configData.value?.entries?.find(e => e.key === 'upload.maxFileBytes')?.value
  return bytesToMb(raw, DEFAULT_MAX_FILE_MB)
})

const editingUploadField = ref<string | null>(null)
const uploadFieldEdit = ref('')

async function saveUploadMb(configKey: string, mbValue: string) {
  saving.value = true
  try {
    const mb = Math.max(1, Number.parseInt(mbValue, 10) || 0)
    const bytes = mb * 1024 * 1024
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value: String(bytes) } })
    editingUploadField.value = null
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

// JCLAW-172: Browser (Playwright) and global Shell-Enabled toggles were
// removed. The browser tool is always headless; both tools register
// unconditionally and per-agent enable/disable lives on the Tools page.
// Shell allowlist + timeouts remain operator-tunable below.

// Shell execution config
const SHELL_KEYS = ['shell.allowlist', 'shell.defaultTimeoutSeconds', 'shell.maxTimeoutSeconds', 'shell.maxOutputBytes'] as const

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

// --- OCR backends ---
// Tesseract today; the response contract is array-shaped so JCLAW-179
// (GLM-OCR via ollama-local) can append a second entry without churn.
// The toggle is bound to a Config DB row but the *render* gates on the
// runtime probe — a host without the binary cannot flip the toggle on,
// matching the spec ("disabled and not selectable to be toggled").

async function toggleOcrBackend(backend: { name: string, configKey: string, available: boolean, enabled: boolean }) {
  if (!backend.available) return // probe says unavailable — toggle is inert
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: backend.configKey, value: backend.enabled ? 'false' : 'true' },
    })
    refreshOcrStatus()
    refresh()
  }
  finally {
    saving.value = false
  }
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
// contextWindow/maxTokens default to 0 ("unknown — please set"). Seeding a
// plausible-but-wrong number here used to mask provider-discovery gaps —
// e.g. Ollama Cloud's /v1/models returns no context field, so the frontend
// previously silently wrote 131072 (kimi-k2.5 is actually 256K), which
// then broke /usage and compaction-budget math. Show 0 honestly instead
// and let the user enter the real value from the provider's docs.
const modelForm = ref({ id: '', name: '', contextWindow: 0, maxTokens: 0, supportsThinking: false, supportsVision: false, supportsAudio: false, promptPrice: -1, completionPrice: -1, cachedReadPrice: -1, cacheWritePrice: -1 })
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
    // Preserve 0 ("unknown") honestly — don't fabricate a fallback. See
    // modelForm initializer above for context.
    contextWindow: m.contextWindow ?? 0,
    maxTokens: m.maxTokens ?? 0,
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
  modelForm.value = { id: '', name: '', contextWindow: 0, maxTokens: 0, supportsThinking: false, supportsVision: false, supportsAudio: false, promptPrice: -1, completionPrice: -1, cachedReadPrice: -1, cacheWritePrice: -1 }
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
}

const PROVIDER_LABELS: Record<string, string> = {
  'ollama-cloud': 'Ollama Cloud',
  'openrouter': 'OpenRouter',
  'openai': 'OpenAI',
  'together': 'TogetherAI',
  'ollama-local': 'Ollama Local',
  'lm-studio': 'LM Studio',
}

function providerGroup(name: string): 'remote' | 'local' {
  return PROVIDER_GROUPS[name] ?? 'remote'
}

function providerLabel(name: string): string {
  return PROVIDER_LABELS[name] ?? name
}

const groupedProviders = computed(() => {
  const remote: Array<[string, ConfigEntry[]]> = []
  const local: Array<[string, ConfigEntry[]]> = []
  for (const [name, entries] of providerEntries.value.providers) {
    if (providerGroup(name) === 'local') local.push([name, entries])
    else remote.push([name, entries])
  }
  return [
    { group: 'remote' as const, label: 'Remote', items: remote },
    { group: 'local' as const, label: 'Local', items: local },
  ]
})

// ──────────────────── Password / account management ─────────────────────
const { resetPassword } = useAuth()
const { confirm } = useConfirm()
const resettingPassword = ref(false)

async function handleResetPassword() {
  // Destructive — confirm via the shared in-app dialog (same pattern as
  // the conversation-delete flow) rather than window.confirm, which
  // looks like a browser alert and escapes theming. On success the
  // backend clears the session too, so the very next request is
  // unauthenticated and the auth middleware routes to /setup-password.
  const ok = await confirm({
    title: 'Reset admin password',
    message: 'This wipes the stored password from the database and signs you out. '
      + 'On next access you\'ll be taken to the setup screen to choose a new password.',
    confirmText: 'Reset',
    variant: 'danger',
  })
  if (!ok) return
  resettingPassword.value = true
  const success = await resetPassword()
  resettingPassword.value = false
  if (success) {
    navigateTo('/setup-password')
  }
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Settings
    </h1>

    <!-- Provider sections -->
    <div
      class="mb-6 space-y-4"
      data-tour="llm-providers"
    >
      <h2 class="text-sm font-medium text-fg-muted">
        LLM Providers
      </h2>
      <p class="text-xs text-fg-muted">
        Enter an API key for at least one provider to enable chat. Base URLs and models are pre-configured.
      </p>
      <template
        v-for="group in groupedProviders"
        :key="group.group"
      >
        <h3
          v-if="group.items.length > 0"
          class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2 first:pt-0"
        >
          {{ group.label }}
        </h3>
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
                v-if="entries.find((e: any) => e.key.endsWith('.apiKey') && e.value && !e.value.startsWith('****') && e.value !== '****')"
                class="text-[10px] text-green-400 border border-green-400/30 px-1"
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
                class="text-[10px] text-amber-400 border border-amber-400/40 px-1"
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
              <span class="text-xs text-red-400">{{ discoveryError }}</span>
            </div>

            <!-- Results -->
            <template v-else-if="discoveredModels.length">
              <!-- Search + filters -->
              <div class="px-4 py-2 flex items-center gap-2 border-b border-border">
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
      </template>
    </div>

    <!-- OCR Backends -->
    <div
      class="mb-6 space-y-4"
      data-tour="ocr-backends"
    >
      <h2 class="text-sm font-medium text-fg-muted">
        OCR
      </h2>
      <p class="text-xs text-fg-muted">
        Backends that extract text from images and scanned PDFs via the <span class="text-fg-muted">documents</span> tool.
        A backend can be toggled only when its system dependency is detected on the host. Install the missing dependency and restart the JVM to enable.
      </p>
      <div
        v-for="backend in (ocrStatus?.providers ?? [])"
        :key="backend.name"
        :class="[
          'bg-surface-elevated border border-border',
          backend.available ? '' : 'opacity-60',
        ]"
      >
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">{{ backend.displayName }}</span>
            <span
              v-if="backend.available && backend.enabled"
              class="text-[10px] text-green-400 border border-green-400/30 px-1"
            >active</span>
            <span
              v-else-if="backend.available && !backend.enabled"
              class="text-[10px] text-fg-muted border border-input px-1"
            >disabled</span>
            <span
              v-else
              class="text-[10px] text-amber-400 border border-amber-400/40 px-1"
              :title="backend.reason ?? 'binary not detected on PATH'"
            >not detected</span>
            <span
              v-if="backend.available && backend.version"
              class="text-[10px] text-fg-muted font-mono ml-1"
            >{{ backend.version }}</span>
          </div>
          <button
            :aria-label="`${backend.available && backend.enabled ? 'Disable' : 'Enable'} ${backend.displayName}`"
            :title="backend.available
              ? (backend.enabled ? 'Disable this backend' : 'Enable this backend')
              : 'Backend dependency is not installed — toggle is disabled'"
            :disabled="!backend.available"
            :class="[
              'relative w-9 h-5 rounded-full transition-colors',
              backend.available
                ? (backend.enabled ? 'bg-emerald-600 hover:bg-emerald-500 cursor-pointer' : 'bg-muted hover:bg-muted cursor-pointer')
                : 'bg-muted cursor-not-allowed',
            ]"
            @click="toggleOcrBackend(backend)"
          >
            <span
              :class="(backend.available && backend.enabled) ? 'translate-x-4' : 'translate-x-0.5'"
              class="block w-4 h-4 bg-white rounded-full transition-transform"
            />
          </button>
        </div>
        <div class="px-4 py-2.5 text-xs text-fg-muted leading-relaxed">
          {{ backend.description }}
          <span
            v-if="!backend.available"
            class="block mt-1 text-amber-400"
          >{{ backend.installHint }}</span>
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
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ searchApiKeyEntry(id).value || '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(searchApiKeyEntry(id))"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
              <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ searchBaseUrl(id) || '(not set)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(searchBaseUrlEntry(id))"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
              :id="`search-recency-filter-${id}`"
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
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingChatField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ chatMaxToolRounds }} rounds</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingChatField = 'maxToolRounds'; chatFieldEdit = chatMaxToolRounds"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxContextMessages
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingChatField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ chatMaxContextMessages }} messages</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingChatField = 'maxContextMessages'; chatFieldEdit = chatMaxContextMessages"
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
    </div>

    <!-- Performance: LLM dispatcher caps -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Performance
      </h2>
      <p class="text-xs text-fg-muted">
        OkHttp dispatcher concurrency caps for outbound LLM calls.
        <span class="font-mono">maxRequestsPerHost</span> bounds in-flight calls to a
        single provider; <span class="font-mono">maxRequests</span> bounds the total
        across all providers. Auto-tuned at first start to
        <span class="font-mono">clamp(8 × cores, 64, 256)</span> per host with total set
        to <span class="font-mono">2×</span> that. Changes apply live; no restart needed.
        Transiently bumped during loadtest when <span class="font-mono">--concurrency</span>
        would otherwise saturate.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">
              maxRequestsPerHost
            </span>
            <template v-if="editingPerfField === 'maxRequestsPerHost'">
              <input
                v-model="perfFieldEdit"
                type="number"
                min="1"
                max="1024"
                aria-label="Max requests per host"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="savePerfField('dispatcher.llm.maxRequestsPerHost', perfFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingPerfField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ dispatcherMaxRequestsPerHost }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingPerfField = 'maxRequestsPerHost'; perfFieldEdit = dispatcherMaxRequestsPerHost"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0">
              maxRequests
            </span>
            <template v-if="editingPerfField === 'maxRequests'">
              <input
                v-model="perfFieldEdit"
                type="number"
                min="1"
                max="2048"
                aria-label="Max requests total"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="savePerfField('dispatcher.llm.maxRequests', perfFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingPerfField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ dispatcherMaxRequests }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingPerfField = 'maxRequests'; perfFieldEdit = dispatcherMaxRequests"
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
    </div>

    <!-- Uploads (JCLAW-131) -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Uploads
      </h2>
      <p class="text-xs text-fg-muted">
        Per-kind attachment size caps. The sniffed MIME decides which limit applies —
        images, audio, or everything else. Takes effect without a restart; raise the
        transport-layer ceiling in application.conf if you need over 512 MB.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxImageBytes
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Max upload size for image attachments, in megabytes. Stored as bytes; most vision models accept up to 20 MB per image.
                </span>
              </span>
            </span>
            <template v-if="editingUploadField === 'maxImageMb'">
              <input
                v-model="uploadFieldEdit"
                type="number"
                min="1"
                max="512"
                aria-label="Max image upload MB"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveUploadMb('upload.maxImageBytes', uploadFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingUploadField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxImageMb }} MB</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingUploadField = 'maxImageMb'; uploadFieldEdit = uploadMaxImageMb"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxAudioBytes
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Max upload size for audio attachments, in megabytes. 100 MB holds roughly an hour of 128 kbps recording.
                </span>
              </span>
            </span>
            <template v-if="editingUploadField === 'maxAudioMb'">
              <input
                v-model="uploadFieldEdit"
                type="number"
                min="1"
                max="512"
                aria-label="Max audio upload MB"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveUploadMb('upload.maxAudioBytes', uploadFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingUploadField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxAudioMb }} MB</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingUploadField = 'maxAudioMb'; uploadFieldEdit = uploadMaxAudioMb"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxFileBytes
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Max upload size for every other attachment type (PDFs, text, archives, etc.), in megabytes.
                </span>
              </span>
            </span>
            <template v-if="editingUploadField === 'maxFileMb'">
              <input
                v-model="uploadFieldEdit"
                type="number"
                min="1"
                max="512"
                aria-label="Max file upload MB"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveUploadMb('upload.maxFileBytes', uploadFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingUploadField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ uploadMaxFileMb }} MB</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingUploadField = 'maxFileMb'; uploadFieldEdit = uploadMaxFileMb"
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ spTimeout }}s</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSPField = 'timeout'; spFieldEdit = spTimeout"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSPField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ spBatchKb }} KB</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSPField = 'batchKb'; spFieldEdit = spBatchKb"
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
    </div>

    <!-- Shell Execution -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Shell Execution
      </h2>
      <p class="text-xs text-fg-muted">
        Allowlist and timeout for the shell tool. Per-agent enable/disable
        lives on the Tools page; this section configures the shared
        execution policy.
      </p>
      <div class="bg-surface-elevated border border-border">
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
                  <CheckIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                </button>
                <button
                  class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                  title="Cancel"
                  @click="editingShellField = null"
                >
                  <XMarkIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
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
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
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
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingShellField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ shellConfig.defaultTimeout }}s</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startShellEdit('timeout', shellConfig.defaultTimeout)"
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
            <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ scannerApiKeyEntry(id).value || '(not set)' }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="startEdit(scannerApiKeyEntry(id))"
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

    <!-- Password / account management -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Password
      </h2>
      <p class="text-xs text-fg-muted">
        The admin password is stored as a PBKDF2-SHA256 hash in the Config DB. Resetting wipes the
        stored hash and signs you out — on the next access you'll be routed to the setup screen to
        choose a new password.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="px-4 py-2.5 flex items-center justify-between gap-4">
          <div class="min-w-0">
            <span class="text-sm font-medium text-fg-strong">Reset password</span>
            <div class="text-xs text-fg-muted mt-0.5">
              Wipe the stored hash and return to the setup flow.
            </div>
          </div>
          <button
            :disabled="resettingPassword"
            class="shrink-0 px-3 py-1.5 text-xs font-medium text-white
                   bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                   disabled:cursor-not-allowed rounded-full transition-colors"
            @click="handleResetPassword"
          >
            {{ resettingPassword ? 'Resetting…' : 'Reset' }}
          </button>
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
