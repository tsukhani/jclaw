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
  // A new filter set changes the count, so jump back to page 1 and drop any
  // carried-over selection (it could reference rows off the new page).
  page.value = 1
  selectedIds.value = new Set()
}

// Server-side sort + pagination (matches Conversations/Subagents). sortBy/
// sortDir and page all feed the reactive `url`, so a header click or page
// change refetches — the sort spans the whole result set, not just the page,
// and totals come from the X-Total-Count header. Declared above `url` because
// the top-level `await useFetch(url)` reads the computed during setup.
type SortColumn = 'agent' | 'text' | 'category' | 'importance' | 'created'
type SortDir = 'asc' | 'desc'
const sortBy = ref<SortColumn | null>(null)
const sortDir = ref<SortDir>('asc')
const pageSize = 20
const page = ref(1)

// Filter + sort params shared by the paginated list URL and the export (which
// pulls the whole matching set, not just the visible page).
function filterParams(): URLSearchParams {
  const params = new URLSearchParams()
  if (qFilter.value) params.set('q', qFilter.value)
  if (agentFilter.value) params.set('agent', agentFilter.value)
  if (categoryFilter.value) params.set('category', categoryFilter.value)
  if (importanceFilter.value) params.set('importance', importanceFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (sortBy.value) {
    params.set('sort', sortBy.value)
    params.set('dir', sortDir.value)
  }
  return params
}

const url = computed(() => {
  const params = filterParams()
  params.set('limit', String(pageSize))
  params.set('offset', String((page.value - 1) * pageSize))
  return `/api/memories?${params}`
})

function supersededTitle(mem: MemoryDto): string {
  const when = mem.supersededAt ? formatDateTime(mem.supersededAt) : ''
  const by = mem.supersededById ? ` by memory #${mem.supersededById}` : ''
  return `Superseded${by} at ${when} — excluded from recall`
}

// $fetch.raw + an explicit watch on the reactive URL (rather than useFetch) so
// the X-Total-Count header is readable off the response — the pager total comes
// from there. Matches the Conversations/Subagents fetch shape, and re-fetches
// on any filter / sort / page change. Top-level await resolves data before the
// first render (and for mountSuspended in tests).
const memoriesData = ref<MemoryDto[]>([])
const total = ref(0)
const fetchError = ref(false)
async function refresh() {
  try {
    const res = await $fetch.raw<MemoryDto[]>(url.value)
    memoriesData.value = res._data ?? []
    const h = res.headers.get('x-total-count')
    total.value = h == null ? memoriesData.value.length : Number.parseInt(h, 10)
    fetchError.value = false
  }
  catch (e) {
    console.error('Failed to load memories:', e)
    fetchError.value = true
  }
}
await refresh()
watch(url, () => {
  refresh()
})
const memories = computed(() => memoriesData.value)

// ── Column sort (server-side) ────────────────────────────────────────────────
// sortBy/sortDir (declared above `url`) drive the `sort`/`dir` query params, so
// clicking a header refetches an ordered page from the backend — the sort spans
// the whole result set, not just the current page. sortBy=null keeps the
// server's recency default; clicking a header sorts by it, clicking the same
// header flips direction, clicking a different one resets to that column's
// natural default (importance/created descending; text columns ascending).
const SORT_COLUMNS: { key: SortColumn, label: string, thClass: string }[] = [
  { key: 'agent', label: 'Agent', thClass: '' },
  { key: 'text', label: 'Memory', thClass: '' },
  { key: 'category', label: 'Category', thClass: '' },
  { key: 'importance', label: 'Importance', thClass: 'w-32' },
  { key: 'created', label: 'Created', thClass: '' },
]

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
  // Re-sorting reorders the whole set; go back to page 1 so the top of the new
  // ordering is what shows. (sortBy/sortDir/page are all `url` deps → one refetch.)
  page.value = 1
  selectedIds.value = new Set()
}

// ── Pagination (mirrors Conversations/Subagents) ─────────────────────────────
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const rangeStart = computed(() => total.value === 0 ? 0 : (page.value - 1) * pageSize + 1)
const rangeEnd = computed(() => Math.min(page.value * pageSize, total.value))

function goto(p: number) {
  if (p < 1 || p > totalPages.value || p === page.value) return
  page.value = p
  selectedIds.value = new Set()
}

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
  if (deletingAll.value || total.value === 0) return
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
    page.value = 1
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

async function exportMemories() {
  // Export the whole matching set, not just the visible page. Reuses the list
  // filters/sort and pulls up to the backend's cap in one request.
  const params = filterParams()
  params.set('limit', '500')
  const all = await $fetch<MemoryDto[]>(`/api/memories?${params}`) ?? []
  const payload = {
    exportedAt: new Date().toISOString(),
    memoryCount: all.length,
    memories: all,
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
          class="px-3 py-1.5 border border-red-700 text-red-700 dark:text-red-400 text-xs font-medium hover:bg-red-700 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
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
      class="mb-4 text-sm text-red-700 dark:text-red-400"
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
              scope="col"
              class="px-4 py-2.5 font-medium"
              :class="col.thClass"
              :aria-sort="sortBy === col.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === col.key ? 'text-fg-strong' : ''"
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
            v-for="mem in memories"
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
      <div
        v-if="total > 0"
        class="flex items-center justify-between px-4 py-2.5 border-t border-border text-xs text-fg-muted"
      >
        <span>Showing {{ rangeStart }}–{{ rangeEnd }} of {{ total }}</span>
        <div class="flex items-center gap-1">
          <button
            :disabled="page <= 1"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            @click="goto(page - 1)"
          >
            Prev
          </button>
          <span class="px-2">Page {{ page }} of {{ totalPages }}</span>
          <button
            :disabled="page >= totalPages"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            @click="goto(page + 1)"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
