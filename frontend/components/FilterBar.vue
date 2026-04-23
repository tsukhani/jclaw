<script setup lang="ts">
import {
  ArrowDownTrayIcon,
  BookmarkIcon,
} from '@heroicons/vue/24/outline'

export interface Filter {
  key: string
  value: string
}

export interface SavedView {
  name: string
  filters: Filter[]
}

const props = withDefaults(defineProps<{
  /** Unique key for localStorage saved views (e.g., 'conversations', 'logs') */
  storageKey: string
  /** Placeholder text for the query input */
  placeholder?: string
  /** Available filter keys shown as autocomplete hints */
  filterKeys?: string[]
}>(), {
  placeholder: 'Filter... (e.g., agent:main channel:web)',
  filterKeys: () => [],
})

const emit = defineEmits<{
  (e: 'update:filters', filters: Filter[]): void
  (e: 'export'): void
}>()

// ── Filter state ────────────────────────────────────────────────────────────
const filters = ref<Filter[]>([])
const queryInput = ref('')
const showSavedViews = ref(false)
const saveName = ref('')
const showSaveInput = ref(false)

// ── Parse query syntax ──────────────────────────────────────────────────────
function parseQuery(query: string): Filter[] {
  if (!query.trim()) return []
  return query.trim().split(/\s+/).map((token) => {
    const colonIdx = token.indexOf(':')
    if (colonIdx > 0) {
      return { key: token.substring(0, colonIdx), value: token.substring(colonIdx + 1) }
    }
    return { key: 'name', value: token }
  }).filter(f => f.value)
}

function commitQuery() {
  const parsed = parseQuery(queryInput.value)
  if (!parsed.length) return
  // Merge with existing filters (replace same keys, add new ones)
  const merged = [...filters.value]
  for (const f of parsed) {
    const existing = merged.findIndex(m => m.key === f.key)
    if (existing >= 0) {
      merged[existing] = f
    }
    else {
      merged.push(f)
    }
  }
  filters.value = merged
  queryInput.value = ''
  emit('update:filters', filters.value)
}

function removeFilter(index: number) {
  filters.value = filters.value.filter((_, i) => i !== index)
  emit('update:filters', filters.value)
}

function clearAll() {
  filters.value = []
  queryInput.value = ''
  emit('update:filters', filters.value)
}

// ── Saved views (localStorage) ──────────────────────────────────────────────
const savedViewsKey = computed(() => `jclaw-filters-${props.storageKey}`)

const savedViews = ref<SavedView[]>([])

onMounted(() => {
  try {
    const stored = localStorage.getItem(savedViewsKey.value)
    if (stored) savedViews.value = JSON.parse(stored)
  }
  catch { /* ignore corrupt data */ }
})

function persistViews() {
  localStorage.setItem(savedViewsKey.value, JSON.stringify(savedViews.value))
}

function saveCurrentView() {
  const name = saveName.value.trim()
  if (!name || !filters.value.length) return
  // Replace if same name exists
  const idx = savedViews.value.findIndex(v => v.name === name)
  const view: SavedView = { name, filters: [...filters.value] }
  if (idx >= 0) {
    savedViews.value[idx] = view
  }
  else {
    savedViews.value.push(view)
  }
  persistViews()
  saveName.value = ''
  showSaveInput.value = false
}

function applySavedView(view: SavedView) {
  filters.value = [...view.filters]
  showSavedViews.value = false
  emit('update:filters', filters.value)
}

function deleteSavedView(index: number) {
  savedViews.value.splice(index, 1)
  persistViews()
}

// ── Keyboard handling ───────────────────────────────────────────────────────
function handleInputKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') {
    e.preventDefault()
    commitQuery()
  }
  if (e.key === 'Backspace' && !queryInput.value && filters.value.length) {
    // Remove last chip on backspace in empty input
    removeFilter(filters.value.length - 1)
  }
  if (e.key === 'Escape') {
    showSavedViews.value = false
    showSaveInput.value = false
  }
}
</script>

<template>
  <div
    role="search"
    class="flex items-center gap-2 flex-wrap"
  >
    <!-- Chips + input -->
    <div class="flex-1 flex items-center gap-1.5 flex-wrap min-w-0 px-3 py-1.5 bg-surface-elevated border border-border rounded-sm focus-within:border-ring transition-colors">
      <!-- Active filter chips -->
      <span
        v-for="(filter, i) in filters"
        :key="`${filter.key}-${i}`"
        :aria-label="`Filter: ${filter.key} is ${filter.value}`"
        class="inline-flex items-center gap-1 px-2 py-0.5 bg-muted text-xs text-fg-primary rounded-sm"
      >
        <span class="text-fg-muted">{{ filter.key }}:</span>
        <span class="font-medium">{{ filter.value }}</span>
        <button
          class="text-fg-muted hover:text-fg-strong ml-0.5"
          :aria-label="`Remove filter ${filter.key}: ${filter.value}`"
          @click="removeFilter(i)"
        >×</button>
      </span>

      <!-- Query input -->
      <input
        v-model="queryInput"
        type="text"
        :placeholder="filters.length ? 'Add filter...' : placeholder"
        aria-label="Filter query"
        class="flex-1 min-w-32 bg-transparent text-sm text-fg-strong placeholder-fg-muted focus:outline-hidden"
        @keydown="handleInputKeydown"
      >
    </div>

    <!-- Saved views dropdown -->
    <div class="relative">
      <button
        class="px-2.5 py-1.5 border border-border rounded-sm text-xs text-fg-muted hover:text-fg-strong hover:border-ring transition-colors"
        title="Saved views"
        @click="showSavedViews = !showSavedViews"
      >
        <BookmarkIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
      </button>
      <div
        v-if="showSavedViews"
        class="absolute right-0 top-full mt-1 w-56 bg-surface-elevated border border-border rounded-sm shadow-lg z-20"
      >
        <div
          v-if="!savedViews.length && !showSaveInput"
          class="px-3 py-2 text-xs text-fg-muted"
        >
          No saved views
        </div>
        <div
          v-for="(view, i) in savedViews"
          :key="view.name"
          class="w-full flex items-center justify-between px-3 py-2 text-xs text-fg-primary hover:bg-muted transition-colors group cursor-pointer"
          role="button"
          tabindex="0"
          @click="applySavedView(view)"
          @keydown.enter="applySavedView(view)"
        >
          <span class="truncate">{{ view.name }}</span>
          <button
            class="text-fg-muted hover:text-danger opacity-0 group-hover:opacity-100 transition-opacity"
            :aria-label="`Delete saved view ${view.name}`"
            @click.stop="deleteSavedView(i)"
          >
            ×
          </button>
        </div>
        <div class="border-t border-border">
          <div
            v-if="showSaveInput"
            class="flex items-center gap-1 px-2 py-1.5"
          >
            <input
              v-model="saveName"
              type="text"
              placeholder="View name..."
              aria-label="Saved view name"
              class="flex-1 px-2 py-1 bg-muted border border-border rounded-sm text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden focus:border-ring"
              @keydown.enter="saveCurrentView"
              @keydown.escape="showSaveInput = false"
            >
            <button
              class="text-xs text-emerald-600 dark:text-emerald-400 hover:underline"
              @click="saveCurrentView"
            >
              Save
            </button>
          </div>
          <button
            v-else
            :disabled="!filters.length"
            class="w-full px-3 py-2 text-xs text-fg-muted hover:text-fg-strong hover:bg-muted transition-colors text-left disabled:opacity-40 disabled:cursor-not-allowed"
            @click="showSaveInput = true"
          >
            Save current view...
          </button>
        </div>
      </div>
    </div>

    <!-- Clear all -->
    <button
      v-if="filters.length"
      class="px-2.5 py-1.5 text-xs text-fg-muted hover:text-fg-strong transition-colors"
      title="Clear all filters"
      @click="clearAll"
    >
      Clear
    </button>

    <!-- Export button -->
    <button
      class="px-2.5 py-1.5 border border-border rounded-sm text-xs text-fg-muted hover:text-fg-strong hover:border-ring transition-colors"
      title="Export"
      @click="emit('export')"
    >
      <ArrowDownTrayIcon
        class="w-4 h-4"
        aria-hidden="true"
      />
    </button>
  </div>
</template>
