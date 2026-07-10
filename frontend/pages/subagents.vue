<script setup lang="ts">
import type { Agent, Message } from '~/types/api'

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
}

const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })

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
  params.set('limit', '200')
  return `/api/subagent-runs?${params}`
})

// Plain ref + $fetch (not useFetch) so each mount fetches fresh — the
// shared useFetch cache otherwise returned stale rows when the page was
// re-opened mid-session after a separate run had finished, and confused
// the test harness which mounts the page across `it` blocks without
// invalidating the cache.
const runs = ref<SubagentRun[]>([])
// Full matching count (pre-pagination), read from the same X-Total-Count
// header the list endpoint sets. `runs` is capped at limit=200 but `total`
// reflects every row the filter matches — which is what "Delete all matching"
// wipes, so the button's label and the confirm count must come from here, not
// runs.length.
const total = ref(0)
async function refresh() {
  const res = await $fetch.raw<SubagentRun[]>(url.value)
  runs.value = res._data ?? []
  const headerTotal = res.headers.get('x-total-count')
  total.value = headerTotal ? Number.parseInt(headerTotal, 10) : runs.value.length
}
await refresh()
watch(url, () => {
  refresh()
})

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
  peekMessages.value = run.childConversationId !== null
    ? (await $fetch<Message[]>(`/api/conversations/${run.childConversationId}/messages`) ?? [])
    : []
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
      <div class="flex items-center gap-2">
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
    <div class="flex flex-wrap gap-3 mb-4">
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
    <div class="bg-surface-elevated border border-border">
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
            <th class="px-4 py-2.5 font-medium">
              ID
            </th>
            <th class="px-4 py-2.5 font-medium">
              Parent
            </th>
            <th class="px-4 py-2.5 font-medium">
              Child
            </th>
            <th class="px-4 py-2.5 font-medium">
              Mode
            </th>
            <th class="px-4 py-2.5 font-medium">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium">
              Started
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
                class="font-mono text-xs bg-muted px-1.5 py-0.5 text-fg-primary"
              >{{ run.mode }}</span>
              <span
                v-else
                class="text-fg-muted"
              >—</span>
            </td>
            <td class="px-4 py-2.5">
              <span
                :class="statusColors[run.status]"
                class="inline-flex items-center gap-1 text-[10px] font-mono uppercase tracking-wide px-1.5 py-0.5 border"
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
                  class="text-xs text-red-700 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 transition-colors disabled:opacity-40"
                  @click.stop="killRun(run.id)"
                >
                  {{ killing.has(run.id) ? 'Killing...' : 'Kill' }}
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
