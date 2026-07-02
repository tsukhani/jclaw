<script setup lang="ts">
import { TrashIcon } from '@heroicons/vue/24/outline'

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
const { data: memoriesData, error: fetchError } = await useFetch<MemoryDto[]>(url)
const memories = computed(() => memoriesData.value ?? [])

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

async function remove(mem: MemoryDto) {
  const ok = await confirm({
    title: 'Delete memory',
    message: `Delete this memory?\n\n"${mem.text}"`,
    variant: 'danger',
    confirmText: 'Delete',
  })
  if (!ok) return
  const res = await mutate(`/api/memories/${mem.id}`, { method: 'DELETE' })
  if (res !== null) memoriesData.value = memories.value.filter(m => m.id !== mem.id)
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
    <div class="mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Memories
      </h1>
      <p class="mt-1 text-sm text-fg-muted">
        Durable facts your agents have captured. Search the text or filter by agent, category, or importance;
        adjust importance inline (it drives recall ranking and core-memory auto-load), or delete entries.
      </p>
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
            <th class="px-4 py-2.5 font-medium">
              Agent
            </th>
            <th class="px-4 py-2.5 font-medium">
              Memory
            </th>
            <th class="px-4 py-2.5 font-medium">
              Category
            </th>
            <th class="w-32 px-4 py-2.5 font-medium">
              Importance
            </th>
            <th class="px-4 py-2.5 font-medium">
              Created
            </th>
            <th class="w-12 px-4 py-2.5 text-right font-medium" />
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
            <td class="px-4 py-2.5 text-right">
              <button
                type="button"
                title="Delete memory"
                data-testid="delete-memory"
                class="text-fg-muted transition-colors hover:text-red-400"
                @click="remove(mem)"
              >
                <TrashIcon class="h-5 w-5" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
