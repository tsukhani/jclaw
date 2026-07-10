<script setup lang="ts">
/**
 * JCLAW-40: agent memory admin. A cross-agent view of every agent's captured
 * memories — owning agent, text, category, importance, and created date —
 * filtered via a tasks-style FilterBar (free-text `q` over memory text plus
 * `agent` / `category` / `importance` / `status` predicates). The operator can
 * adjust importance inline and delete entries. Read path is GET
 * /api/memories?...; mutations go through PUT/DELETE /api/memories/{memoryId}.
 *
 * JCLAW-557: the default view shows active memories only (matching recall);
 * `status:superseded` / `status:all` expose the JCLAW-525 supersession trail,
 * rendered dimmed with a "superseded" badge that carries the when/by-whom.
 */
import { ChevronDownIcon, ChevronUpIcon } from '@heroicons/vue/24/outline'

interface MemoryDto {
  id: string
  agentName: string
  text: string
  category: string | null
  importance: number
  createdAt: string | null
  supersededAt: string | null
  supersededById: string | null
}

interface Filter { key: string, value: string }

// Per-category badge colors, keyed by the six canonical MemoryCategory labels.
// Unknown/legacy categories fall back to a neutral gray.
const CATEGORY_CLASS: Record<string, string> = {
  core: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  fact: 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300',
  preference: 'bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-300',
  decision: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  entity: 'bg-teal-100 text-teal-800 dark:bg-teal-900/40 dark:text-teal-300',
  lesson: 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-300',
}

function categoryClass(cat: string | null): string {
  return CATEGORY_CLASS[cat ?? ''] ?? 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
}

// FilterBar-driven query state. Each chip maps to a backend query param;
// removing a chip resets its ref to '' so that predicate clears.
const qFilter = ref('')
const agentFilter = ref('')
const categoryFilter = ref('')
const importanceFilter = ref('')
// JCLAW-557: active (default, matches recall) / superseded / all.
const statusFilter = ref('')

function onFiltersChanged(filters: Filter[]) {
  qFilter.value = filters.find(f => f.key === 'q')?.value ?? ''
  agentFilter.value = filters.find(f => f.key === 'agent')?.value ?? ''
  categoryFilter.value = filters.find(f => f.key === 'category')?.value ?? ''
  importanceFilter.value = filters.find(f => f.key === 'importance')?.value ?? ''
  statusFilter.value = filters.find(f => f.key === 'status')?.value ?? ''
}

const url = computed(() => {
  const params = new URLSearchParams()
  if (qFilter.value) params.set('q', qFilter.value)
  if (agentFilter.value) params.set('agent', agentFilter.value)
  if (categoryFilter.value) params.set('category', categoryFilter.value)
  if (importanceFilter.value) params.set('importance', importanceFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  params.set('limit', '200')
  return `/api/memories?${params}`
})

function supersededTitle(mem: MemoryDto): string {
  const when = mem.supersededAt ? formatDateTime(mem.supersededAt) : ''
  const by = mem.supersededById ? ` by memory #${mem.supersededById}` : ''
  return `Superseded${by} at ${when} — excluded from recall`
}

// Top-level await + reactive URL: re-fetches whenever a filter changes, and
// mountSuspended resolves with data in tests.
const { data: memoriesData, error: fetchError, refresh } = await useFetch<MemoryDto[]>(url)
const memories = computed(() => memoriesData.value ?? [])

// ── Client-side column sort ────────────────────────────────────────────────
// The ≤200 rows the API returns are sorted in the browser. sortBy=null keeps the
// server's default order; clicking a header sorts by it, clicking the same header
// flips direction, clicking a different one resets to that column's natural
// default (importance/created default descending — most-important / newest first;
// text columns default ascending).
type SortColumn = 'agent' | 'text' | 'category' | 'importance' | 'created'
type SortDir = 'asc' | 'desc'

const SORT_COLUMNS: { key: SortColumn, label: string, thClass: string }[] = [
  { key: 'agent', label: 'Agent', thClass: '' },
  { key: 'text', label: 'Memory', thClass: '' },
  { key: 'category', label: 'Category', thClass: '' },
  { key: 'importance', label: 'Importance', thClass: 'w-32' },
  { key: 'created', label: 'Created', thClass: '' },
]

const sortBy = ref<SortColumn | null>(null)
const sortDir = ref<SortDir>('asc')

function defaultDirFor(col: SortColumn): SortDir {
  return col === 'importance' || col === 'created' ? 'desc' : 'asc'
}

function toggleSort(col: SortColumn) {
  if (sortBy.value === col) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  }
  else {
    sortBy.value = col
    sortDir.value = defaultDirFor(col)
  }
}

const sortedMemories = computed<MemoryDto[]>(() => {
  const col = sortBy.value
  if (!col) return memories.value // untouched: preserve the server's default order
  const dir = sortDir.value === 'asc' ? 1 : -1
  return [...memories.value].sort((a, b) => {
    let cmp: number
    if (col === 'importance') {
      cmp = a.importance - b.importance
    }
    else if (col === 'created') {
      // ISO strings sort lexicographically == chronologically; missing dates
      // always sort last, regardless of direction.
      if (a.createdAt === b.createdAt) cmp = 0
      else if (!a.createdAt) return 1
      else if (!b.createdAt) return -1
      else cmp = a.createdAt.localeCompare(b.createdAt)
    }
    else if (col === 'agent') {
      cmp = a.agentName.localeCompare(b.agentName)
    }
    else if (col === 'category') {
      cmp = (a.category ?? '').localeCompare(b.category ?? '')
    }
    else {
      cmp = a.text.localeCompare(b.text)
    }
    // Stable, deterministic tie-break by id.
    return cmp !== 0 ? cmp * dir : a.id.localeCompare(b.id)
  })
})

const { mutate } = useApiMutation()
const { confirm } = useConfirm()

async function updateImportance(mem: MemoryDto, raw: string) {
  const parsed = Number.parseFloat(raw)
  if (Number.isNaN(parsed)) return
  const value = Math.min(1, Math.max(0, parsed))
  const res = await mutate(`/api/memories/${mem.id}`, {
    method: 'PUT',
    body: { importance: value },
  })
  if (res !== null) mem.importance = value
}

// ── Bulk deletion (mirrors the Conversations page: selection-driven Delete
// plus a separate Delete-all-matching surface; no per-row trash icons) ──────
const selectedIds = ref<Set<string>>(new Set())

const allSelected = computed(() =>
  memories.value.length > 0 && memories.value.every(m => selectedIds.value.has(m.id)))
const someSelected = computed(() => selectedIds.value.size > 0 && !allSelected.value)

function toggleSelection(id: string) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  selectedIds.value = allSelected.value ? new Set() : new Set(memories.value.map(m => m.id))
}

const deletingBulk = ref(false)

async function deleteSelected() {
  if (!selectedIds.value.size) return
  const count = selectedIds.value.size
  const ok = await confirm({
    title: 'Delete memories',
    message: `Delete ${count} memor${count === 1 ? 'y' : 'ies'}? This cannot be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  deletingBulk.value = true
  try {
    await $fetch('/api/memories', {
      method: 'DELETE',
      body: { ids: Array.from(selectedIds.value).map(Number) },
    })
    selectedIds.value = new Set()
    await refresh()
  }
  catch (e) {
    console.error('Failed to delete memories:', e)
  }
  finally {
    deletingBulk.value = false
  }
}

const deletingAll = ref(false)
const activeFilterCount = computed(() =>
  [qFilter, agentFilter, categoryFilter, importanceFilter, statusFilter]
    .filter(f => f.value).length)

/** The current filter set as the DELETE body — same predicates the list uses. */
function activeFilterPayload() {
  const out: Record<string, string> = {}
  if (qFilter.value) out.q = qFilter.value
  if (agentFilter.value) out.agent = agentFilter.value
  if (categoryFilter.value) out.category = categoryFilter.value
  if (importanceFilter.value) out.importance = importanceFilter.value
  if (statusFilter.value) out.status = statusFilter.value
  return out
}

async function deleteAll() {
  if (deletingAll.value || memories.value.length === 0) return
  const scope = activeFilterCount.value ? ' matching the active filters' : ''
  const ok = await confirm({
    title: 'Delete all memories',
    message: `Delete all memories${scope}? This cannot be undone.`,
    confirmText: 'Delete all',
    variant: 'danger',
    requireText: 'delete',
  })
  if (!ok) return
  deletingAll.value = true
  try {
    await $fetch('/api/memories', {
      method: 'DELETE',
      body: { filter: activeFilterPayload() },
    })
    selectedIds.value = new Set()
    await refresh()
  }
  catch (e) {
    console.error('Failed to delete all memories:', e)
  }
  finally {
    deletingAll.value = false
  }
}

function fileStamp(): string {
  return new Date().toISOString().replaceAll(':', '-').slice(0, 19)
}

function downloadBlob(filename: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const objectUrl = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = objectUrl
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(objectUrl)
}

function exportMemories() {
  const payload = {
    exportedAt: new Date().toISOString(),
    memoryCount: memories.value.length,
    memories: memories.value,
  }
  downloadBlob(`jclaw-memories-${fileStamp()}.json`, JSON.stringify(payload, null, 2), 'application/json')
}
</script>

<template>
  <div>
    <div class="mb-6 flex items-start justify-between">
      <div>
        <h1 class="text-lg font-semibold text-fg-strong">
          Memories
        </h1>
        <p class="mt-1 text-sm text-fg-muted">
          Durable facts your agents have captured. Search the text or filter by agent, category, or importance;
          adjust importance inline (it drives recall ranking and core-memory auto-load), or select rows to delete.
        </p>
      </div>
      <div
        v-if="memories.length > 0"
        class="flex shrink-0 items-center gap-2 pt-1"
      >
        <button
          data-testid="delete-selected"
          :disabled="!selectedIds.size || deletingBulk"
          class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          @click="deleteSelected"
        >
          {{ deletingBulk ? 'Deleting...' : `Delete${selectedIds.size ? ' ' + selectedIds.size : ''}` }}
        </button>
        <button
          data-testid="delete-all"
          :disabled="deletingAll"
          class="px-3 py-1.5 border border-red-700 text-red-600 dark:text-red-400 text-xs font-medium hover:bg-red-700 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          @click="deleteAll"
        >
          {{ deletingAll ? 'Deleting...' : `Delete all${activeFilterCount ? ' matching' : ''}` }}
        </button>
      </div>
    </div>

    <FilterBar
      storage-key="memories"
      placeholder="Filter... (e.g., q:invoice category:core importance:>0.8 agent:main status:superseded)"
      :filter-keys="['q', 'agent', 'category', 'importance', 'status']"
      class="mb-4"
      @update:filters="onFiltersChanged"
      @export="exportMemories"
    />

    <p
      v-if="fetchError"
      class="mb-4 text-sm text-red-400"
    >
      Failed to load memories.
    </p>

    <p
      v-else-if="memories.length === 0"
      data-testid="memory-empty"
      class="border border-border bg-surface-elevated px-4 py-8 text-center text-sm text-fg-muted"
    >
      No memories found.
    </p>

    <div
      v-else
      class="border border-border bg-surface-elevated"
    >
      <table
        data-testid="memory-table"
        class="w-full text-sm"
      >
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="w-10 px-4 py-2.5">
              <input
                type="checkbox"
                data-testid="select-all"
                aria-label="Select all memories"
                :checked="allSelected"
                :indeterminate="someSelected"
                class="accent-red-500 align-middle"
                title="Select all"
                @change="toggleSelectAll"
              >
            </th>
            <th
              v-for="col in SORT_COLUMNS"
              :key="col.key"
              class="px-4 py-2.5 font-medium"
              :class="col.thClass"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === col.key ? 'text-fg-strong' : ''"
                :aria-sort="sortBy === col.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                :data-testid="`sort-${col.key}`"
                @click="toggleSort(col.key)"
              >
                {{ col.label }}
                <ChevronUpIcon
                  v-if="sortBy === col.key && sortDir === 'asc'"
                  class="w-3 h-3"
                  aria-hidden="true"
                />
                <ChevronDownIcon
                  v-else-if="sortBy === col.key && sortDir === 'desc'"
                  class="w-3 h-3"
                  aria-hidden="true"
                />
              </button>
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="mem in sortedMemories"
            :key="mem.id"
            data-testid="memory-row"
            class="align-top"
            :class="{ 'opacity-50': mem.supersededAt }"
          >
            <td class="px-4 py-2.5">
              <input
                type="checkbox"
                data-testid="select-memory"
                aria-label="Select memory"
                :checked="selectedIds.has(mem.id)"
                class="accent-red-500 align-middle"
                @change="toggleSelection(mem.id)"
              >
            </td>
            <td class="whitespace-nowrap px-4 py-2.5 text-fg-muted">
              {{ mem.agentName }}
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              {{ mem.text }}
              <span
                v-if="mem.supersededAt"
                data-testid="superseded-badge"
                class="ml-2 inline-block bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700 dark:bg-gray-800 dark:text-gray-300"
                :title="supersededTitle(mem)"
              >superseded</span>
            </td>
            <td class="px-4 py-2.5">
              <span
                v-if="mem.category"
                class="inline-block px-2 py-0.5 text-xs font-medium"
                :class="categoryClass(mem.category)"
              >{{ mem.category }}</span>
            </td>
            <td class="px-4 py-2.5">
              <input
                type="number"
                min="0"
                max="1"
                step="0.05"
                :value="mem.importance"
                data-testid="importance-input"
                aria-label="Importance"
                class="w-20 border border-input bg-surface-elevated px-2 py-1 text-fg-strong"
                @change="updateImportance(mem, ($event.target as HTMLInputElement).value)"
              >
            </td>
            <td class="whitespace-nowrap px-4 py-2.5 text-fg-muted">
              {{ mem.createdAt ? formatDateTime(mem.createdAt) : '—' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
