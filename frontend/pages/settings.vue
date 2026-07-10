<script setup lang="ts">
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import type {
  Agent,
  ConfigEntry,
  OcrStatusResponse,
} from '~/types/api'

// JCLAW-680: the shared /api/config store, page-wide inline config-row editor,
// and /api/providers billing projection live in a composable that provides a
// reactive context to the extracted panels. The page still holds the same
// configData/refresh/saving/editingKey refs (children inject the identical
// objects).
const {
  configData, refresh, saving, getProviderModels,
  editingKey, editValue, startEdit, updateEntry,
  asyncConfig, asyncProviders,
} = useProvideSettingsConfig()
await asyncConfig
await asyncProviders
// JCLAW-177 follow-up: probe state + Config DB toggle for the OCR section.
// Fetched separately from /api/config so the section can render the toggle
// as uninteractive when the binary isn't on PATH (probe.available=false),
// regardless of what the stored ocr.tesseract.enabled row says.
const { data: ocrStatus, refresh: refreshOcrStatus }
  = await useFetch<OcrStatusResponse>('/api/ocr/status')

// Top-level Config DB prefixes claimed by a UI domain. Any row whose key starts
// with one of these is owned somewhere in the app and should not surface in the
// Unmanaged diagnostic list — regardless of which page actually manages it.
// Keeps Settings free of exact-key knowledge about other pages' config.
const MANAGED_PREFIXES = [
  'app.', // Operator-wide settings — Settings (General). app.timezone = the
  // assistant's wall-clock zone injected into the system prompt.
  'provider.', // LLM providers — Settings
  'dispatcher.', // OkHttp dispatcher caps — Settings (Performance)
  'transcription.', // Transcription provider + local model — Settings (Transcription)
  'caption.', // Image captioning cloud + local model (caption.cloud.*) — Settings (Image Captioning)
  'video.', // Video interpretation frame-sample density (video.sampleFrames) — Settings (Video Interpretation)
  'imagegen.', // Image generation provider selection (imagegen.provider) — Settings (Image Generation)
  'videogen.', // Video generation provider + job timeout (videogen.provider) — Settings (Video Generation)
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
  'pricing.', // LiteLLM nightly price-refresh toggle (JCLAW-28 follow-up) — Settings (LLM Providers section)
  'subagent.', // JCLAW-266: subagent recursion caps — Settings (Subagents section)
  'tasks.', // JCLAW-259: task retention TTL — Settings (Tasks section)
  'tailscale.', // Funnel enable/port (tailscale.funnel.*) — managed on the Channels page
  'jtokkit.', // Token-count safety multipliers — `jtokkit.safetyMultiplier.unmatched`
  // is the operator-tunable global in the Advanced subsection of Chat, and the
  // per-(provider, model) `jtokkit.safetyMultiplier.<provider>.<modelId>`
  // entries are written autonomously by TokenizerCalibrationJob every 30 min
  // based on observed provider-vs-jtokkit deltas — operators don't manage
  // these directly. Surfacing them in "Unmanaged keys" would imply they're
  // stale or operator-actionable, neither of which is true.
]

function isManagedKey(key: string): boolean {
  return MANAGED_PREFIXES.some(p => key.startsWith(p))
}

// JCLAW-266: subagent recursion caps. DB-backed via ConfigService so the
// Settings page can edit them at runtime without a restart. Defaults
// mirror the Java-side fallbacks in SubagentSpawnTool.
const subagentMaxDepth = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.maxDepth')?.value ?? '1'
})

const subagentMaxChildrenPerParent = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.maxChildrenPerParent')?.value ?? '5'
})

// JCLAW-499: external-harness (ACP) runtime command. Empty = disabled
// (runtime="acp" spawns are refused until set). Operator-set only.
const subagentAcpCommand = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'subagent.acp.command')?.value ?? ''
})

const editingSubagentField = ref<string | null>(null)
const subagentFieldEdit = ref('')

async function saveSubagentField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingSubagentField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}

// ──────────────────── Tasks retention (JCLAW-259) ───────────────────────
// tasks.retentionDays controls TaskCleanupJob's TTL sweep. Default '30';
// '0' (or blank) means "never auto-delete". Mirrors the subagent.* editing
// pattern above — same managed-config-key shape, same edit/save/cancel
// triple.
const tasksRetentionDays = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'tasks.retentionDays')?.value ?? '30'
})

// JCLAW-261: tasks.defaultTimezone is the global default applied to
// CRON / SCHEDULED tasks that don't carry their own per-task timezone.
// When absent from the Config table, the backend falls back to
// application.conf and ultimately ZoneId.systemDefault() — we display
// the effective resolved value from GET /api/timezones rather than
// guess a fallback here, so the UI matches what the scheduler actually
// uses at fire time.
// `default` = effective task-scheduling zone; `appDefault` = effective
// operator wall-clock zone (app.timezone chain → server JVM zone). Both come
// resolved from the backend so the UI never re-implements the fallback chain.
interface TimezonesPayload { timezones: string[], default: string, appDefault: string }
const timezonesPayload = await useFetch<TimezonesPayload>('/api/timezones', {
  default: () => ({ timezones: [], default: 'UTC', appDefault: 'UTC' }),
})
const tasksDefaultTimezone = computed(() => {
  const entries = configData.value?.entries ?? []
  const stored = entries.find(e => e.key === 'tasks.defaultTimezone')?.value
  return stored ?? timezonesPayload.data.value?.default ?? 'UTC'
})

// app.timezone — the operator's wall-clock zone the assistant treats as "now".
// Falls back to the backend-resolved appDefault (server JVM zone) when unset.
const appTimezone = computed(() => {
  const entries = configData.value?.entries ?? []
  const stored = entries.find(e => e.key === 'app.timezone')?.value
  return stored ?? timezonesPayload.data.value?.appDefault ?? 'UTC'
})

const editingTasksField = ref<string | null>(null)
const tasksFieldEdit = ref('')

async function saveTasksField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingTasksField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}

// General section (operator-wide settings). Separate edit state from Tasks so
// the two timezone controls don't share an "editing" flag.
const editingGeneralField = ref<string | null>(null)
const generalFieldEdit = ref('')

async function saveGeneralField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingGeneralField.value = null
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

// JCLAW-422: subagent model. Unset (the default) = inherit the conversation's
// model — your agent's default unless you switch mid-chat. A specific value
// pins ALL fan-outs to that model (e.g. a cheaper one for large evaluations).
const subagentModelValue = computed(() => {
  const entries = configData.value?.entries ?? []
  const p = entries.find(e => e.key === 'subagent.modelProvider')?.value
  const m = entries.find(e => e.key === 'subagent.modelId')?.value
  return p && m ? `${p}::${m}` : ''
})

const subagentInheritLabel = computed(() => {
  const a = agentsList.value?.find(x => x.isMain) ?? agentsList.value?.[0]
  return a ? `${a.modelProvider} / ${a.modelId}` : 'the agent default'
})

const allModelOptions = computed(() => {
  const opts: { value: string, label: string }[] = []
  for (const provider of availableProviderNames.value) {
    for (const m of getProviderModels(provider)) {
      opts.push({ value: `${provider}::${m.id}`, label: `${provider} / ${m.name || m.id}` })
    }
  }
  return opts
})

async function saveSubagentModel(value: string) {
  saving.value = true
  try {
    if (!value) {
      await $fetch('/api/config/subagent.modelProvider', { method: 'DELETE' })
      await $fetch('/api/config/subagent.modelId', { method: 'DELETE' })
    }
    else {
      const sep = value.indexOf('::')
      await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.modelProvider', value: value.slice(0, sep) } })
      await $fetch('/api/config', { method: 'POST', body: { key: 'subagent.modelId', value: value.slice(sep + 2) } })
    }
    refresh()
  }
  finally {
    saving.value = false
  }
}

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
  return entry ? Number.parseInt(entry.value, 10) : 99
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

// JCLAW-229: image-generation-only providers are NOT chat LLM providers (excluded from the backend
// ProviderRegistry too) — their keys are set in the Image Generation section, not here, so skip them
// when grouping the LLM Providers list.
const IMAGE_ONLY_PROVIDERS = new Set(['bfl', 'replicate'])

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
      if (IMAGE_ONLY_PROVIDERS.has(name)) continue // set in the Image Generation section
      if (!providers.has(name)) providers.set(name, [])
      providers.get(name)!.push(e)
    }
    else if (!isManagedKey(e.key)) {
      other.push(e)
    }
  }
  return { providers, other }
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

    <!-- General: operator-wide settings. Timezone is the zone the assistant
         treats as "now" when its system prompt injects the current date/time.
         Defaults to the server's JVM zone; distinct from the Tasks default
         timezone (which governs CRON scheduling and defaults to UTC). -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Timezone
      </h2>
      <p class="text-xs text-fg-muted">
        Your timezone. The assistant uses this as the current date and time in
        every conversation, so it doesn't guess the clock. Defaults to the
        server's timezone; this is separate from the
        <span class="font-mono">Tasks</span> default timezone used for CRON
        scheduling.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              timezone
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  IANA timezone the assistant treats as the current wall-clock time in its system prompt. Defaults to the server's JVM zone when unset.
                </span>
              </span>
            </span>
            <template v-if="editingGeneralField === 'timezone'">
              <select
                v-model="generalFieldEdit"
                aria-label="Operator timezone"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
                <option
                  v-for="z in timezonesPayload.data.value?.timezones ?? []"
                  :key="z"
                  :value="z"
                >
                  {{ z }}
                </option>
              </select>
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveGeneralField('app.timezone', generalFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingGeneralField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ appTimezone }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingGeneralField = 'timezone'; generalFieldEdit = appTimezone"
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

    <SettingsLoggingPanel />

    <SettingsProvidersPanel />

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

    <SettingsTranscriptionPanel />

    <SettingsImageCaptionPanel />

    <SettingsImageGenPanel />

    <SettingsVideoInterpPanel />

    <SettingsVideoGenPanel />

    <SettingsChatPanel />

    <!-- Subagents (JCLAW-266) -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Subagents
      </h2>
      <p class="text-xs text-fg-muted">
        Recursion caps for the <span class="font-mono">subagent_spawn</span> tool.
        <span class="font-mono">maxDepth</span> bounds how deep the parent-child
        chain may go (1 = top-level agents may spawn, grandchildren refused);
        <span class="font-mono">maxChildrenPerParent</span> bounds how many
        concurrent <span class="font-mono">RUNNING</span> children a single parent
        may have in flight. On violation the tool emits
        <span class="font-mono">SUBAGENT_LIMIT_EXCEEDED</span> and returns a
        plain-text refusal to the model. Changes apply live; no restart needed.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              maxDepth
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Max recursion depth for subagent_spawn. 1 = only top-level agents may spawn; a child trying to spawn its own subagent is refused.
                </span>
              </span>
            </span>
            <template v-if="editingSubagentField === 'maxDepth'">
              <input
                v-model="subagentFieldEdit"
                type="number"
                min="1"
                max="32"
                aria-label="Max recursion depth"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSubagentField('subagent.maxDepth', subagentFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSubagentField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ subagentMaxDepth }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSubagentField = 'maxDepth'; subagentFieldEdit = subagentMaxDepth"
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
              maxChildrenPerParent
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Max concurrent RUNNING children per parent. Counted per direct parent, not across the whole subtree. The (N+1)th spawn from the same parent is refused while N children are in flight.
                </span>
              </span>
            </span>
            <template v-if="editingSubagentField === 'maxChildrenPerParent'">
              <input
                v-model="subagentFieldEdit"
                type="number"
                min="1"
                max="64"
                aria-label="Max concurrent children per parent"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSubagentField('subagent.maxChildrenPerParent', subagentFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSubagentField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ subagentMaxChildrenPerParent }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSubagentField = 'maxChildrenPerParent'; subagentFieldEdit = subagentMaxChildrenPerParent"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- JCLAW-499: external-harness (ACP) runtime command. Empty disables
               runtime="acp" subagents; the harness is operator-set, never model-supplied. -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              acp.command
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  External agent harness for runtime="acp" subagents — e.g. "claude -p" or "codex exec". The task is sent on stdin and stdout becomes the reply. Empty disables ACP (runtime="acp" spawns are refused). Operator-set only; never model-supplied.
                </span>
              </span>
            </span>
            <template v-if="editingSubagentField === 'acpCommand'">
              <input
                v-model="subagentFieldEdit"
                type="text"
                placeholder="(disabled)"
                aria-label="ACP harness command"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveSubagentField('subagent.acp.command', subagentFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingSubagentField = null"
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
                :class="subagentAcpCommand ? 'text-fg-primary' : 'text-fg-muted'"
              >{{ subagentAcpCommand || '(disabled)' }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingSubagentField = 'acpCommand'; subagentFieldEdit = subagentAcpCommand"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- JCLAW-422: model subagents run on. Default (inherit) tracks the
               conversation's model; a specific value pins all fan-outs. -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              model
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-72 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Model subagents run on. "Conversation default" inherits the model your chat is using (your agent default unless you switch mid-chat). Pick a specific model to pin every fan-out to it — e.g. a cheaper model for large evaluations.
                </span>
              </span>
            </span>
            <select
              :value="subagentModelValue"
              aria-label="Subagent model"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              @change="saveSubagentModel(($event.target as HTMLSelectElement).value)"
            >
              <option value="">
                Conversation default (inherit — {{ subagentInheritLabel }})
              </option>
              <option
                v-for="o in allModelOptions"
                :key="o.value"
                :value="o.value"
              >
                {{ o.label }}
              </option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <!-- Tasks (JCLAW-259) -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-fg-muted">
        Tasks
      </h2>
      <p class="text-xs text-fg-muted">
        Retention TTL for terminal tasks. <span class="font-mono">TaskCleanupJob</span>
        runs every 24 hours and hard-deletes tasks in
        <span class="font-mono">COMPLETED</span> / <span class="font-mono">FAILED</span> /
        <span class="font-mono">CANCELLED</span> / <span class="font-mono">LOST</span>
        whose <span class="font-mono">updatedAt</span> predates the cutoff, along with
        their full run history (TaskRunMessage → TaskRun → Task). Active tasks
        (<span class="font-mono">PENDING</span> / <span class="font-mono">ACTIVE</span> /
        <span class="font-mono">RUNNING</span>) are never touched. Set to
        <span class="font-mono">0</span> to disable auto-cleanup entirely
        (tasks retained forever).
      </p>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              retentionDays
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  Days a terminal task stays in the DB before TaskCleanupJob deletes it. 0 = retention disabled. Max 3650 (≈10 years).
                </span>
              </span>
            </span>
            <template v-if="editingTasksField === 'retentionDays'">
              <input
                v-model="tasksFieldEdit"
                type="number"
                min="0"
                max="3650"
                aria-label="Task retention days"
                class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveTasksField('tasks.retentionDays', tasksFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingTasksField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ tasksRetentionDays }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingTasksField = 'retentionDays'; tasksFieldEdit = tasksRetentionDays"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
          <!-- JCLAW-261: default IANA timezone for CRON / SCHEDULED tasks
               that don't carry their own. Saved to Config DB, which
               overrides application.conf at runtime. -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
              defaultTimezone
              <span class="relative group/tip">
                <InformationCircleIcon
                  class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                  aria-hidden="true"
                />
                <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                  IANA timezone applied to CRON / SCHEDULED tasks that don't specify their own. Leave unset to follow the General timezone (your operator zone); set it only to run tasks in a different zone. Per-task `timezone` overrides this. INTERVAL / IMMEDIATE are duration-based and ignore timezone entirely.
                </span>
              </span>
            </span>
            <template v-if="editingTasksField === 'defaultTimezone'">
              <select
                v-model="tasksFieldEdit"
                aria-label="Default IANA timezone"
                class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
                <option
                  v-for="z in timezonesPayload.data.value?.timezones ?? []"
                  :key="z"
                  :value="z"
                >
                  {{ z }}
                </option>
              </select>
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveTasksField('tasks.defaultTimezone', tasksFieldEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingTasksField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono">{{ tasksDefaultTimezone }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="editingTasksField = 'defaultTimezone'; tasksFieldEdit = tasksDefaultTimezone"
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

    <SettingsPerformancePanel />

    <SettingsUploadsPanel />

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
