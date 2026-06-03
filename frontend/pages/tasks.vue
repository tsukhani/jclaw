<script setup lang="ts">
import type { Task, TaskRunView, TaskRunMessageView, TranscriptSearchHit, TaskStats, RecentRunView } from '~/types/api'
import {
  ArrowDownIcon,
  ArrowPathIcon,
  ArrowUpIcon,
  BoltIcon,
  CalendarDaysIcon,
  CheckIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  NoSymbolIcon,
  PauseIcon,
  PencilSquareIcon,
  PlayIcon,
  PlusIcon,
  StopIcon,
  TableCellsIcon,
  TrashIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

// JCLAW-304: FilterBar-driven state. The legacy status/type select refs
// stay as live refs so the table's existing computed paths (e.g. the
// status-pill renderer and the calendar's per-fire projection) keep
// working unchanged — onFiltersChanged keeps them in sync with the bar.
const statusFilter = ref('')
const typeFilter = ref('')
const qFilter = ref('')
const agentFilter = ref('')

interface Filter { key: string, value: string }
function onFiltersChanged(filters: Filter[]) {
  // Rehydrate each watched filter ref from the bar's emitted state.
  // Missing keys reset to empty string so removing a chip clears the
  // backend predicate, not leaves it stale.
  qFilter.value = filters.find(f => f.key === 'q')?.value ?? ''
  statusFilter.value = filters.find(f => f.key === 'status')?.value ?? ''
  typeFilter.value = filters.find(f => f.key === 'type')?.value ?? ''
  agentFilter.value = filters.find(f => f.key === 'agent')?.value ?? ''
  // Transcript search rides the same bar via a `transcript:` token (distinct
  // Lucene scope: task_run_message bodies, not the task name/description that
  // `q:` searches). A non-empty token runs the search and shows the hit panel;
  // removing the chip clears it. Multi-word queries are quoted in the bar
  // (transcript:"send the invoice") and reach the backend FTS verbatim, so
  // AND/OR/NOT and prefix* all work.
  const tq = filters.find(f => f.key === 'transcript')?.value ?? ''
  transcriptQuery.value = tq
  if (tq) void searchTranscripts()
  else clearTranscriptSearch()
}

// Two layouts: dense table (default) and a navigable calendar (month / week /
// day granularity). Persisted to the URL so refresh / back-forward / shareable
// links keep the same view.
type View = 'table' | 'calendar'
const route = useRoute()
const router = useRouter()
const view = computed<View>({
  get() {
    return route.query.view === 'calendar' ? 'calendar' : 'table'
  },
  set(v: View) {
    void router.replace({ query: { ...route.query, view: v === 'table' ? undefined : v } })
  },
})

const url = computed(() => {
  const params = new URLSearchParams()
  // JCLAW-304: q is the FTS keyword; backend resolves it against the
  // TASK Lucene scope (name + description virtual doc) and intersects
  // with the equality predicates below.
  if (qFilter.value) params.set('q', qFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (typeFilter.value) params.set('type', typeFilter.value)
  if (agentFilter.value) params.set('agent', agentFilter.value)
  // Reminders live on their own /reminders page (different mental model:
  // personal nudges vs background automation). Filter them out here so
  // the Tasks list stays focused on automation work.
  params.set('excludePayloadType', 'reminder')
  params.set('limit', '50')
  return `/api/tasks?${params}`
})

const { data: tasks, refresh } = await useFetch<Task[]>(url)
// JCLAW-22 (slice K): dashboard KPI aggregate. Refetched live on task
// lifecycle events (see scheduleLiveRefresh) so the counts stay current.
const { data: stats, refresh: refreshStats } = await useFetch<TaskStats>('/api/tasks/stats')
// JCLAW-22: the calendar's week/day grids place real run blocks onto an hourly
// axis. We fetch the runs that fall inside the currently-visible calendar range
// (driven by the granularity + anchor below) and refetch reactively as the
// operator navigates. The range computeds live here — above the fetch — so the
// URL ref can key on them (const has no hoisting); the navigation handlers and
// the per-day projection live further down with the rest of the grid logic.
const calGranularity = ref<'month' | 'week' | 'day'>('month')
const calendarMonth = ref(startOfMonth(new Date()))
const calAnchor = ref(startOfDay(new Date()))
const calRange = computed<{ start: Date, end: Date }>(() => {
  if (calGranularity.value === 'day') {
    const s = startOfDay(calAnchor.value)
    return { start: s, end: addDays(s, 1) }
  }
  if (calGranularity.value === 'week') {
    const s = startOfWeek(calAnchor.value)
    return { start: s, end: addDays(s, 7) }
  }
  // Month: the 6×7 grid window (Sunday on/before the 1st, 42 days) — matches
  // calendarDays so a run/fire never lands outside the visible cells.
  const gridStart = startOfWeek(calendarMonth.value)
  return { start: gridStart, end: addDays(gridStart, 42) }
})
const calRunsUrl = computed(() =>
  `/api/task-runs/recent?from=${encodeURIComponent(calRange.value.start.toISOString())}`
  + `&to=${encodeURIComponent(calRange.value.end.toISOString())}&limit=500`)
const { data: recentRuns, refresh: refreshRecent } = await useFetch<RecentRunView[]>(calRunsUrl)
const { mutate } = useApiMutation()
const { confirm } = useConfirm()

// JCLAW-259: surface the retention TTL in the page header so operators
// know when terminal tasks will auto-delete. Read the single config key
// rather than the whole /api/config list to keep the request cheap.
// Reactive on visit only — the TaskCleanupJob runs daily off the chat
// hot path so the value doesn't change mid-session; if the operator
// updates it in Settings, navigating back here refreshes naturally.
interface RetentionConfigEntry { value?: string }
const retentionConfig = await useFetch<RetentionConfigEntry>('/api/config/tasks.retentionDays', {
  // The endpoint returns 404 when the key is absent — that's the
  // "use default" case, not an error. Suppress the throw so the page
  // still renders.
  default: (): RetentionConfigEntry => ({}),
  // Don't propagate the 404 to the page-level error boundary; surface
  // it as an empty payload so the computed below resolves to the
  // default-days branch.
  onResponseError: ({ response }) => { if (response.status === 404) response._data = {} },
})

const retentionDisplay = computed(() => {
  const raw = retentionConfig.data.value?.value
  const days = raw == null || raw === '' ? 30 : Number.parseInt(raw, 10)
  if (!Number.isFinite(days)) return 'Retention: 30 days (default)'
  if (days === 0) return 'Retention: disabled — terminal tasks kept forever'
  return `Retention: ${days} day${days === 1 ? '' : 's'}`
})

/**
 * Bulk-select wiring shared with subagents.vue (and the seeded
 * agents.vue pattern). The composable owns selectMode, selectedIds,
 * deletingBulk, enter/exit/toggle/toggleAll, and the confirmation
 * sweep — this page only needs to pin the rows source, the per-id
 * delete endpoint, and the confirm copy.
 */
const {
  selectMode,
  selectedIds,
  deletingBulk,
  selectableRows,
  enter: enterSelectMode,
  exit: exitSelectMode,
  toggle: toggleSelection,
  toggleAll: toggleSelectAll,
  deleteSelected,
} = useBulkSelect<Task>({
  rows: tasks,
  deleteOne: id => $fetch<unknown>(`/api/tasks/${id}`, { method: 'DELETE' }),
  onComplete: () => refresh(),
  confirmCopy: count => ({
    title: 'Delete tasks',
    message: `Permanently delete ${count} task${count === 1 ? '' : 's'} and their run history? This cannot be undone.`,
  }),
})

async function cancelTask(id: number) {
  await mutate(`/api/tasks/${id}/cancel`, { method: 'POST' })
  refresh()
}

// Pause/resume — the reversible suspend for recurring tasks. Pause keeps the
// scheduler row (cadence preserved); the backend skips the fire body while
// paused, and resume clears the flag so the next scheduled fire runs.
async function pauseTask(id: number) {
  await mutate(`/api/tasks/${id}/pause`, { method: 'POST' })
  refresh()
}

async function resumeTask(id: number) {
  await mutate(`/api/tasks/${id}/resume`, { method: 'POST' })
  refresh()
}

// Re-enable — restore a CANCELLED task's schedule at its next natural fire
// (no immediate run for CRON). The one-off counterpart to resume.
async function reenableTask(id: number) {
  await mutate(`/api/tasks/${id}/reenable`, { method: 'POST' })
  refresh()
}

async function retryTask(id: number) {
  await mutate(`/api/tasks/${id}/retry`, { method: 'POST' })
  refresh()
}

// Run now — fire a recurring task's next occurrence immediately, out of band.
// The backend reschedules the existing scheduler row to now (TaskSchedulingService
// .runNow), so the cron/interval cadence is untouched; gated to !paused below
// because a paused task's fire body is skipped, which would make this a no-op.
async function runNowTask(id: number) {
  await mutate(`/api/tasks/${id}/run`, { method: 'POST' })
  refresh()
}

// Cancel an in-progress run (JCLAW-414). Flips the run's cooperative-cancel
// flag server-side so the agent tool loop bails at its next checkpoint; the
// endpoint marks the run CANCELLED immediately, and the SSE task.* events
// refresh the row so the icon swaps back to the bolt.
async function cancelRunningRun(runId: number) {
  await mutate(`/api/task-runs/${runId}/cancel`, { method: 'POST' })
  refresh()
}

// Per-type action model: recurring tasks (CRON/INTERVAL) use Pause/Resume
// (reversible, cadence-preserving); one-off tasks (SCHEDULED/IMMEDIATE) use
// Cancel; any CANCELLED task uses Re-enable to re-arm. isLive = waiting or
// recurring-active (the states a suspend action applies to).
function isRecurring(t: Task): boolean {
  return t.type === 'CRON' || t.type === 'INTERVAL'
}
function isLive(t: Task): boolean {
  return t.status === 'PENDING' || t.status === 'ACTIVE'
}

/**
 * Hard-delete a task — distinct from cancelTask which keeps the row.
 * Always confirms first because the deletion includes every TaskRun
 * and TaskRunMessage under it, and there's no revive path.
 */
async function deleteTask(task: Task) {
  const ok = await confirm({
    title: 'Delete task?',
    message: `"${task.name}" and its run history will be permanently removed. This cannot be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  await mutate(`/api/tasks/${task.id}`, { method: 'DELETE' })
  refresh()
}

// ── JCLAW-22 (slice D): row-expand detail — step list + TaskRun history ──
// Single-open accordion (same UX as /logs). The step list is parsed
// client-side from task.description (already in the /api/tasks payload via
// parseTaskSteps, the twin of the backend TaskSteps.parse); the run history
// is lazy-loaded from /api/tasks/{id}/runs on first expand and cached.
const expandedId = ref<number | null>(null)
const runsByTask = reactive<Record<number, TaskRunView[]>>({})
const runsLoading = reactive<Record<number, boolean>>({})
const runsError = reactive<Record<number, string | null>>({})

async function loadRuns(id: number) {
  runsLoading[id] = true
  runsError[id] = null
  try {
    runsByTask[id] = await $fetch<TaskRunView[]>(`/api/tasks/${id}/runs?limit=20`)
  }
  catch (e) {
    runsError[id] = e instanceof Error ? e.message : 'Failed to load run history'
  }
  finally {
    runsLoading[id] = false
  }
}

function toggleExpand(id: number) {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id
  // Lazy-load the run history on first expand; the live SSE refresh
  // (slice L) reuses loadRuns to keep an open task's history current.
  if (runsByTask[id] === undefined && !runsLoading[id]) {
    void loadRuns(id)
  }
}

// Steps for the currently-expanded task — parsed once here rather than
// per-render inside the v-for (only one row is ever open).
const expandedTask = computed(() => tasks.value?.find(t => t.id === expandedId.value) ?? null)
const expandedSteps = computed(() => parseTaskSteps(expandedTask.value?.description))

// colspan for the expanded detail row: every visible column, including the
// leading checkbox column when bulk-select is active.
const tableColspan = computed(() => (selectMode.value ? 9 : 8))

// ── JCLAW-22 (slice E): inline step editor ──
// Editing is tied to the expanded row, one task at a time. editSteps is a
// working copy of the parsed steps; saving re-serialises it (single step →
// plain text, multiple → JSON array via serializeTaskSteps) and PATCHes the
// task's description, then refreshes so the read-only view reflects it.
const editingId = ref<number | null>(null)
const editSteps = ref<string[]>([])
const savingSteps = ref(false)
const stepsError = ref<string | null>(null)

function startEditSteps(task: Task) {
  const steps = parseTaskSteps(task.description)
  editSteps.value = steps.length ? [...steps] : ['']
  stepsError.value = null
  editingId.value = task.id
}

function cancelEditSteps() {
  editingId.value = null
  editSteps.value = []
  stepsError.value = null
}

function addStep() {
  editSteps.value.push('')
}

function removeStep(i: number) {
  editSteps.value.splice(i, 1)
}

function moveStep(i: number, dir: -1 | 1) {
  const arr = editSteps.value
  const j = i + dir
  if (j < 0 || j >= arr.length) return
  const a = arr[i]
  const b = arr[j]
  if (a === undefined || b === undefined) return
  arr[i] = b
  arr[j] = a
}

async function saveSteps(task: Task) {
  savingSteps.value = true
  stepsError.value = null
  try {
    const description = serializeTaskSteps(editSteps.value)
    await $fetch(`/api/tasks/${task.id}`, { method: 'PATCH', body: { description } })
    editingId.value = null
    editSteps.value = []
    refresh()
  }
  catch (e) {
    stepsError.value = e instanceof Error ? e.message : 'Failed to save steps'
  }
  finally {
    savingSteps.value = false
  }
}

// ── JCLAW-22 (slice P): TaskRun trace in a PeekPanel ──
// The shared PeekPanel lazy-loads a run's task_run_message rows (turn-by-turn
// trace) from /api/task-runs/{id}/messages. Opened either from a run row in
// the history (openTrace) or from a transcript-search hit (openTraceForHit,
// slice T) — both funnel through loadTrace with their own title/subtitle.
const peekOpen = ref(false)
const peekTitle = ref('Run trace')
const peekSubtitle = ref('')
const peekMessages = ref<TaskRunMessageView[]>([])
const peekLoading = ref(false)
const peekError = ref<string | null>(null)

async function loadTrace(runId: number, title: string, subtitle: string) {
  peekTitle.value = title
  peekSubtitle.value = subtitle
  peekOpen.value = true
  peekMessages.value = []
  peekError.value = null
  peekLoading.value = true
  try {
    peekMessages.value = await $fetch<TaskRunMessageView[]>(`/api/task-runs/${runId}/messages`)
  }
  catch (e) {
    peekError.value = e instanceof Error ? e.message : 'Failed to load trace'
  }
  finally {
    peekLoading.value = false
  }
}

function openTrace(run: TaskRunView) {
  const when = run.startedAt ? new Date(run.startedAt).toLocaleString() : ''
  void loadTrace(run.id, `Run trace — ${run.status ?? ''}`, when)
}

function closeTrace() {
  peekOpen.value = false
}

// ── JCLAW-22 (slice T): transcript search ──
// Distinct from the FilterBar's q (task name/description, JCLAW-304): this
// searches task_run_message bodies via the shipped /api/task-runs/search
// (Lucene QueryParser — phrase, AND/OR/NOT, prefix*). Each hit opens the
// run's trace in the same PeekPanel.
const transcriptQuery = ref('')
const transcriptHits = ref<TranscriptSearchHit[]>([])
const transcriptSearching = ref(false)
const transcriptError = ref<string | null>(null)
const transcriptActive = ref(false)

async function searchTranscripts() {
  const q = transcriptQuery.value.trim()
  if (!q) {
    clearTranscriptSearch()
    return
  }
  transcriptActive.value = true
  transcriptSearching.value = true
  transcriptError.value = null
  try {
    transcriptHits.value = await $fetch<TranscriptSearchHit[]>(
      `/api/task-runs/search?q=${encodeURIComponent(q)}&limit=50`,
    )
  }
  catch (e) {
    transcriptError.value = e instanceof Error ? e.message : 'Search failed'
    transcriptHits.value = []
  }
  finally {
    transcriptSearching.value = false
  }
}

function clearTranscriptSearch() {
  transcriptQuery.value = ''
  transcriptHits.value = []
  transcriptActive.value = false
  transcriptError.value = null
}

function openTraceForHit(hit: TranscriptSearchHit) {
  if (hit.taskRunId == null) return
  const when = hit.createdAt ? new Date(hit.createdAt).toLocaleString() : ''
  void loadTrace(hit.taskRunId, `Trace — ${hit.taskName ?? 'run'}`, `${hit.role ?? ''} · ${when}`)
}

function openTraceForRun(run: RecentRunView) {
  const when = run.startedAt ? new Date(run.startedAt).toLocaleString() : ''
  void loadTrace(run.id, `Trace — ${run.taskName ?? 'run'}`, `${run.status ?? ''} · ${when}`)
}

// ── JCLAW-22 (slice X): CSV/JSON audit export ──
// The FilterBar's Export button downloads a JSON audit bundle of the current
// (filtered) tasks, each with its TaskRuns. The trace PeekPanel adds a
// per-run export of that run's task_run_message rows (the "selected" rows).
const exporting = ref(false)

function fileStamp(): string {
  return new Date().toISOString().replaceAll(':', '-').slice(0, 19)
}

function downloadBlob(filename: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

async function exportTasksBundle() {
  if (exporting.value) return
  const list = tasks.value ?? []
  if (!list.length) return
  exporting.value = true
  try {
    const tasksWithRuns = await Promise.all(list.map(async (t) => {
      const runs = await $fetch<TaskRunView[]>(`/api/tasks/${t.id}/runs?limit=200`).catch(() => [])
      return { ...t, runs }
    }))
    const payload = {
      exportedAt: new Date().toISOString(),
      taskCount: tasksWithRuns.length,
      tasks: tasksWithRuns,
    }
    downloadBlob(`jclaw-tasks-audit-${fileStamp()}.json`, JSON.stringify(payload, null, 2), 'application/json')
  }
  finally {
    exporting.value = false
  }
}

function exportTrace() {
  if (!peekMessages.value.length) return
  const payload = {
    title: peekTitle.value,
    exportedAt: new Date().toISOString(),
    messages: peekMessages.value,
  }
  downloadBlob(`jclaw-run-trace-${fileStamp()}.json`, JSON.stringify(payload, null, 2), 'application/json')
}

// ── JCLAW-22 (slice L): live updates via SSE + elapsed-time indicator ──
const { onEvent } = useEventBus()

// 1s tick drives the live elapsed-time indicator for RUNNING runs.
const nowMs = ref(Date.now())
let tickHandle: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  tickHandle = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})
onUnmounted(() => {
  if (tickHandle) clearInterval(tickHandle)
})

function elapsedSince(startedAt: string | null): string {
  if (!startedAt) return ''
  const secs = Math.max(0, Math.floor((nowMs.value - new Date(startedAt).getTime()) / 1000))
  const m = Math.floor(secs / 60)
  const s = secs % 60
  return m > 0 ? `${m}m ${s}s` : `${s}s`
}

// Real-time list updates: any task lifecycle event (published on the SSE bus
// by TaskLifecycleEvents) refreshes the list and the open task's run history.
// Debounced so a burst of events collapses to one refetch; skipped mid-edit
// so an in-progress step edit isn't disrupted.
let liveRefreshHandle: ReturnType<typeof setTimeout> | undefined
function scheduleLiveRefresh() {
  if (liveRefreshHandle) clearTimeout(liveRefreshHandle)
  liveRefreshHandle = setTimeout(() => {
    if (editingId.value != null) return
    refresh()
    refreshStats()
    refreshRecent()
    if (expandedId.value != null) void loadRuns(expandedId.value)
  }, 400)
}

for (const evt of ['task.started', 'task.completed', 'task.failed', 'task.delivered', 'task.delivery_failed', 'task.lost']) {
  onEvent(evt, scheduleLiveRefresh)
}

// LOST sits between RUNNING (blue) and FAILED (red) on the heat axis —
// the task is stuck but db-scheduler will auto-recover it. Orange
// communicates "needs attention but not yet a terminal failure".
const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-400',
  ACTIVE: 'text-emerald-400',
  RUNNING: 'text-blue-400',
  LOST: 'text-orange-400',
  COMPLETED: 'text-green-400',
  FAILED: 'text-red-400',
  CANCELLED: 'text-neutral-600',
}

// v0.12.38: ACTIVE was promoted from a frontend display-only alias to a
// real Task.Status enum value. Recurring tasks now arrive with status =
// "ACTIVE" directly from the API, so we no longer need a per-row mapping
// helper. statusColors[task.status] resolves correctly for both PENDING
// (one-shot waiting) and ACTIVE (recurring ongoing).

/**
 * Humanize a Task's recurring schedule for display. Order of preference:
 *   1. Recognized cron pattern (daily at 9 AM, every 30 min, weekdays at...)
 *   2. INTERVAL duration humanized (every 30 min, every 2 hours, every 1 day)
 *   3. Server's scheduleDisplay — the operator's raw input verbatim
 *   4. em-dash if nothing applies (one-shot / unscheduled tasks)
 */
function humanSchedule(task: Task): string {
  if (task.type === 'INTERVAL') {
    const secs = task.intervalSeconds as number | null | undefined
    if (typeof secs === 'number' && secs > 0) return `every ${humanDuration(secs)}`
    return (task.scheduleDisplay as string | null) || '—'
  }
  if (task.type === 'CRON') {
    const expr = task.cronExpression as string | null | undefined
    if (expr) {
      const h = humanCron(expr)
      if (h) return h
    }
    return (task.scheduleDisplay as string | null) || expr || '—'
  }
  return (task.scheduleDisplay as string | null) || '—'
}

function humanDuration(secs: number): string {
  if (secs % 86400 === 0) {
    const d = secs / 86400
    return d === 1 ? '1 day' : `${d} days`
  }
  if (secs % 3600 === 0) {
    const h = secs / 3600
    return h === 1 ? '1 hour' : `${h} hours`
  }
  if (secs % 60 === 0) {
    const m = secs / 60
    return m === 1 ? '1 min' : `${m} min`
  }
  return `${secs}s`
}

/**
 * Recognize common cron patterns and return a natural-language equivalent.
 * Returns null for patterns we don't know — caller falls back to the
 * server's scheduleDisplay so the operator at least sees their raw input.
 */
function humanCron(expr: string): string | null {
  const trimmed = expr.trim()
  switch (trimmed) {
    case '@hourly': return 'hourly'
    case '@daily':
    case '@midnight': return 'daily at midnight'
    case '@weekly': return 'weekly on Sunday at midnight'
    case '@monthly': return 'monthly on the 1st at midnight'
    case '@yearly':
    case '@annually': return 'yearly on Jan 1 at midnight'
  }
  const parts = trimmed.split(/\s+/)
  let sec: string, min: string, hour: string, dom: string, mon: string, dow: string
  if (parts.length === 6) {
    // We know all six indexes exist after the length check.
    [sec, min, hour, dom, mon, dow] = parts as [string, string, string, string, string, string]
  }
  else if (parts.length === 5) {
    [min, hour, dom, mon, dow] = parts as [string, string, string, string, string]
    sec = '0'
  }
  else return null
  const dailyWildcards = dom === '*' && mon === '*' && dow === '*'
  if (sec === '0' && dailyWildcards) {
    if (min.startsWith('*/') && hour === '*') {
      const n = Number.parseInt(min.slice(2), 10)
      if (!Number.isNaN(n)) return `every ${n} min`
    }
    if (min === '0' && hour.startsWith('*/')) {
      const n = Number.parseInt(hour.slice(2), 10)
      if (!Number.isNaN(n)) {
        const unit = n === 1 ? '1 hour' : `${n} hours`
        return `every ${unit}`
      }
    }
    if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
      return `daily at ${formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))}`
    }
  }
  if (sec === '0' && dom === '*' && mon === '*' && /^\d+$/.test(min) && /^\d+$/.test(hour)) {
    if (dow === '1-5' || /^MON-FRI$/i.test(dow)) {
      return `weekdays at ${formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))}`
    }
    const dowNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
    if (/^\d+$/.test(dow)) {
      const d = Number.parseInt(dow, 10)
      if (d >= 0 && d <= 6) return `weekly on ${dowNames[d]} at ${formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))}`
    }
  }
  return null
}

function formatTime12h(hour: number, min: number): string {
  const period = hour >= 12 ? 'PM' : 'AM'
  const h12 = hour % 12 || 12
  const mm = min === 0 ? '' : `:${min.toString().padStart(2, '0')}`
  return `${h12}${mm} ${period}`
}

/**
 * Mirror pages/index.vue's formatActivityTimestamp so the Next Run column
 * reads "May 24, 2026 · 9:00:00 AM" instead of the locale-default
 * "25/05/2026, 09:00:00". Pinned to 12-hour clock so AM/PM is consistent
 * regardless of OS locale settings.
 *
 * <p>JCLAW-261: when {@code zone} is supplied (the task's effective IANA
 * timezone), the timestamp is rendered IN that zone — so "9 am NYC"
 * shows as 9:00 AM regardless of where the operator's browser sits. The
 * zone short-id is appended so the operator can tell which clock they're
 * reading. A null/undefined zone falls back to browser-local (existing
 * behavior); used by the few legacy call sites that don't carry a task.
 */
/**
 * JCLAW-261: which IANA zone should the Next Run column render this
 * task's timestamp in? CRON / SCHEDULED carry a meaningful per-task
 * (or default-resolved) zone; INTERVAL and IMMEDIATE are duration-
 * based and have no wall-clock binding — render those in the browser's
 * local zone (return undefined to fall through formatTaskTimestamp's
 * default behavior).
 */
function zoneForTaskRender(task: Task): string | undefined {
  if (task.type !== 'CRON' && task.type !== 'SCHEDULED') return undefined
  return task.effectiveTimezone ?? undefined
}

function formatTaskTimestamp(iso: string, zone?: string | null): string {
  const d = new Date(iso)
  const opts: Intl.DateTimeFormatOptions = zone ? { timeZone: zone } : {}
  const date = d.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    ...opts,
  })
  const time = d.toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
    ...opts,
  })
  return zone ? `${date} · ${time} (${zone})` : `${date} · ${time}`
}

// ─────────────────────────── Calendar projection ────────────────────────────
//
// Project every fire a task makes in a half-open window [from, to). Used by
// the Calendar view to drop dots onto specific day cells. CRON tasks use a
// limited cron expander that handles the patterns humanCron recognizes;
// anything else falls back to just the task's nextRunAt (so the operator at
// least sees the next fire even if we can't expand further). INTERVAL tasks
// step forward from nextRunAt by intervalSeconds. SCHEDULED / IMMEDIATE tasks
// have a single fire — show it on its day.

interface ProjectedFire {
  taskId: number
  taskName: string
  taskType: string
  taskStatus: string
  agentName: string | null
  fireAt: Date
  /** True when {@link fireAt} is strictly before the current wall-clock
   *  time at the moment the projection ran. Drives per-fire dimming in
   *  the calendar's day cells — a daily-9am task on today's date should
   *  read as already-fired by 9:01am even though today's whole-day
   *  `isPast` flag is still false. */
  isPast: boolean
  /** Mirrors {@link Task.paused}. A paused recurring task keeps its schedule
   *  but skips the fire body, so its projected fires are real slots that
   *  won't actually run — the calendar dims + strikes them to show that. */
  taskPaused: boolean
}

function projectFires(task: Task, from: Date, to: Date): ProjectedFire[] {
  const out: ProjectedFire[] = []
  const nowMs = Date.now()
  const base = {
    taskId: task.id,
    taskName: task.name,
    taskType: task.type,
    taskStatus: task.status,
    agentName: task.agentName,
    taskPaused: task.paused,
  }
  const push = (fireAt: Date) => {
    out.push({ ...base, fireAt, isPast: fireAt.getTime() < nowMs })
  }
  if (task.type === 'INTERVAL') {
    const secs = task.intervalSeconds as number | null | undefined
    const start = task.nextRunAt as string | null | undefined
    if (typeof secs === 'number' && secs > 0 && start) {
      let cursor = new Date(start)
      // Step backwards first if nextRunAt is past `to` (shouldn't happen but
      // defends against clock skew + caching).
      while (cursor.getTime() > to.getTime()) cursor = new Date(cursor.getTime() - secs * 1000)
      // Then forward to fill the window. Cap at 500 iterations defensively
      // (e.g. every-second interval over a year would explode otherwise).
      for (let i = 0; i < 500 && cursor.getTime() < to.getTime(); i++) {
        if (cursor.getTime() >= from.getTime()) push(new Date(cursor))
        cursor = new Date(cursor.getTime() + secs * 1000)
      }
    }
    return out
  }
  if (task.type === 'CRON') {
    const expr = task.cronExpression as string | null | undefined
    if (expr) {
      const fires = expandCron(expr, from, to)
      for (const f of fires) push(f)
    }
    else if (task.nextRunAt) {
      const d = new Date(task.nextRunAt as string)
      if (d >= from && d < to) push(d)
    }
    return out
  }
  // SCHEDULED / IMMEDIATE / one-shot — single fire on nextRunAt.
  if (task.nextRunAt) {
    const d = new Date(task.nextRunAt as string)
    if (d >= from && d < to) push(d)
  }
  return out
}

/**
 * Lightweight cron expander covering the patterns humanCron recognizes.
 * For each minute slot in [from, to), tests whether the cron expression's
 * minute / hour / day-of-month / month / day-of-week fields match. The
 * calendar grid is always a 6-row × 7-col block (42 days) covering the
 * current month plus leading/trailing days from neighbours, so the
 * iteration is bounded at ~60k steps per task — well under what a modern
 * browser does in a single tick. A defensive 45-day cap stays in place
 * to guard against callers passing a degenerate window.
 */
function expandCron(expr: string, from: Date, to: Date): Date[] {
  const out: Date[] = []
  const trimmed = expr.trim()
  // @-shortcuts: rewrite to equivalent 5-field cron and recurse.
  const shortcut: Record<string, string> = {
    '@hourly': '0 * * * *',
    '@daily': '0 0 * * *',
    '@midnight': '0 0 * * *',
    '@weekly': '0 0 * * 0',
    '@monthly': '0 0 1 * *',
    '@yearly': '0 0 1 1 *',
    '@annually': '0 0 1 1 *',
  }
  if (shortcut[trimmed]) return expandCron(shortcut[trimmed]!, from, to)

  const parts = trimmed.split(/\s+/)
  let min: string, hour: string, dom: string, mon: string, dow: string
  if (parts.length === 6) {
    // Spring 6-field: sec min hour dom mon dow — only sec=0 expansion is
    // supported (the calendar grid is minute-resolution).
    if (parts[0] !== '0') return []
    ;[min, hour, dom, mon, dow] = parts.slice(1) as [string, string, string, string, string]
  }
  else if (parts.length === 5) {
    [min, hour, dom, mon, dow] = parts as [string, string, string, string, string]
  }
  else return []

  const cap = Math.min(to.getTime(), from.getTime() + 45 * 24 * 60 * 60 * 1000)
  // Iterate by minute (cron resolution). Bounded ~60k iterations for a 42-day
  // grid (the calendar's worst case) — well under a single browser tick.
  const cursor = new Date(from)
  cursor.setSeconds(0, 0)
  while (cursor.getTime() < cap) {
    if (matchCronField(min, cursor.getMinutes(), 0, 59)
      && matchCronField(hour, cursor.getHours(), 0, 23)
      && matchCronField(dom, cursor.getDate(), 1, 31)
      && matchCronField(mon, cursor.getMonth() + 1, 1, 12)
      && matchCronField(dow, cursor.getDay(), 0, 6)) {
      out.push(new Date(cursor))
    }
    cursor.setMinutes(cursor.getMinutes() + 1)
  }
  return out
}

/** Test whether a cron-field value matches the current numeric clock value. */
function matchCronField(field: string, value: number, min: number, max: number): boolean {
  if (field === '*') return true
  for (const part of field.split(',')) {
    // step: */N or X-Y/N or X/N
    const stepMatch = part.match(/^(.+)\/(\d+)$/)
    if (stepMatch) {
      const step = Number.parseInt(stepMatch[2]!, 10)
      const base = stepMatch[1]!
      const nonStarRange: [number, number] = base.includes('-')
        ? base.split('-').map(n => Number.parseInt(n, 10)) as [number, number]
        : [Number.parseInt(base, 10), max]
      const [lo, hi] = base === '*' ? [min, max] : nonStarRange
      if (value >= lo && value <= hi && (value - lo) % step === 0) return true
      continue
    }
    // range: X-Y
    if (part.includes('-')) {
      const [lo, hi] = part.split('-').map(n => Number.parseInt(n, 10)) as [number, number]
      if (value >= lo && value <= hi) return true
      continue
    }
    // single value
    if (Number.parseInt(part, 10) === value) return true
  }
  return false
}

// ─────────────────────────── Calendar grid state ────────────────────────────
// (calGranularity / calendarMonth / calAnchor / calRange are declared up top so
// the runs fetch can key on the visible range — see the fetch block above.)

function startOfMonth(d: Date): Date {
  const r = new Date(d)
  r.setDate(1)
  r.setHours(0, 0, 0, 0)
  return r
}

function startOfDay(d: Date): Date {
  const r = new Date(d)
  r.setHours(0, 0, 0, 0)
  return r
}

// Week starts on Sunday to match the month grid's leading-day padding.
function startOfWeek(d: Date): Date {
  const r = startOfDay(d)
  r.setDate(r.getDate() - r.getDay())
  return r
}

function addDays(d: Date, n: number): Date {
  const r = new Date(d)
  r.setDate(r.getDate() + n)
  return r
}

function nextMonthFrom(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth() + 1, 1)
}

// Unified navigation: month steps by calendar month, week/day by 7/1 days.
function calPrev() {
  if (calGranularity.value === 'month') {
    calendarMonth.value = new Date(calendarMonth.value.getFullYear(), calendarMonth.value.getMonth() - 1, 1)
  }
  else {
    calAnchor.value = addDays(calAnchor.value, calGranularity.value === 'week' ? -7 : -1)
  }
}
function calNext() {
  if (calGranularity.value === 'month') {
    calendarMonth.value = nextMonthFrom(calendarMonth.value)
  }
  else {
    calAnchor.value = addDays(calAnchor.value, calGranularity.value === 'week' ? 7 : 1)
  }
}
function calToday() {
  calendarMonth.value = startOfMonth(new Date())
  calAnchor.value = startOfDay(new Date())
}

const calTitle = computed(() => {
  if (calGranularity.value === 'day') {
    return calAnchor.value.toLocaleDateString(undefined, { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })
  }
  if (calGranularity.value === 'week') {
    const s = startOfWeek(calAnchor.value)
    const e = addDays(s, 6)
    const sameMonth = s.getMonth() === e.getMonth()
    const sStr = s.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    const eStr = e.toLocaleDateString(undefined, sameMonth ? { day: 'numeric', year: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' })
    return `${sStr} – ${eStr}`
  }
  return calendarMonth.value.toLocaleDateString(undefined, { year: 'numeric', month: 'long' })
})

/**
 * 6-row × 7-col grid covering the visible month, padded with leading days
 * from the previous month and trailing days from the next month so every
 * week starts on Sunday. Each cell carries its date plus the fires
 * projected onto that day for the current task list.
 */
interface DayCell {
  date: Date
  inMonth: boolean
  isToday: boolean
  isPast: boolean
  fires: ProjectedFire[]
}

const calendarDays = computed<DayCell[]>(() => {
  const monthStart = calendarMonth.value
  const monthEnd = nextMonthFrom(monthStart)
  const gridStart = new Date(monthStart)
  gridStart.setDate(monthStart.getDate() - monthStart.getDay())
  const cells: DayCell[] = []
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  // Project once over the full visible window so we don't re-walk per cell.
  const gridEnd = new Date(gridStart)
  gridEnd.setDate(gridStart.getDate() + 42)
  const allFires: ProjectedFire[] = []
  for (const t of tasks.value ?? []) {
    allFires.push(...projectFires(t, gridStart, gridEnd))
  }
  for (let i = 0; i < 42; i++) {
    const d = new Date(gridStart)
    d.setDate(gridStart.getDate() + i)
    const dayStart = d.getTime()
    const dayEnd = dayStart + 24 * 60 * 60 * 1000
    cells.push({
      date: d,
      inMonth: d >= monthStart && d < monthEnd,
      isToday: d.getTime() === today.getTime(),
      isPast: d.getTime() < today.getTime(),
      fires: allFires
        .filter(f => f.fireAt.getTime() >= dayStart && f.fireAt.getTime() < dayEnd)
        .sort((a, b) => a.fireAt.getTime() - b.fireAt.getTime()),
    })
  }
  return cells
})

// ───────────────────── Week / Day hourly-grid projection ─────────────────────
// Week = 7 columns, Day = 1 column. Each column places its runs as absolutely
// positioned blocks on a 24h axis (top + height as a % of the day, sized by
// duration), plus hollow markers for upcoming projected fires that have not
// run yet. RUNNING runs (no completedAt) extend to the live `nowMs` tick.
const DAY_MS = 24 * 60 * 60 * 1000
const HOUR_PX = 48
const hours = Array.from({ length: 24 }, (_, h) => h)
const gridBodyStyle = { height: `${24 * HOUR_PX}px` }

interface RunBlock {
  run: RecentRunView
  topPct: number
  heightPct: number
}
interface FireMarker {
  fire: ProjectedFire
  topPct: number
}
interface DayColumn {
  date: Date
  isToday: boolean
  runs: RunBlock[]
  fires: FireMarker[]
}

const calColumns = computed<DayColumn[]>(() => {
  const count = calGranularity.value === 'day' ? 1 : 7
  const gridStart = startOfDay(calRange.value.start)
  const today = startOfDay(new Date(nowMs.value)).getTime()
  const runs = recentRuns.value ?? []
  // Project once over the whole visible window, then bucket per day below.
  const allFires: ProjectedFire[] = []
  for (const t of tasks.value ?? []) {
    allFires.push(...projectFires(t, gridStart, addDays(gridStart, count)))
  }
  const cols: DayColumn[] = []
  for (let i = 0; i < count; i++) {
    const d = addDays(gridStart, i)
    const dayStart = d.getTime()
    const dayEnd = dayStart + DAY_MS
    const dayRuns: RunBlock[] = []
    for (const run of runs) {
      if (!run.startedAt) continue
      const s = new Date(run.startedAt).getTime()
      if (s < dayStart || s >= dayEnd) continue
      const endMs = run.completedAt ? new Date(run.completedAt).getTime() : nowMs.value
      const topPct = ((s - dayStart) / DAY_MS) * 100
      const rawH = ((Math.max(endMs, s) - s) / DAY_MS) * 100
      // Min height so instant runs stay clickable; never spill past midnight.
      const heightPct = Math.min(100 - topPct, Math.max(1.5, rawH))
      dayRuns.push({ run, topPct, heightPct })
    }
    // Only upcoming fires — a past fire already has its run block, so showing
    // both would double-count. isPast is stamped at projection time.
    const dayFires: FireMarker[] = allFires
      .filter(f => !f.isPast && f.fireAt.getTime() >= dayStart && f.fireAt.getTime() < dayEnd)
      .map(f => ({ fire: f, topPct: ((f.fireAt.getTime() - dayStart) / DAY_MS) * 100 }))
    cols.push({ date: d, isToday: dayStart === today, runs: dayRuns, fires: dayFires })
  }
  return cols
})

// Red "now" line position as a % of the day, for today's column.
const nowTopPct = computed(() => {
  const d = new Date(nowMs.value)
  return ((d.getHours() * 60 + d.getMinutes()) / (24 * 60)) * 100
})

function formatHour(h: number): string {
  const period = h < 12 ? 'a' : 'p'
  const hour = h % 12 === 0 ? 12 : h % 12
  return `${hour}${period}`
}

function fmtRunTime(run: RecentRunView): string {
  const s = run.startedAt
    ? new Date(run.startedAt).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
    : '?'
  return run.durationMs != null ? `${s} · ${(run.durationMs / 1000).toFixed(1)}s` : s
}

// Solid block fills for actual runs (the text statusColors are too faint as a
// filled background). Falls back to neutral for any unmapped status.
const statusBg: Record<string, string> = {
  RUNNING: 'bg-blue-500/70 border-blue-400',
  LOST: 'bg-orange-500/70 border-orange-400',
  COMPLETED: 'bg-green-600/60 border-green-500',
  FAILED: 'bg-red-500/70 border-red-400',
  CANCELLED: 'bg-neutral-600/60 border-neutral-500',
}

// (JCLAW-304: status/type select ids were retired with their dropdowns.
// FilterBar manages its own ARIA labels on the underlying input.)
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <div class="flex items-center gap-3">
        <h1 class="text-lg font-semibold text-fg-strong">
          Tasks
        </h1>
        <!-- View switcher (icon-only, tooltips): table / calendar. Sits next
             to the title. State persists in the URL (?view=calendar) so
             refresh and shareable links survive. -->
        <div
          v-if="!selectMode"
          class="inline-flex border border-input divide-x divide-input"
          role="tablist"
          aria-label="Task view"
        >
          <button
            v-for="opt in ([
              { id: 'table', label: 'Table', icon: TableCellsIcon },
              { id: 'calendar', label: 'Calendar', icon: CalendarDaysIcon },
            ] as const)"
            :key="opt.id"
            type="button"
            role="tab"
            :aria-selected="view === opt.id"
            :title="`${opt.label} view`"
            :aria-label="`${opt.label} view`"
            class="p-2 inline-flex items-center transition-colors"
            :class="view === opt.id
              ? 'bg-muted text-fg-strong'
              : 'text-fg-muted hover:text-fg-strong'"
            @click="view = opt.id"
          >
            <component
              :is="opt.icon"
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <!-- JCLAW-259: live retention TTL. Subtle muted text so it doesn't
             compete with the page title but stays visible enough that
             operators don't get surprised by auto-deletes. Sourced from
             tasks.retentionDays (default 30, 0 = disabled). -->
        <NuxtLink
          to="/settings"
          class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
          title="Configure in Settings → Tasks"
        >
          {{ retentionDisplay }}
        </NuxtLink>
        <template v-if="!selectMode">
          <button
            :disabled="!tasks?.length"
            class="p-2 border border-input text-fg-muted hover:text-red-400 hover:border-red-700/50 disabled:opacity-40 disabled:hover:text-fg-muted disabled:hover:border-input transition-colors"
            title="Delete tasks"
            @click="enterSelectMode"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </template>
        <template v-else>
          <button
            class="px-3 py-1.5 border border-input text-fg-muted text-xs hover:text-fg-strong hover:border-neutral-500 transition-colors"
            @click="exitSelectMode"
          >
            Cancel
          </button>
          <button
            :disabled="!selectedIds.size || deletingBulk"
            class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 transition-colors"
            @click="deleteSelected"
          >
            Delete {{ selectedIds.size || '' }}
          </button>
        </template>
      </div>
    </div>

    <!-- JCLAW-22 (slice K): dashboard KPI strip. Refetched live on task
         lifecycle events so the counts stay current. -->
    <div
      v-if="stats"
      class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2 mb-4"
    >
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Runs today
        </div>
        <div class="text-lg font-semibold text-fg-strong">
          {{ stats.runsToday }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Success rate
        </div>
        <div class="text-lg font-semibold text-fg-strong">
          {{ stats.successRate != null ? `${Math.round(stats.successRate * 100)}%` : '—' }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Avg duration
        </div>
        <div class="text-lg font-semibold text-fg-strong">
          {{ stats.avgDurationMs != null ? `${(stats.avgDurationMs / 1000).toFixed(1)}s` : '—' }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Pending
        </div>
        <div class="text-lg font-semibold text-fg-strong">
          {{ stats.pendingCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Running
        </div>
        <div
          class="text-lg font-semibold"
          :class="stats.runningCount > 0 ? 'text-blue-400' : 'text-fg-strong'"
        >
          {{ stats.runningCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Failed
        </div>
        <div
          class="text-lg font-semibold"
          :class="stats.failedCount > 0 ? 'text-red-400' : 'text-fg-strong'"
        >
          {{ stats.failedCount }}
        </div>
      </div>
    </div>

    <!-- One filter bar drives everything. JCLAW-304 replaced the legacy
         status/type selects with this FilterBar; the `q:KEYWORD` token runs
         FTS over the TASK Lucene scope (task.name + task.description virtual
         doc); `status:`, `type:`, and `agent:` are equality keys the backend
         intersects with the FTS hit set. The `transcript:` token is a separate
         scope (task_run_message bodies) — see onFiltersChanged; its results
         render in the hit panel below, not the table. Multi-word transcript
         queries are quoted so they survive tokenization: transcript:"send the
         invoice" (also supports AND/OR/NOT and prefix*). -->
    <div class="mb-4">
      <FilterBar
        storage-key="tasks"
        placeholder="Filter... (e.g., q:invoice type:CRON status:PENDING transcript:&quot;daily briefing&quot;)"
        :filter-keys="['q', 'status', 'type', 'agent', 'transcript']"
        @update:filters="onFiltersChanged"
        @export="exportTasksBundle"
      />
    </div>

    <!-- JCLAW-22 (slice T): transcript search over task_run_message bodies,
         driven by the FilterBar's `transcript:` token (distinct scope from
         `q:`). Each hit opens the run's turn-by-turn trace in the PeekPanel. -->
    <div
      v-if="transcriptActive"
      class="bg-surface-elevated border border-border mb-4"
    >
      <div class="px-4 py-2 text-[10px] uppercase tracking-wider font-medium text-fg-muted border-b border-border bg-muted/30">
        Transcript results for "{{ transcriptQuery }}"
      </div>
      <p
        v-if="transcriptSearching"
        class="px-4 py-6 text-center text-sm text-fg-muted"
      >
        Searching…
      </p>
      <p
        v-else-if="transcriptError"
        class="px-4 py-6 text-center text-sm text-red-400"
      >
        {{ transcriptError }}
      </p>
      <p
        v-else-if="!transcriptHits.length"
        class="px-4 py-6 text-center text-sm text-fg-muted"
      >
        No transcript matches.
      </p>
      <ul
        v-else
        class="divide-y divide-border"
      >
        <li
          v-for="hit in transcriptHits"
          :key="hit.messageId"
        >
          <button
            type="button"
            class="w-full text-left px-4 py-2.5 hover:bg-muted transition-colors bg-transparent cursor-pointer"
            @click="openTraceForHit(hit)"
          >
            <div class="flex flex-wrap items-center gap-2 mb-0.5">
              <span class="text-xs text-fg-primary font-medium">{{ hit.taskName ?? '—' }}</span>
              <span class="text-[10px] font-mono uppercase text-fg-muted">{{ hit.role }}</span>
              <span
                v-if="hit.agentName"
                class="text-[10px] text-fg-muted"
              >· {{ hit.agentName }}</span>
              <span
                v-if="hit.createdAt"
                class="text-[10px] text-fg-muted ml-auto"
              >{{ new Date(hit.createdAt).toLocaleString() }}</span>
            </div>
            <p class="text-xs text-fg-muted line-clamp-2 whitespace-pre-wrap break-words">
              {{ hit.content }}
            </p>
          </button>
        </li>
      </ul>
    </div>

    <div
      v-if="view === 'table'"
      class="bg-surface-elevated border border-border"
    >
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th
              v-if="selectMode"
              class="px-4 py-2.5 font-medium w-8"
            >
              <input
                type="checkbox"
                :checked="!!selectableRows.length && selectedIds.size === selectableRows.length"
                :indeterminate.prop="selectedIds.size > 0 && selectedIds.size < selectableRows.length"
                aria-label="Select all tasks on this page"
                @change="toggleSelectAll"
              >
            </th>
            <th class="px-4 py-2.5 font-medium">
              Name
            </th>
            <th class="px-4 py-2.5 font-medium">
              Type
            </th>
            <th class="px-4 py-2.5 font-medium">
              Schedule
            </th>
            <th class="px-4 py-2.5 font-medium">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium">
              Agent
            </th>
            <th class="px-4 py-2.5 font-medium">
              Next Run
            </th>
            <th class="px-4 py-2.5 font-medium">
              Retries
            </th>
            <th class="px-4 py-2.5 font-medium text-right">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <template
            v-for="task in tasks"
            :key="task.id"
          >
            <tr
              :class="{ 'bg-muted/30 cursor-pointer': selectMode }"
              @click="selectMode ? toggleSelection(task.id) : undefined"
            >
              <td
                v-if="selectMode"
                class="px-4 py-2.5 w-8"
              >
                <input
                  type="checkbox"
                  :checked="selectedIds.has(task.id)"
                  :aria-label="`Select ${task.name}`"
                  @click.stop="toggleSelection(task.id)"
                >
              </td>
              <td class="px-4 py-2.5 text-fg-primary">
                <button
                  type="button"
                  class="inline-flex items-center gap-1.5 text-left bg-transparent border-0 cursor-pointer text-fg-primary hover:text-fg-strong transition-colors"
                  :aria-expanded="expandedId === task.id"
                  :aria-label="`Toggle details for ${task.name}`"
                  @click.stop="toggleExpand(task.id)"
                >
                  <ChevronRightIcon
                    :class="expandedId === task.id ? 'rotate-90' : ''"
                    class="h-3.5 w-3.5 text-fg-muted shrink-0 transition-transform"
                    aria-hidden="true"
                  />
                  {{ task.name }}
                </button>
              </td>
              <td class="px-4 py-2.5 text-fg-muted font-mono text-xs">
                {{ task.type }}
              </td>
              <td class="px-4 py-2.5 text-fg-muted text-xs">
                {{ humanSchedule(task) }}
              </td>
              <td class="px-4 py-2.5">
                <span
                  :class="statusColors[task.status]"
                  class="text-xs font-mono"
                >{{ task.status }}</span>
              </td>
              <td class="px-4 py-2.5 text-fg-muted">
                {{ task.agentName || '—' }}
              </td>
              <td class="px-4 py-2.5 text-fg-muted text-xs">
                {{ task.nextRunAt ? formatTaskTimestamp(task.nextRunAt as string, zoneForTaskRender(task)) : '—' }}
              </td>
              <td class="px-4 py-2.5 text-fg-muted text-xs">
                {{ task.retryCount }}/{{ task.maxRetries }}
              </td>
              <td class="px-4 py-2.5 text-right">
                <div class="inline-flex items-center gap-1">
                  <!-- Recurring + live + idle → Run now (fire immediately; cadence kept). -->
                  <button
                    v-if="!selectMode && isRecurring(task) && isLive(task) && !task.paused && !task.runningRunId"
                    type="button"
                    class="p-1 text-fg-muted hover:text-emerald-400 transition-colors"
                    :title="`Run “${task.name}” now — fires immediately, next scheduled run unchanged`"
                    :aria-label="`Run ${task.name} now`"
                    @click.stop="runNowTask(task.id)"
                  >
                    <BoltIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- Recurring + live + a run in flight → Cancel this run (JCLAW-414). -->
                  <button
                    v-else-if="!selectMode && isRecurring(task) && isLive(task) && task.runningRunId"
                    type="button"
                    class="p-1 text-red-400 hover:text-red-300 transition-colors"
                    :title="`Cancel the running fire of “${task.name}” — stops at the next safe point; schedule unchanged`"
                    :aria-label="`Cancel the running fire of ${task.name}`"
                    @click.stop="task.runningRunId && cancelRunningRun(task.runningRunId)"
                  >
                    <StopIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- Recurring + live + running → Pause (reversible suspend). -->
                  <button
                    v-if="!selectMode && isRecurring(task) && isLive(task) && !task.paused"
                    type="button"
                    class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                    :title="`Pause schedule for “${task.name}”`"
                    :aria-label="`Pause ${task.name}`"
                    @click.stop="pauseTask(task.id)"
                  >
                    <PauseIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- Recurring + live + paused → Resume. -->
                  <button
                    v-else-if="!selectMode && isRecurring(task) && isLive(task) && task.paused"
                    type="button"
                    class="p-1 text-emerald-400 hover:text-emerald-300 transition-colors"
                    :title="`Resume schedule for “${task.name}”`"
                    :aria-label="`Resume ${task.name}`"
                    @click.stop="resumeTask(task.id)"
                  >
                    <PlayIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- One-off waiting → Cancel (won't fire). -->
                  <button
                    v-else-if="!selectMode && !isRecurring(task) && task.status === 'PENDING'"
                    type="button"
                    class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                    :title="`Cancel “${task.name}” — it won't fire`"
                    :aria-label="`Cancel ${task.name}`"
                    @click.stop="cancelTask(task.id)"
                  >
                    <NoSymbolIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- Cancelled (either type) → Re-enable (re-arm the schedule). -->
                  <button
                    v-if="!selectMode && task.status === 'CANCELLED'"
                    type="button"
                    class="p-1 text-fg-muted hover:text-emerald-400 transition-colors"
                    :title="isRecurring(task)
                      ? `Re-enable “${task.name}” — resume at its next scheduled fire`
                      : `Re-enable “${task.name}” — re-arm its scheduled fire`"
                    :aria-label="`Re-enable ${task.name}`"
                    @click.stop="reenableTask(task.id)"
                  >
                    <ArrowPathIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- Failed / lost → Retry (reset retries and rerun). -->
                  <button
                    v-if="!selectMode && (task.status === 'FAILED' || task.status === 'LOST')"
                    type="button"
                    class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                    :title="`Retry “${task.name}”`"
                    :aria-label="`Retry ${task.name}`"
                    @click.stop="retryTask(task.id)"
                  >
                    <ArrowPathIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                  <button
                    v-if="!selectMode"
                    type="button"
                    class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                    :title="`Permanently delete “${task.name}” and its run history`"
                    :aria-label="`Delete ${task.name}`"
                    @click.stop="deleteTask(task)"
                  >
                    <TrashIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
                </div>
              </td>
            </tr>
            <tr
              v-if="expandedId === task.id"
              class="bg-muted/20"
            >
              <td
                :colspan="tableColspan"
                class="px-4 py-3"
              >
                <div class="grid gap-6 md:grid-cols-2">
                  <!-- Instructions: the JCLAW-260 step list, read-only by
                     default with an inline editor (slice E) behind Edit. -->
                  <section>
                    <div class="flex items-center justify-between mb-1.5">
                      <h3 class="text-[10px] uppercase tracking-wider font-medium text-fg-muted">
                        Instructions
                      </h3>
                      <button
                        v-if="editingId !== task.id"
                        type="button"
                        class="inline-flex items-center gap-1 text-[10px] text-fg-muted hover:text-fg-strong transition-colors bg-transparent border-0 cursor-pointer"
                        @click="startEditSteps(task)"
                      >
                        <PencilSquareIcon
                          class="h-3 w-3"
                          aria-hidden="true"
                        />
                        Edit
                      </button>
                    </div>

                    <!-- Read-only view (numbered when multi-step, verbatim for one). -->
                    <template v-if="editingId !== task.id">
                      <ol
                        v-if="expandedSteps.length > 1"
                        class="list-decimal list-inside space-y-0.5 text-xs text-fg-primary"
                      >
                        <li
                          v-for="(step, i) in expandedSteps"
                          :key="i"
                        >
                          {{ step }}
                        </li>
                      </ol>
                      <p
                        v-else-if="expandedSteps.length === 1"
                        class="text-xs text-fg-primary whitespace-pre-wrap"
                      >
                        {{ expandedSteps[0] }}
                      </p>
                      <p
                        v-else
                        class="text-xs text-fg-muted italic"
                      >
                        No instructions
                      </p>
                    </template>

                    <!-- Inline editor: add / edit / reorder / remove → PATCH. -->
                    <div
                      v-else
                      class="space-y-2"
                    >
                      <div
                        v-for="(step, i) in editSteps"
                        :key="i"
                        class="flex items-start gap-1.5"
                      >
                        <span class="text-[10px] text-fg-muted font-mono mt-1.5 w-4 text-right shrink-0">{{ i + 1 }}</span>
                        <textarea
                          v-model="editSteps[i]"
                          rows="2"
                          :aria-label="`Step ${i + 1}`"
                          placeholder="Step instruction…"
                          class="flex-1 px-2 py-1 bg-muted border border-input text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden focus:border-ring transition-colors resize-y"
                        />
                        <div class="flex flex-col gap-0.5 shrink-0">
                          <button
                            type="button"
                            :disabled="i === 0"
                            :aria-label="`Move step ${i + 1} up`"
                            class="p-0.5 text-fg-muted hover:text-fg-strong disabled:opacity-30 disabled:cursor-not-allowed"
                            @click="moveStep(i, -1)"
                          >
                            <ArrowUpIcon
                              class="h-3 w-3"
                              aria-hidden="true"
                            />
                          </button>
                          <button
                            type="button"
                            :disabled="i === editSteps.length - 1"
                            :aria-label="`Move step ${i + 1} down`"
                            class="p-0.5 text-fg-muted hover:text-fg-strong disabled:opacity-30 disabled:cursor-not-allowed"
                            @click="moveStep(i, 1)"
                          >
                            <ArrowDownIcon
                              class="h-3 w-3"
                              aria-hidden="true"
                            />
                          </button>
                          <button
                            type="button"
                            :aria-label="`Remove step ${i + 1}`"
                            class="p-0.5 text-fg-muted hover:text-red-400"
                            @click="removeStep(i)"
                          >
                            <XMarkIcon
                              class="h-3 w-3"
                              aria-hidden="true"
                            />
                          </button>
                        </div>
                      </div>
                      <div class="flex items-center gap-2 pt-0.5">
                        <button
                          type="button"
                          class="inline-flex items-center gap-1 text-xs text-fg-muted hover:text-fg-strong bg-transparent border-0 cursor-pointer"
                          @click="addStep"
                        >
                          <PlusIcon
                            class="h-3.5 w-3.5"
                            aria-hidden="true"
                          />
                          Add step
                        </button>
                        <span class="flex-1" />
                        <button
                          type="button"
                          :disabled="savingSteps"
                          class="px-2 py-1 text-xs bg-muted border border-input text-fg-muted hover:text-fg-strong disabled:opacity-50 cursor-pointer"
                          @click="cancelEditSteps"
                        >
                          Cancel
                        </button>
                        <button
                          type="button"
                          :disabled="savingSteps"
                          class="inline-flex items-center gap-1 px-2 py-1 text-xs bg-muted border border-emerald-600 text-emerald-400 hover:bg-emerald-600/10 disabled:opacity-50 cursor-pointer"
                          @click="saveSteps(task)"
                        >
                          <CheckIcon
                            class="h-3.5 w-3.5"
                            aria-hidden="true"
                          />
                          {{ savingSteps ? 'Saving…' : 'Save' }}
                        </button>
                      </div>
                      <p
                        v-if="stepsError"
                        class="text-xs text-red-400"
                      >
                        {{ stepsError }}
                      </p>
                    </div>
                  </section>
                  <!-- Run history: lazy-loaded TaskRuns, most-recent first. -->
                  <section>
                    <h3 class="text-[10px] uppercase tracking-wider font-medium text-fg-muted mb-1.5">
                      Run history
                    </h3>
                    <p
                      v-if="runsLoading[task.id]"
                      class="text-xs text-fg-muted"
                    >
                      Loading…
                    </p>
                    <p
                      v-else-if="runsError[task.id]"
                      class="text-xs text-red-400"
                    >
                      {{ runsError[task.id] }}
                    </p>
                    <p
                      v-else-if="!runsByTask[task.id]?.length"
                      class="text-xs text-fg-muted italic"
                    >
                      No runs yet
                    </p>
                    <ul
                      v-else
                      class="space-y-1.5"
                    >
                      <li
                        v-for="run in runsByTask[task.id]"
                        :key="run.id"
                      >
                        <button
                          type="button"
                          class="w-full text-left text-xs border-l-2 border-border pl-2 py-0.5 bg-transparent cursor-pointer hover:border-emerald-500 hover:bg-muted/40 transition-colors"
                          :aria-label="`View execution trace for this run`"
                          @click="openTrace(run)"
                        >
                          <div class="flex flex-wrap items-center gap-2">
                            <span
                              :class="statusColors[run.status ?? '']"
                              class="font-mono"
                            >{{ run.status }}</span>
                            <span class="text-fg-muted">{{ run.startedAt ? formatTaskTimestamp(run.startedAt, zoneForTaskRender(task)) : '—' }}</span>
                            <span
                              v-if="run.durationMs != null"
                              class="text-fg-muted"
                            >· {{ (run.durationMs / 1000).toFixed(1) }}s</span>
                            <span
                              v-else-if="run.status === 'RUNNING'"
                              class="text-blue-400"
                            >· {{ elapsedSince(run.startedAt) }} elapsed</span>
                            <span
                              v-if="run.deliveryStatus && run.deliveryStatus !== 'NOT_REQUESTED'"
                              class="text-fg-muted"
                            >· {{ run.deliveryStatus }}</span>
                          </div>
                          <p
                            v-if="run.error"
                            class="text-red-400 mt-0.5 whitespace-pre-wrap break-words"
                          >
                            {{ run.error }}
                          </p>
                          <p
                            v-else-if="run.outputSummary"
                            class="text-fg-muted mt-0.5 whitespace-pre-wrap break-words line-clamp-3"
                          >
                            {{ run.outputSummary }}
                          </p>
                        </button>
                      </li>
                    </ul>
                  </section>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <div
        v-if="!tasks?.length"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No tasks found
      </div>
    </div>

    <!-- Calendar view: month grid of projected fires, or week/day hourly grids
         that place actual run blocks (sized by duration) plus hollow markers
         for upcoming fires. CRON expansion covers the patterns humanCron
         understands; INTERVAL steps from nextRunAt; SCHEDULED / IMMEDIATE pin
         to their nextRunAt date. -->
    <div
      v-else-if="view === 'calendar'"
      class="bg-surface-elevated border border-border"
    >
      <div class="flex items-center justify-between gap-3 px-4 py-3 border-b border-border">
        <!-- Granularity: Month / Week / Day -->
        <!-- Segmented control uses ARIA group semantics on the wrapper because no native HTML element groups a set of related <button> toggles (fieldset/optgroup are for form inputs and carry UA styling); aria-label names the group for assistive tech. -->
        <div
          class="inline-flex border border-input divide-x divide-border"
          role="group"
          aria-label="Calendar granularity"
        >
          <button
            v-for="g in (['month', 'week', 'day'] as const)"
            :key="g"
            type="button"
            class="px-2.5 py-1 text-xs capitalize transition-colors"
            :class="calGranularity === g
              ? 'bg-emerald-600/20 text-emerald-300'
              : 'text-fg-muted hover:text-fg-strong'"
            :aria-pressed="calGranularity === g"
            @click="calGranularity = g"
          >
            {{ g }}
          </button>
        </div>
        <div class="text-sm font-medium text-fg-strong truncate">
          {{ calTitle }}
        </div>
        <div class="inline-flex items-center gap-1">
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            :title="`Previous ${calGranularity}`"
            :aria-label="`Previous ${calGranularity}`"
            @click="calPrev"
          >
            <ChevronLeftIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            class="px-2 py-1 text-xs text-fg-muted hover:text-fg-strong transition-colors"
            title="Jump to today"
            @click="calToday"
          >
            Today
          </button>
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            :title="`Next ${calGranularity}`"
            :aria-label="`Next ${calGranularity}`"
            @click="calNext"
          >
            <ChevronRightIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>

      <!-- Month: 6×7 day grid of projected fires (compact per-day fire list). -->
      <template v-if="calGranularity === 'month'">
        <div class="grid grid-cols-7 text-[10px] uppercase tracking-wider text-fg-muted border-b border-border">
          <div
            v-for="dn in ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']"
            :key="dn"
            class="px-2 py-1.5"
          >
            {{ dn }}
          </div>
        </div>
        <div class="grid grid-cols-7">
          <div
            v-for="(cell, idx) in calendarDays"
            :key="idx"
            class="min-h-[100px] border-r border-b border-border last:border-r-0 px-2 py-1.5 flex flex-col gap-1"
            :class="[
              cell.inMonth ? '' : 'bg-muted/20',
              cell.isPast ? 'opacity-40' : '',
            ]"
          >
            <div
              class="text-xs flex items-center gap-1"
              :class="[
                cell.inMonth ? 'text-fg-primary' : 'text-fg-muted',
                cell.isToday ? 'font-semibold' : '',
              ]"
            >
              <span
                v-if="cell.isToday"
                class="inline-block w-5 h-5 rounded-full bg-emerald-500 text-white text-center leading-5"
              >{{ cell.date.getDate() }}</span>
              <span v-else>{{ cell.date.getDate() }}</span>
            </div>
            <ul
              v-if="cell.fires.length"
              class="flex flex-col gap-0.5 overflow-hidden"
            >
              <li
                v-for="(fire, i) in cell.fires.slice(0, 4)"
                :key="i"
                class="text-[10px] truncate"
                :class="[
                  statusColors[fire.taskStatus] || 'text-fg-muted',
                  fire.isPast ? 'opacity-40' : '',
                  fire.taskPaused ? 'opacity-40 line-through' : '',
                ]"
                :title="`${fire.taskName} · ${fire.fireAt.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit', hour12: true })}${fire.taskPaused ? ' · paused' : ''}`"
              >
                <span class="font-mono">{{ fire.fireAt.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit', hour12: true }).replace(' ', '') }}</span>
                <span class="ml-1">{{ fire.taskName }}</span>
              </li>
              <li
                v-if="cell.fires.length > 4"
                class="text-[10px] text-fg-muted"
              >
                +{{ cell.fires.length - 4 }} more
              </li>
            </ul>
          </div>
        </div>
      </template>

      <!-- Week (7 cols) / Day (1 col): hourly grid placing real run blocks
           (sized by duration) plus hollow markers for upcoming fires. -->
      <div
        v-else
        class="overflow-auto max-h-[70vh]"
      >
        <!-- Sticky column-date headers. -->
        <div class="flex sticky top-0 z-30 bg-surface-elevated border-b border-border">
          <div class="w-12 shrink-0" />
          <div
            v-for="col in calColumns"
            :key="col.date.toISOString()"
            class="flex-1 px-1 py-1.5 text-center border-l border-border"
          >
            <div class="text-[10px] uppercase tracking-wider text-fg-muted">
              {{ col.date.toLocaleDateString(undefined, { weekday: 'short' }) }}
            </div>
            <div
              class="text-xs"
              :class="col.isToday ? 'font-semibold text-emerald-400' : 'text-fg-primary'"
            >
              {{ col.date.getDate() }}
            </div>
          </div>
        </div>
        <!-- Hour grid body. -->
        <div class="flex">
          <!-- Hour-label gutter. -->
          <div class="w-12 shrink-0">
            <div
              v-for="h in hours"
              :key="h"
              class="h-12 text-[10px] text-fg-muted text-right pr-1 -translate-y-1.5"
            >
              {{ formatHour(h) }}
            </div>
          </div>
          <!-- One column per visible day. -->
          <div
            v-for="col in calColumns"
            :key="col.date.toISOString()"
            class="flex-1 relative border-l border-border"
            :style="gridBodyStyle"
          >
            <!-- Hour lines. -->
            <div
              v-for="h in hours"
              :key="h"
              class="h-12 border-b border-border/40"
            />
            <!-- Live "now" line (today's column only). -->
            <div
              v-if="col.isToday"
              class="absolute left-0 right-0 border-t border-red-500/70 pointer-events-none z-20"
              :style="{ top: `${nowTopPct}%` }"
            >
              <span class="absolute -left-1 -top-[3px] w-1.5 h-1.5 rounded-full bg-red-500" />
            </div>
            <!-- Run blocks (solid, sized by duration; click → trace). -->
            <button
              v-for="(blk, bi) in col.runs"
              :key="`r${bi}`"
              type="button"
              class="absolute left-0.5 right-0.5 rounded-sm border px-1 text-[10px] text-white text-left leading-tight overflow-hidden hover:brightness-125 transition z-10"
              :class="statusBg[blk.run.status ?? ''] ?? 'bg-neutral-600/60 border-neutral-500'"
              :style="{ top: `${blk.topPct}%`, height: `${blk.heightPct}%` }"
              :title="`${blk.run.taskName ?? 'run'} · ${blk.run.status} · ${fmtRunTime(blk.run)}`"
              @click="openTraceForRun(blk.run)"
            >
              <span class="font-mono">{{ blk.run.taskName ?? 'run' }}</span>
            </button>
            <!-- Upcoming projected fires (hollow markers; paused = dimmed). -->
            <div
              v-for="(fm, fi) in col.fires"
              :key="`f${fi}`"
              class="absolute left-0.5 right-0.5 flex items-center gap-1 pointer-events-none z-10"
              :class="fm.fire.taskPaused ? 'opacity-40' : ''"
              :style="{ top: `${fm.topPct}%` }"
            >
              <span class="w-1.5 h-1.5 rounded-full border border-emerald-400 bg-surface-elevated shrink-0" />
              <span class="flex-1 border-t border-dashed border-emerald-400/50" />
              <span
                class="text-[9px] text-emerald-300/90 font-mono truncate max-w-[70%]"
                :class="fm.fire.taskPaused ? 'line-through' : ''"
                :title="`${fm.fire.taskName} · ${fm.fire.fireAt.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}${fm.fire.taskPaused ? ' · paused' : ''}`"
              >{{ fm.fire.taskName }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- PeekPanel: turn-by-turn execution trace for a clicked TaskRun (slice P). -->
    <PeekPanel
      :open="peekOpen"
      :title="peekTitle"
      :description="peekSubtitle"
      @update:open="closeTrace"
    >
      <p
        v-if="peekLoading"
        class="text-sm text-fg-muted"
      >
        Loading trace…
      </p>
      <p
        v-else-if="peekError"
        class="text-sm text-red-400"
      >
        {{ peekError }}
      </p>
      <p
        v-else-if="!peekMessages.length"
        class="text-sm text-fg-muted italic"
      >
        No transcript for this run (e.g. a reminder, which skips the agent).
      </p>
      <div
        v-else
        class="space-y-3"
      >
        <div class="flex justify-end">
          <button
            type="button"
            class="inline-flex items-center gap-1 px-2 py-1 text-xs bg-muted border border-input text-fg-muted hover:text-fg-strong hover:border-ring cursor-pointer"
            @click="exportTrace"
          >
            Export JSON
          </button>
        </div>
        <div
          v-for="msg in peekMessages"
          :key="msg.id"
          :class="msg.role === 'USER' ? 'ml-8' : msg.role === 'TOOL' ? 'ml-4' : ''"
        >
          <div class="flex items-center gap-2 mb-0.5">
            <span class="text-[10px] font-mono uppercase text-fg-muted">{{ msg.role }}</span>
            <span class="text-[10px] text-fg-muted">turn {{ msg.turnIndex }}</span>
            <span
              v-if="msg.truncated"
              class="text-[10px] text-amber-400"
            >truncated</span>
          </div>
          <p
            v-if="msg.reasoning"
            class="text-xs text-fg-muted italic border-l-2 border-border pl-2 mb-1 whitespace-pre-wrap break-words"
          >
            {{ msg.reasoning }}
          </p>
          <div
            v-if="msg.content"
            class="bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap break-words"
          >
            {{ msg.content }}
          </div>
          <pre
            v-if="msg.toolCalls"
            class="bg-muted border border-border px-2 py-1 text-[11px] text-fg-muted overflow-x-auto mt-1 whitespace-pre-wrap break-words"
          >{{ msg.toolCalls }}</pre>
          <pre
            v-if="msg.toolResults"
            class="bg-muted border border-border px-2 py-1 text-[11px] text-fg-muted overflow-x-auto mt-1 whitespace-pre-wrap break-words"
          >{{ msg.toolResults }}</pre>
        </div>
      </div>
    </PeekPanel>
  </div>
</template>
