<script setup lang="ts">
// Search Providers settings panel (JCLAW-680). Web search engines agents can
// call via the web_search tool, priority-ordered with drag-drop reordering.
// Moved verbatim from pages/settings.vue. Self-contained on the shared config
// store + inline config-row editor.
import {
  CheckIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, refresh, editingKey, editValue, startEdit, updateEntry } = useSettingsConfig()

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
</script>

<template>
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
            class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          >active</span>
          <span
            v-else-if="searchEnabled(id)"
            class="text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
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
</template>
