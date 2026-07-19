<script setup lang="ts">
import type { Agent, Message } from '~/types/api'
// UsersRound matches the Subagents nav icon (the "spawned children" glyph) so
// the empty-state landing reads as the same surface.
import { UsersRound } from '@lucide/vue'

/**
 * JCLAW-271: SubagentRuns admin page. Lists every subagent run with filters
 * (parent agent, status, since), shows status pills + duration, and offers
 * a per-row kill button for RUNNING rows. The kill action POSTs to the
 * shared {@code /api/subagent-runs/{id}/kill} endpoint, which delegates to
 * the same SubagentRegistry kill primitive as the {@code /subagent kill}
 * slash command — so admin-page kills and slash kills behave identically.
 */

interface SubagentRun {
  id: number
  parentAgentId: number | null
  parentAgentName: string | null
  childAgentId: number | null
  childAgentName: string | null
  parentConversationId: number | null
  childConversationId: number | null
  mode: string | null
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'KILLED' | 'TIMEOUT'
  startedAt: string | null
  endedAt: string | null
  outcome: string | null
}

const parentAgentFilter = ref<string>('')
const statusFilter = ref<string>('')
const sinceFilter = ref<string>('')
// JCLAW-304: FTS keyword bound to the FilterBar's `q:` key. Backend
// resolves it against the SUBAGENT_RUN scope (label + outcome virtual
// doc) UNIONED with hits on the CONVERSATION_MESSAGE scope mapped back
// to runs via SubagentRun.childConversation — so a search matches when
// the term appears anywhere in the run's narrative content OR anywhere
// in its child transcript.
const qFilter = ref<string>('')
// JCLAW-326: URL-driven filter so chat (or any deep-link) can scope the
// list to subagents spawned within a specific parent conversation. There's
// no visible input — operators typically reach this filter via a link, not
// by typing a conversation id. A clearable chip surfaces the active filter
// and lets the user back out.
const route = useRoute()
const parentConversationFilter = ref<string>(
  typeof route.query.parentConversationId === 'string' ? route.query.parentConversationId : '',
)

interface Filter { key: string, value: string }
function onFiltersChanged(filters: Filter[]) {
  // Rehydrate watched refs from the bar's emitted state.
  qFilter.value = filters.find(f => f.key === 'q')?.value ?? ''
  parentAgentFilter.value = filters.find(f => f.key === 'parentAgent')?.value ?? ''
  statusFilter.value = filters.find(f => f.key === 'status')?.value ?? ''
  sinceFilter.value = filters.find(f => f.key === 'since')?.value ?? ''
  // The parentConversation key on the bar mirrors the URL-driven chip
  // for round-trip consistency; the chip's clear-button writes through
  // here too so the URL state and the bar's chip stay in lockstep.
  parentConversationFilter.value = filters.find(f => f.key === 'parentConversation')?.value ?? parentConversationFilter.value
  // A new filter set changes the result count, so jump back to page 1 and drop
  // any carried-over selection. page + the filter refs are all `url` deps, so
  // Vue batches this into a single recompute → one refetch via the watch.
  page.value = 1
  selectedIds.value = new Set()
}

const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })

// Pagination, matching the conversations page. `page` feeds the `offset`
// param inside `url`, so goto() → url change → the existing watch refetches;
// no separate load() path is needed.
const pageSize = 20
const page = ref(1)

// Server-side sort (parity with Memory/Conversations). sortBy/sortDir feed the
// `sort`/`dir` params, so a header click refetches an ordered page — the sort
// spans the whole result set, not just the current page. `mode` (from spawn
// events) and `duration` (computed) aren't DB columns, so they aren't sortable.
type SortColumn = 'id' | 'parent' | 'child' | 'status' | 'started'
const sortBy = ref<SortColumn | null>(null)
const sortDir = ref<'asc' | 'desc'>('desc')

const url = computed(() => {
  const params = new URLSearchParams()
  // JCLAW-304: q goes first as the FTS keyword; backend unions
  // SUBAGENT_RUN + child-transcript matches and intersects with the
  // equality filters below.
  if (qFilter.value) params.set('q', qFilter.value)
  if (parentAgentFilter.value) {
    // Operators type `parentAgent:main` (a name) or `parentAgent:42` (an
    // id) — resolve names to ids client-side so the backend's typed
    // Long parameter binds cleanly. Falls through verbatim when the
    // value parses as a number or matches no agent (which the backend
    // then treats as "no match", surfacing zero rows — clearer than a
    // 400 on an unresolvable name).
    const v = parentAgentFilter.value
    const asNumber = Number(v)
    if (Number.isFinite(asNumber) && Number.isInteger(asNumber)) {
      params.set('parentAgentId', String(asNumber))
    }
    else {
      const match = agentList.value?.find((a: Agent) => a.name.toLowerCase() === v.toLowerCase())
      if (match) params.set('parentAgentId', String(match.id))
    }
  }
  if (parentConversationFilter.value) params.set('parentConversationId', parentConversationFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (sinceFilter.value) {
    // Datetime-local inputs return "YYYY-MM-DDTHH:MM"; the backend wants
    // ISO-8601 with a timezone. Append :00Z so the value parses as UTC —
    // matching the timestamps the backend renders in the table.
    //
    // FilterBar emits the `since` token verbatim, so an operator typing
    // `since:2026-05-01T09:00` flows directly through this same suffix.
    // Less ideal than a typed picker but consistent with the other key
    // shapes — and the typed picker can layer on later if needed.
    params.set('since', `${sinceFilter.value}:00Z`)
  }
  if (sortBy.value) {
    params.set('sort', sortBy.value)
    params.set('dir', sortDir.value)
  }
  params.set('limit', String(pageSize))
  params.set('offset', String((page.value - 1) * pageSize))
  return `/api/subagent-runs?${params}`
})

// Plain ref + $fetch (not useFetch) so each mount fetches fresh — the
// shared useFetch cache otherwise returned stale rows when the page was
// re-opened mid-session after a separate run had finished, and confused
// the test harness which mounts the page across `it` blocks without
// invalidating the cache.
const runs = ref<SubagentRun[]>([])
// Full matching count (pre-pagination), read from the X-Total-Count header the
// list endpoint sets. `runs` holds only the current page; `total` reflects
// every row the filter matches — driving both the pager range and the "Delete
// all matching" count, neither of which can come from runs.length.
const total = ref(0)
const loading = ref(false)
async function refresh() {
  loading.value = true
  try {
    const res = await $fetch.raw<SubagentRun[]>(url.value)
    runs.value = res._data ?? []
    const headerTotal = res.headers.get('x-total-count')
    total.value = headerTotal ? Number.parseInt(headerTotal, 10) : runs.value.length
  }
  finally {
    loading.value = false
  }
}
await refresh()
watch(url, () => {
  refresh()
})

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const rangeStart = computed(() => total.value === 0 ? 0 : (page.value - 1) * pageSize + 1)
const rangeEnd = computed(() => Math.min(page.value * pageSize, total.value))

// Page navigation. Mutating `page` changes the `offset` in `url`, which the
// watch above turns into a refetch; we only clear the selection here so a
// carried-over checkbox set can't span pages.
function goto(p: number) {
  if (p < 1 || p > totalPages.value || p === page.value || loading.value) return
  page.value = p
  selectedIds.value = new Set()
}

// Header click → server-side sort. Same column flips direction; a new column
// resets to its natural default (started/id newest-first, text columns asc).
// sortBy/sortDir + page are `url` deps, so this refetches via the watch.
function toggleSort(col: SortColumn) {
  if (sortBy.value === col) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  }
  else {
    sortBy.value = col
    sortDir.value = col === 'started' || col === 'id' ? 'desc' : 'asc'
  }
  page.value = 1
  selectedIds.value = new Set()
}

function sortArrow(col: SortColumn): string {
  if (sortBy.value !== col) return ''
  return sortDir.value === 'asc' ? '↑' : '↓'
}

// Auto-refresh every 5s when there is at least one RUNNING row — keeps the
// admin view live during an actual subagent fan-out without polling
// hammering the DB once everything is terminal.
const hasRunning = computed(() => runs.value?.some(r => r.status === 'RUNNING') ?? false)
let interval: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  interval = setInterval(() => {
    if (hasRunning.value && !document.hidden) refresh()
  }, 5000)
})
onUnmounted(() => clearInterval(interval))

const statusColors: Record<string, string> = {
  RUNNING: 'bg-blue-100 dark:bg-blue-400/10 text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-400/20',
  COMPLETED: 'bg-emerald-100 dark:bg-emerald-400/10 text-emerald-700 dark:text-emerald-400 border-emerald-300 dark:border-emerald-400/20',
  FAILED: 'bg-red-100 dark:bg-red-400/10 text-red-700 dark:text-red-400 border-red-300 dark:border-red-400/20',
  KILLED: 'bg-yellow-100 dark:bg-yellow-400/10 text-yellow-700 dark:text-yellow-400 border-yellow-300 dark:border-yellow-400/20',
  TIMEOUT: 'bg-orange-100 dark:bg-orange-400/10 text-orange-700 dark:text-orange-400 border-orange-300 dark:border-orange-400/20',
}

function durationSeconds(r: SubagentRun): number | null {
  if (!r.startedAt) return null
  const start = new Date(r.startedAt).getTime()
  const end = r.endedAt ? new Date(r.endedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  return Math.max(0, Math.round((end - start) / 1000))
}

const killing = ref<Set<number>>(new Set())

async function killRun(id: number) {
  killing.value.add(id)
  try {
    await $fetch(`/api/subagent-runs/${id}/kill`, {
      method: 'POST',
      body: { reason: 'Killed by operator via admin page' },
    })
    await refresh()
  }
  catch (e) {
    console.error('Failed to kill subagent run:', e)
  }
  finally {
    killing.value.delete(id)
  }
}

// confirm() drives the "Delete all" typed-confirmation dialog below; the
// selection-driven "Delete N" runs its own confirm inside useBulkSelect.
const { confirm } = useConfirm()

/**
 * Bulk-select wiring shared with tasks.vue via useBulkSelect. The
 * RUNNING-row exclusion lives in the `selectable` predicate so the
 * "select all" header checkbox doesn't include live rows the backend
 * would reject with 409.
 */
const {
  selectedIds,
  deletingBulk,
  selectableRows: selectableRuns,
  toggle: toggleSelection,
  toggleAll: toggleSelectAll,
  deleteSelected,
} = useBulkSelect<SubagentRun>({
  rows: runs,
  selectable: r => r.status !== 'RUNNING',
  deleteOne: id => $fetch<unknown>(`/api/subagent-runs/${id}`, { method: 'DELETE' }),
  onComplete: () => refresh(),
  confirmCopy: count => ({
    title: 'Delete subagent runs',
    message: `Permanently delete ${count} subagent run${count === 1 ? '' : 's'} along with their child agents and transcripts? This cannot be undone.`,
  }),
})

// Always-on selection (mirrors the conversations page): the checkbox column is
// always visible rather than gated behind a select-mode toggle. RUNNING rows
// stay out of the candidate set (selectableRuns) so "select all" never picks a
// live run the backend would reject with 409.
const allRunsSelected = computed(() => selectableRuns.value.length > 0 && selectedIds.value.size === selectableRuns.value.length)
const someRunsSelected = computed(() => selectedIds.value.size > 0 && !allRunsSelected.value)

const deletingAll = ref(false)

/**
 * Filter object for the DELETE /api/subagent-runs body. Mirrors the param
 * construction in the `url` computed exactly (name→id resolution, the `:00Z`
 * since suffix) so "Delete all matching" wipes precisely the rows the list
 * shows. RUNNING rows the filter still matches are skipped server-side.
 */
function activeFilterPayload(): { parentAgentId?: number, parentConversationId?: number, status?: string, since?: string, q?: string } {
  const out: { parentAgentId?: number, parentConversationId?: number, status?: string, since?: string, q?: string } = {}
  if (qFilter.value) out.q = qFilter.value
  if (parentAgentFilter.value) {
    const v = parentAgentFilter.value
    const asNumber = Number(v)
    if (Number.isFinite(asNumber) && Number.isInteger(asNumber)) out.parentAgentId = asNumber
    else {
      const match = agentList.value?.find((a: Agent) => a.name.toLowerCase() === v.toLowerCase())
      if (match) out.parentAgentId = match.id
    }
  }
  if (parentConversationFilter.value) out.parentConversationId = Number(parentConversationFilter.value)
  if (statusFilter.value) out.status = statusFilter.value
  if (sinceFilter.value) out.since = `${sinceFilter.value}:00Z`
  return out
}

/** Human-readable echo of the active filters for the confirm message. */
function activeFilterDescription(): string {
  const parts: string[] = []
  if (qFilter.value) parts.push(`q:${qFilter.value}`)
  if (parentAgentFilter.value) parts.push(`parentAgent:${parentAgentFilter.value}`)
  if (parentConversationFilter.value) parts.push(`conversation:#${parentConversationFilter.value}`)
  if (statusFilter.value) parts.push(`status:${statusFilter.value}`)
  if (sinceFilter.value) parts.push(`since:${sinceFilter.value}`)
  return parts.join(' ')
}

const hasActiveFilters = computed(() =>
  !!(qFilter.value || parentAgentFilter.value || parentConversationFilter.value || statusFilter.value || sinceFilter.value),
)

// Welcome-style landing (mirrors the conversations page): shown only when the
// list is genuinely empty — zero runs AND no active filter. With a filter
// active, keep the filter bar + table so the "no matches" message stands and
// the operator can adjust the filter. !loading guards against a flash before
// the first fetch resolves.
const hasNoData = computed(() => !loading.value && total.value === 0 && !hasActiveFilters.value)

/**
 * Wipe every terminal run matching the active filter (or the whole table when
 * unfiltered). Separate destructive surface from "Delete N": filter-scoped,
 * so it reaches rows beyond the 200-row page cap, and gated behind a typed
 * 'delete' confirmation because the blast radius is the full matching set.
 */
async function deleteAll() {
  if (deletingAll.value || total.value <= 0) return
  const filterDesc = activeFilterDescription()
  const scope = filterDesc ? ` matching ${filterDesc}` : ''
  const ok = await confirm({
    title: 'Delete all subagent runs',
    message: `Delete all ${total.value} subagent run${total.value === 1 ? '' : 's'}${scope} along with their child agents and transcripts? Running rows are skipped. This cannot be undone.`,
    confirmText: `Delete ${total.value}`,
    variant: 'danger',
    requireText: 'delete',
  })
  if (!ok) return
  deletingAll.value = true
  try {
    await $fetch('/api/subagent-runs', {
      method: 'DELETE',
      body: { filter: activeFilterPayload() },
    })
    selectedIds.value = new Set()
    page.value = 1
    await refresh()
  }
  catch (e) {
    console.error('Failed to delete all subagent runs:', e)
  }
  finally {
    deletingAll.value = false
  }
}

// ── Quick-preview peek (mirrors the conversations page) ───────────────
// A run's transcript IS its child conversation, so the peek fetches the
// child conversation's messages. The "View transcript" eye-link opens the
// full chat viewer; this peek is the inline read-without-leaving glance.
const peekOpen = ref(false)
const selectedRun = ref<SubagentRun | null>(null)
const peekMessages = ref<Message[]>([])

async function selectRun(run: SubagentRun) {
  selectedRun.value = run
  peekOpen.value = true
  peekMessages.value = run.childConversationId === null
    ? []
    : (await $fetch<Message[]>(`/api/conversations/${run.childConversationId}/messages`) ?? [])
}

function closePeek() {
  peekOpen.value = false
  // Keep selectedRun so re-opening preserves the last selection.
}

// (JCLAW-304: select-input ids were retired when the three dropdowns
// were replaced with FilterBar. FilterBar manages its own ARIA labels
// on the underlying input.)
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Subagents
      </h1>
      <div
        v-if="!hasNoData"
        class="flex items-center gap-2"
      >
        <!-- Selection-driven bulk delete, matching the conversations page's
             always-visible "Delete N" button. -->
        <button
          :disabled="!selectedIds.size || deletingBulk"
          class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          @click="deleteSelected"
        >
          {{ deletingBulk ? 'Deleting...' : `Delete${selectedIds.size ? ' ' + selectedIds.size : ''}` }}
        </button>
        <!--
          "Delete all" mirrors the conversations page: a separate destructive
          surface from selection-driven "Delete N". Only shown when the match
          count is greater than zero; wipes only the matching subset when a
          filter is active, and the confirm dialog echoes that scope before the
          user types 'delete' to commit.
        -->
        <button
          v-if="total > 0"
          :disabled="deletingAll"
          class="px-3 py-1.5 border border-red-700 text-red-700 dark:text-red-400 text-xs font-medium hover:bg-red-700 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          @click="deleteAll"
        >
          {{ deletingAll ? 'Deleting...' : `Delete all${hasActiveFilters ? ' matching' : ''}` }}
        </button>
      </div>
    </div>

    <!-- Empty-state landing: zero runs AND no active filter. Mirrors the
         conversations page — hides the filter bar / table / pager so the page
         reads as a first-run explainer rather than an empty data view. The
         "no rows after a filter" case keeps the chrome below and uses the
         table's own "No subagent runs matching filters" message. -->
    <section
      v-if="hasNoData"
      class="rounded-lg border border-dashed border-zinc-300 bg-zinc-50 px-6 py-12 text-center dark:border-zinc-700 dark:bg-zinc-900/30"
    >
      <UsersRound class="mx-auto h-10 w-10 text-zinc-400" />
      <h2 class="mt-3 text-sm font-medium text-zinc-700 dark:text-zinc-300">
        No subagent runs yet
      </h2>
      <p class="mt-1 text-sm text-zinc-500 dark:text-zinc-500">
        <!-- Link text sits flush against the tags so Vue's whitespace
             condensing doesn't leak a space before the comma. -->
        Runs appear here when an agent delegates part of a task to a child agent — via the spawn tool or a <NuxtLink
          to="/chat"
          class="font-medium text-emerald-700 underline-offset-2 hover:underline dark:text-emerald-400"
        >/subagent command in chat</NuxtLink>. Each run's status, transcript, and outcome shows up in this list.
      </p>
    </section>

    <!-- Filter bar. JCLAW-304 replaces the three select dropdowns
         (parent agent, status, started-after) with a FilterBar
         consistent with /conversations and /tasks. The bar's `q:`
         keyword drives FTS via the SUBAGENT_RUN scope UNIONED with
         child-transcript matches from the CONVERSATION_MESSAGE scope;
         `parentAgent:`, `status:`, `since:` are equality keys the
         backend intersects with the FTS hit set. The URL-driven
         parentConversation chip below is preserved as a separate
         visual element so deep links from chat still surface their
         filter unambiguously. -->
    <div
      v-if="!hasNoData"
      class="flex flex-wrap gap-3 mb-4"
    >
      <div class="flex-1 min-w-[280px]">
        <FilterBar
          storage-key="subagents"
          placeholder="Filter... (e.g., q:radarr status:COMPLETED parentAgent:42)"
          :filter-keys="['q', 'parentAgent', 'status', 'since', 'parentConversation']"
          @update:filters="onFiltersChanged"
        />
      </div>
      <!--
        JCLAW-326: parent-conversation filter chip. Surfaces the URL-driven
        parentConversationId filter when active so the operator can see
        what's narrowing the list and clear it without editing the URL.
        Hidden when no filter is set; no visible input form because
        operators don't type conversation ids — they arrive here via a
        deep-link.
      -->
      <div
        v-if="parentConversationFilter"
        class="inline-flex items-center gap-2 bg-muted border border-input text-sm text-fg-strong px-2 py-1"
      >
        <span class="text-xs text-fg-muted">Conversation</span>
        <span class="font-mono">#{{ parentConversationFilter }}</span>
        <button
          type="button"
          class="text-fg-muted hover:text-fg-strong text-xs leading-none"
          aria-label="Clear conversation filter"
          @click="parentConversationFilter = ''"
        >
          ×
        </button>
      </div>
    </div>

    <!-- Table -->
    <div
      v-if="!hasNoData"
      class="bg-surface-elevated border border-border"
    >
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="px-4 py-2.5 font-medium w-8">
              <input
                type="checkbox"
                :checked="allRunsSelected"
                :indeterminate.prop="someRunsSelected"
                :disabled="!selectableRuns.length"
                class="accent-red-500 align-middle"
                aria-label="Select all deletable runs on this page"
                @change="toggleSelectAll"
              >
            </th>
            <th
              class="px-4 py-2.5 font-medium"
              :aria-sort="sortBy === 'id' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === 'id' ? 'text-fg-strong' : ''"
                @click="toggleSort('id')"
              >
                ID <span
                  v-if="sortArrow('id')"
                  aria-hidden="true"
                >{{ sortArrow('id') }}</span>
              </button>
            </th>
            <th
              class="px-4 py-2.5 font-medium"
              :aria-sort="sortBy === 'parent' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === 'parent' ? 'text-fg-strong' : ''"
                @click="toggleSort('parent')"
              >
                Parent <span
                  v-if="sortArrow('parent')"
                  aria-hidden="true"
                >{{ sortArrow('parent') }}</span>
              </button>
            </th>
            <th
              class="px-4 py-2.5 font-medium"
              :aria-sort="sortBy === 'child' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === 'child' ? 'text-fg-strong' : ''"
                @click="toggleSort('child')"
              >
                Child <span
                  v-if="sortArrow('child')"
                  aria-hidden="true"
                >{{ sortArrow('child') }}</span>
              </button>
            </th>
            <th class="px-4 py-2.5 font-medium">
              Mode
            </th>
            <th
              class="px-4 py-2.5 font-medium"
              :aria-sort="sortBy === 'status' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === 'status' ? 'text-fg-strong' : ''"
                @click="toggleSort('status')"
              >
                Status <span
                  v-if="sortArrow('status')"
                  aria-hidden="true"
                >{{ sortArrow('status') }}</span>
              </button>
            </th>
            <th
              class="px-4 py-2.5 font-medium"
              :aria-sort="sortBy === 'started' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
            >
              <button
                type="button"
                class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                :class="sortBy === 'started' ? 'text-fg-strong' : ''"
                @click="toggleSort('started')"
              >
                Started <span
                  v-if="sortArrow('started')"
                  aria-hidden="true"
                >{{ sortArrow('started') }}</span>
              </button>
            </th>
            <th class="px-4 py-2.5 font-medium">
              Duration
            </th>
            <th class="px-4 py-2.5 font-medium text-right">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="run in runs"
            :key="run.id"
            class="hover:bg-muted/30 transition-colors"
          >
            <td class="px-4 py-2.5 w-8">
              <input
                v-if="run.status !== 'RUNNING'"
                type="checkbox"
                :checked="selectedIds.has(run.id)"
                class="accent-red-500 align-middle"
                :aria-label="`Select run #${run.id}`"
                @change="toggleSelection(run.id)"
              >
            </td>
            <td class="px-4 py-2.5 font-mono text-xs text-fg-muted">
              #{{ run.id }}
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              {{ run.parentAgentName || '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              {{ run.childAgentName || '—' }}
            </td>
            <td class="px-4 py-2.5">
              <span
                v-if="run.mode"
                class="inline-block -ml-1.5 font-mono text-xs bg-muted px-1.5 py-0.5 text-fg-primary"
              >{{ run.mode }}</span>
              <span
                v-else
                class="text-fg-muted"
              >—</span>
            </td>
            <td class="px-4 py-2.5">
              <span
                :class="statusColors[run.status]"
                class="inline-flex items-center gap-1 -ml-1.5 text-[10px] font-mono uppercase tracking-wide px-1.5 py-0.5 border"
              >
                <span
                  v-if="run.status === 'RUNNING'"
                  class="w-1.5 h-1.5 rounded-full bg-blue-500 animate-pulse"
                  aria-hidden="true"
                />
                {{ run.status }}
              </span>
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">
              {{ run.startedAt ? new Date(run.startedAt).toLocaleString() : '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs font-mono">
              <template v-if="durationSeconds(run) !== null">
                {{ durationSeconds(run) }}s
              </template>
              <template v-else>
                —
              </template>
            </td>
            <td class="px-4 py-2.5">
              <div class="flex items-center justify-end gap-1">
                <!--
                  JCLAW-274: "View transcript" opens the child conversation in the
                  standard chat viewer. Icon-only (eye) to match the conversations
                  page's action column. Inline-mode runs share
                  parentConversationId == childConversationId — the link still
                  works there (it lands back at the parent), no special-casing.
                -->
                <NuxtLink
                  v-if="run.childConversationId !== null"
                  :to="`/chat?conversation=${run.childConversationId}`"
                  class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
                  title="View transcript"
                  @click.stop
                >
                  <svg
                    class="w-4 h-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.5"
                      d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z"
                    />
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.5"
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                    />
                  </svg>
                </NuxtLink>
                <!--
                  Quick preview — slide-in peek of the child transcript without
                  leaving the page. Icon matches the conversations page's
                  "Quick preview" (split-panel glyph). The eye-link above opens
                  the full chat viewer; this is the inline glance.
                -->
                <button
                  v-if="run.childConversationId !== null"
                  type="button"
                  class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
                  title="Quick preview"
                  @click.stop="selectRun(run)"
                >
                  <svg
                    class="w-4 h-4"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.5"
                      d="M4 4h16v16H4z M14 4v16"
                    />
                  </svg>
                </button>
                <!--
                  Kill is the only per-row destructive action left: RUNNING rows
                  can't be checkbox-selected (they're excluded from the delete
                  set), so they need their own affordance. Terminal rows are
                  deleted via the checkbox + "Delete N" toolbar button.
                -->
                <button
                  v-if="run.status === 'RUNNING'"
                  :disabled="killing.has(run.id)"
                  class="p-1.5 rounded text-red-600 dark:text-red-400 hover:bg-red-500/10 hover:text-red-500 transition-colors disabled:opacity-40"
                  :title="killing.has(run.id) ? 'Killing…' : 'Kill run'"
                  :aria-label="killing.has(run.id) ? 'Killing run' : 'Kill run'"
                  @click.stop="killRun(run.id)"
                >
                  <!-- Stop-circle glyph: destructive/active action, red-highlighted
                       and set apart from the muted view/preview icons. Pulses while
                       the kill is in flight. -->
                  <svg
                    class="w-4 h-4"
                    :class="killing.has(run.id) ? 'animate-pulse' : ''"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                  >
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.5"
                      d="M9 9.563C9 9.252 9.252 9 9.563 9h4.874c.311 0 .563.252.563.563v4.874c0 .311-.252.563-.563.563H9.564A.562.562 0 019 14.437V9.564z"
                    />
                    <path
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.5"
                      d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                    />
                  </svg>
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div
        v-if="!runs?.length"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No subagent runs matching filters
      </div>
      <div
        v-if="total > 0"
        class="flex items-center justify-between px-4 py-2.5 border-t border-border text-xs text-fg-muted"
      >
        <span>Showing {{ rangeStart }}–{{ rangeEnd }} of {{ total }}</span>
        <div class="flex items-center gap-1">
          <button
            :disabled="page <= 1 || loading"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            @click="goto(page - 1)"
          >
            Prev
          </button>
          <span class="px-2">Page {{ page }} of {{ totalPages }}</span>
          <button
            :disabled="page >= totalPages || loading"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            @click="goto(page + 1)"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <!-- Quick-preview peek: the run's child transcript, read without leaving
         the page. Mirrors the conversations page's PeekPanel. -->
    <PeekPanel
      :open="peekOpen"
      :title="selectedRun ? `Run #${selectedRun.id} · ${selectedRun.childAgentName || 'child'}` : 'Subagent run'"
      :description="selectedRun ? `${selectedRun.parentAgentName || '—'} → ${selectedRun.childAgentName || '—'} · ${selectedRun.status}` : ''"
      @update:open="closePeek"
    >
      <template v-if="selectedRun">
        <div class="flex flex-wrap gap-x-6 gap-y-1 text-xs text-fg-muted mb-4">
          <span>Mode: <strong class="text-fg-primary font-mono">{{ selectedRun.mode || '—' }}</strong></span>
          <span>Status: <strong class="text-fg-primary">{{ selectedRun.status }}</strong></span>
          <span>Started: <strong class="text-fg-primary">{{ selectedRun.startedAt ? new Date(selectedRun.startedAt).toLocaleString() : '—' }}</strong></span>
          <span v-if="durationSeconds(selectedRun) !== null">Duration: <strong class="text-fg-primary font-mono">{{ durationSeconds(selectedRun) }}s</strong></span>
        </div>
        <div
          v-if="selectedRun.outcome"
          class="mb-4 bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap"
        >
          {{ selectedRun.outcome }}
        </div>
        <div class="space-y-3">
          <div
            v-for="msg in peekMessages"
            :key="msg.id"
            :class="msg.role === 'user' ? 'ml-12' : msg.role === 'tool' ? 'ml-6' : ''"
          >
            <div class="flex items-center gap-2 mb-0.5">
              <span class="text-xs font-mono text-fg-muted">{{ msg.role }}</span>
              <span class="text-xs text-fg-muted">{{ new Date(msg.createdAt).toLocaleTimeString() }}</span>
            </div>
            <div class="bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap">
              {{ msg.content || '(tool call)' }}
            </div>
          </div>
          <div
            v-if="!peekMessages.length"
            class="text-sm text-fg-muted"
          >
            No transcript messages yet.
          </div>
        </div>
      </template>
    </PeekPanel>
  </div>
</template>
