<script setup lang="ts">
import type { Task, TaskRunView, TaskRunMessageView, TranscriptSearchHit, TaskStats, RecentRunView } from '~/types/api'
import {
  ArrowDownIcon,
  ArrowPathIcon,
  ArrowUpIcon,
  BoltIcon,
  CalendarDaysIcon,
  CheckIcon,
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
    router.replace({ query: { ...route.query, view: v === 'table' ? undefined : v } }).catch(() => {})
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
// Exclude reminders so the KPI strip matches this page's table (which passes
// excludePayloadType=reminder) and the dashboard Tasks tile; reminder counts +
// run KPIs live on /reminders. Without this, a pending reminder showed as
// "Pending 1" here while never appearing in the list below.
const { data: stats, refresh: refreshStats } = await useFetch<TaskStats>('/api/tasks/stats?excludePayloadType=reminder')
// JCLAW-440: the calendar view (month/week/day grids, fire projection, run
// blocks) moved to the shared <ScheduleCalendar> component, which owns its own
// range state + runs fetch. This page just renders it in the calendar view.
const { mutate } = useApiMutation()
const { confirm } = useConfirm()

// JCLAW-259: surface the retention TTL in the page header so operators
// know when terminal tasks will auto-delete. The effective value rides on
// the stats payload (TaskCleanupJob.resolveRetentionDays, resolved
// server-side) — so the default lives only in the backend, the client
// never re-derives it, and there's no separate config fetch that 404s when
// the key is unset. Reactive on visit and on every stats refresh.
const retentionDisplay = computed(() => {
  const days = stats.value?.retentionDays
  if (days == null) return null
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

// Reset the run-derived KPIs (Runs Today / Success Rate / Avg Duration) by
// deleting terminal run history. In-flight runs and the task definitions
// themselves are kept; scoped to the same excludePayloadType=reminder the
// stats fetch uses so it never touches the Reminders surface.
async function resetStats() {
  const ok = await confirm({
    title: 'Reset task stats?',
    message: 'Permanently delete all completed, failed and cancelled run history (in-flight runs are kept). Runs Today, Success Rate and Avg Duration will reset.',
    confirmText: 'Reset stats',
    variant: 'danger',
  })
  if (!ok) return
  await $fetch('/api/task-runs/reset?excludePayloadType=reminder', { method: 'POST' })
  refresh()
  refreshStats()
  // refresh()/refreshStats() update the task list and KPIs, but the per-task
  // RUN HISTORY shown in an expanded panel reads from the runsByTask cache,
  // which still holds the now-deleted terminal rows. Re-pull runs for every
  // task whose history has been loaded so open panels drop the cleared rows
  // (a still-RUNNING run, spared by the reset, correctly remains).
  for (const id of Object.keys(runsByTask)) void loadRuns(Number(id), true)
}

// ── JCLAW-22 (slice D): row-expand detail — step list + TaskRun history ──
// Single-open accordion (same UX as /logs). The step list is parsed
// client-side from task.description (already in the /api/tasks payload via
// parseTaskSteps, the twin of the backend TaskSteps.parse); the run history
// is lazy-loaded from /api/tasks/{id}/runs on first expand and cached.
// Independent per-row expansion: expanding one task never collapses another.
// A reactive Set tracks the open ids; the run-history maps below are already
// keyed by task id, so each open row keeps its own history.
const expandedIds = reactive(new Set<number>())
const runsByTask = reactive<Record<number, TaskRunView[]>>({})
const runsLoading = reactive<Record<number, boolean>>({})
const runsError = reactive<Record<number, string | null>>({})

// JCLAW-455: per-task Slack delivery-reachability advisory, fetched lazily on
// expand (and refreshed after a delivery edit). null = reachable / not applicable.
const deliveryAdvisories = reactive<Record<number, string | null>>({})

// force=true re-probes even if already fetched (used after a delivery edit).
async function loadDeliveryAdvisory(id: number, force = false) {
  if (!force && deliveryAdvisories[id] !== undefined) return
  try {
    const r = await $fetch<{ advisory: string | null }>(`/api/tasks/${id}/delivery-advisory`)
    deliveryAdvisories[id] = r.advisory
  }
  catch {
    // Best-effort preflight — a probe failure must never block the page.
    deliveryAdvisories[id] = null
  }
}

async function loadRuns(id: number, silent = false) {
  if (!silent) runsLoading[id] = true
  runsError[id] = null
  try {
    runsByTask[id] = await $fetch<TaskRunView[]>(`/api/tasks/${id}/runs?limit=20`)
  }
  catch (e) {
    // A silent poll failure is transient — keep the last good history rather
    // than flashing an error over a row the user is watching.
    if (!silent) runsError[id] = e instanceof Error ? e.message : 'Failed to load run history'
  }
  finally {
    if (!silent) runsLoading[id] = false
  }
}

function toggleExpand(id: number) {
  if (expandedIds.has(id)) {
    expandedIds.delete(id)
    return
  }
  expandedIds.add(id)
  // Lazy-load the run history on first expand; the live SSE refresh
  // (slice L) reuses loadRuns to keep each open task's history current.
  if (runsByTask[id] === undefined && !runsLoading[id]) {
    void loadRuns(id)
  }
  // JCLAW-455: probe Slack delivery reachability on first expand.
  void loadDeliveryAdvisory(id)
}

// Steps for an expanded row, parsed from its description. Called only inside
// the expanded detail block (guarded by expandedIds.has), so the parse cost
// is bounded to the handful of open rows.
function stepsFor(task: Task): string[] {
  return parseTaskSteps(task.description)
}

// colspan for the expanded detail row: every visible column, including the
// leading checkbox column when bulk-select is active. JCLAW-420 added the
// Channel column, so the base count is 9 (10 with the checkbox column).
const tableColspan = computed(() => (selectMode.value ? 10 : 9))

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

// ── JCLAW-426: inline name editor ──
// Same expanded-row model as the step editor, scoped to one task. Name is
// required (unlike description), so an empty value is blocked client-side
// before the PATCH; the backend re-validates and 400s on blank.
const editingNameId = ref<number | null>(null)
const editName = ref('')
const savingName = ref(false)
const nameError = ref<string | null>(null)

function startEditName(task: Task) {
  editName.value = task.name
  nameError.value = null
  editingNameId.value = task.id
}

function cancelEditName() {
  editingNameId.value = null
  editName.value = ''
  nameError.value = null
}

async function saveName(task: Task) {
  const name = editName.value.trim()
  if (!name) {
    nameError.value = 'Name cannot be empty'
    return
  }
  savingName.value = true
  nameError.value = null
  try {
    await $fetch(`/api/tasks/${task.id}`, { method: 'PATCH', body: { name } })
    editingNameId.value = null
    editName.value = ''
    refresh()
  }
  catch (e) {
    nameError.value = e instanceof Error ? e.message : 'Failed to save name'
  }
  finally {
    savingName.value = false
  }
}

// ── JCLAW-420: inline delivery (output channel) editor ──
// Same shape as the step editor above, scoped to the expanded row. The text
// input takes a raw grammar string (`tool:send_gmail_message`, `telegram:123`,
// `none`, …); the backend re-validates it (JCLAW-417) and returns 400 with a
// message on a bad value, which we surface verbatim. On success we refresh so
// the read-only deliveryLabel reflects the new value.
const editingDeliveryId = ref<number | null>(null)
const editDelivery = ref('')
const savingDelivery = ref(false)
const deliveryError = ref<string | null>(null)

function startEditDelivery(task: Task) {
  editDelivery.value = typeof task.delivery === 'string' ? task.delivery : ''
  deliveryError.value = null
  editingDeliveryId.value = task.id
}

function cancelEditDelivery() {
  editingDeliveryId.value = null
  editDelivery.value = ''
  deliveryError.value = null
}

async function saveDelivery(task: Task) {
  savingDelivery.value = true
  deliveryError.value = null
  try {
    const delivery = editDelivery.value.trim()
    await $fetch(`/api/tasks/${task.id}`, { method: 'PATCH', body: { delivery } })
    editingDeliveryId.value = null
    editDelivery.value = ''
    refresh()
    // JCLAW-455: the target changed — re-probe so the advisory reflects the new channel.
    void loadDeliveryAdvisory(task.id, true)
  }
  catch (e) {
    // $fetch surfaces the backend's 400 body on e.data; prefer its message so
    // the operator sees the validation reason rather than a generic "400".
    const data = (e as { data?: { error?: string } }).data
    deliveryError.value = data?.error ?? (e instanceof Error ? e.message : 'Failed to save channel')
  }
  finally {
    savingDelivery.value = false
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
// The run open in the trace panel, plus its owning task (when opened from a
// history row) — the live poll uses these to keep the run's status fresh and
// stream its trace while it's still RUNNING.
const peekRunId = ref<number | null>(null)
const peekTaskId = ref<number | null>(null)

async function loadTrace(runId: number, title: string, subtitle: string, taskId: number | null = null) {
  peekRunId.value = runId
  peekTaskId.value = taskId
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

// Silent re-fetch of the open trace (no spinner, no clear) — the live poll uses
// it so new turns of a RUNNING run append in place as the fire proceeds.
async function refreshOpenTrace() {
  if (peekRunId.value == null) return
  try {
    peekMessages.value = await $fetch<TaskRunMessageView[]>(`/api/task-runs/${peekRunId.value}/messages`)
  }
  catch { /* transient — the next tick retries */ }
}

// Whether the open run is still in flight, derived from the loaded run lists so
// it self-corrects the instant a refresh flips the run terminal.
const peekRunIsRunning = computed(() => {
  if (peekRunId.value == null) return false
  for (const runs of Object.values(runsByTask)) {
    const hit = runs.find(r => r.id === peekRunId.value)
    if (hit) return hit.status === 'RUNNING'
  }
  return false
})

function openTrace(run: TaskRunView, taskId?: number) {
  const when = run.startedAt ? new Date(run.startedAt).toLocaleString() : ''
  void loadTrace(run.id, `Run trace — ${run.status ?? ''}`, when, taskId ?? null)
}

function closeTrace() {
  peekOpen.value = false
  peekRunId.value = null
  peekTaskId.value = null
}

// When the open run finishes (RUNNING → terminal) pull the final trace once, so
// the last turns written between polls aren't missed.
watch(peekRunIsRunning, (running, wasRunning) => {
  if (wasRunning && !running && peekOpen.value) void refreshOpenTrace()
})

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
let liveTraceHandle: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  tickHandle = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
  // Live trace poll: the SSE bus emits only lifecycle events (started /
  // completed / …), not the per-turn growth of an in-flight run. So while a
  // RUNNING run is on screen — expanded in the history, or open in the trace
  // panel — re-fetch it every 2s (silently) so its partial clip and trace fill
  // in as the fire proceeds.
  liveTraceHandle = setInterval(() => {
    void liveTracePoll()
  }, 2000)
})
onUnmounted(() => {
  if (tickHandle) clearInterval(tickHandle)
  if (liveTraceHandle) clearInterval(liveTraceHandle)
})

async function liveTracePoll() {
  const taskIds = new Set<number>(expandedIds)
  if (peekOpen.value && peekTaskId.value != null) taskIds.add(peekTaskId.value)
  for (const id of taskIds) {
    if (runsByTask[id]?.some(r => r.status === 'RUNNING')) void loadRuns(id, true)
  }
  if (peekOpen.value && peekRunIsRunning.value) void refreshOpenTrace()
}

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
    if (editingId.value != null || editingDeliveryId.value != null || editingNameId.value != null) return
    refresh()
    refreshStats()
    for (const id of expandedIds) void loadRuns(id)
  }, 400)
}

for (const evt of ['task.started', 'task.completed', 'task.failed', 'task.delivered', 'task.delivery_failed', 'task.lost']) {
  onEvent(evt, scheduleLiveRefresh)
}

// LOST sits between RUNNING (blue) and FAILED (red) on the heat axis —
// the task is stuck but db-scheduler will auto-recover it. Orange
// communicates "needs attention but not yet a terminal failure".
const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-700 dark:text-yellow-400',
  ACTIVE: 'text-emerald-700 dark:text-emerald-400',
  RUNNING: 'text-blue-700 dark:text-blue-400',
  LOST: 'text-orange-700 dark:text-orange-400',
  COMPLETED: 'text-green-700 dark:text-green-400',
  FAILED: 'text-red-700 dark:text-red-400',
  CANCELLED: 'text-fg-muted',
}

// v0.12.38: ACTIVE was promoted from a frontend display-only alias to a
// real Task.Status enum value. Recurring tasks now arrive with status =
// "ACTIVE" directly from the API, so we no longer need a per-row mapping
// helper. statusColors[task.status] resolves correctly for both PENDING
// (one-shot waiting) and ACTIVE (recurring ongoing).

/**
 * JCLAW-420: humanize a task's `delivery` (output channel) for display,
 * mirroring the JCLAW-417 backend grammar 3 states:
 *   - `tool:<name>`               → the tool name (agent self-delivers inline)
 *   - null / blank / `none`       → "none" (no delivery)
 *   - `<channel>:<target>` / bare → the channel name (telegram/slack/whatsapp/web;
 *                                    `email:` kept as a legacy-row alias)
 * The `delivery` field rides the Task index signature (not a declared
 * property), so it's narrowed to a string before parsing.
 */
function deliveryLabel(task: Task): string {
  const raw = typeof task.delivery === 'string' ? task.delivery.trim() : ''
  if (!raw || raw.toLowerCase() === 'none') return 'none'
  if (raw.startsWith('tool:')) return raw.slice('tool:'.length)
  const colon = raw.indexOf(':')
  const channel = colon === -1 ? raw : raw.slice(0, colon)
  return channel
}

// JCLAW-438: humanSchedule / humanCron / humanDuration / formatTime12h moved
// to utils/schedule.ts (auto-imported) so the Reminders page shares the same
// cron/interval humanizers instead of duplicating ~50 lines.

/**
 * JCLAW-261: which IANA zone should the Next Run column render this
 * task's timestamp in? CRON / SCHEDULED carry a meaningful per-task
 * (or default-resolved) zone; INTERVAL and IMMEDIATE are duration-
 * based and have no wall-clock binding — render those in the browser's
 * local zone (return undefined to fall through formatDateTime's
 * default behavior). The datetime formatter itself (formatDateTime) is
 * shared from utils/schedule.ts.
 */
function zoneForTaskRender(task: Task): string | undefined {
  if (task.type !== 'CRON' && task.type !== 'SCHEDULED') return undefined
  return task.effectiveTimezone ?? undefined
}

// (JCLAW-304: status/type select ids were retired with their dropdowns.
// FilterBar manages its own ARIA labels on the underlying input.)
</script>

<template>
  <div>
    <!-- Three zones: title (left), retention TTL (dead-centered via the auto
         middle column flanked by equal 1fr sides), action icons (right). The
         1fr/auto/1fr grid centers the label on the row regardless of the left
         and right group widths. -->
    <div class="grid grid-cols-[1fr_auto_1fr] items-center mb-6">
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
      <!-- JCLAW-259: live retention TTL, centered on the row. Subtle muted text
           so it doesn't compete with the page title but stays visible enough
           that operators don't get surprised by auto-deletes. Sourced from
           tasks.retentionDays (default 30, 0 = disabled). Wrapper is always
           present so it holds the center grid column even when the link is
           hidden (retentionDays null). -->
      <div class="flex justify-center">
        <NuxtLink
          v-if="retentionDisplay"
          to="/settings?section=tasks"
          class="inline-flex items-center leading-none text-xs text-fg-muted hover:text-fg-strong transition-colors"
          title="Configure in Settings → Tasks"
        >
          {{ retentionDisplay }}
        </NuxtLink>
      </div>
      <div class="flex items-center gap-2 justify-self-end">
        <template v-if="!selectMode">
          <button
            :disabled="!tasks?.length"
            class="p-2 border border-input text-fg-muted hover:text-fg-strong hover:border-neutral-500 disabled:opacity-40 disabled:hover:text-fg-muted disabled:hover:border-input transition-colors"
            title="Reset stats — delete completed/failed/cancelled run history and reset the run KPIs"
            aria-label="Reset stats"
            @click="resetStats"
          >
            <ArrowPathIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
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
      class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-7 gap-2 mb-4"
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
          Running
        </div>
        <div
          class="text-lg font-semibold"
          :class="stats.runningCount > 0 ? 'text-blue-700 dark:text-blue-400' : 'text-fg-strong'"
        >
          {{ stats.runningCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Active
        </div>
        <div
          class="text-lg font-semibold"
          :class="stats.activeCount > 0 ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-strong'"
        >
          {{ stats.activeCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Pending
        </div>
        <div
          class="text-lg font-semibold"
          :class="stats.pendingCount > 0 ? 'text-yellow-700 dark:text-yellow-400' : 'text-fg-strong'"
        >
          {{ stats.pendingCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Failed
        </div>
        <div
          class="text-lg font-semibold"
          :class="stats.failedCount > 0 ? 'text-red-700 dark:text-red-400' : 'text-fg-strong'"
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
        class="px-4 py-6 text-center text-sm text-red-700 dark:text-red-400"
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
      <!-- table-fixed + per-column header widths: with auto layout the
           expanded detail row (a wide colspan cell) re-balanced every column
           on expand/collapse. Fixed layout sizes columns from the header row
           alone, so the detail content can't shift them. Widths sum to 100%
           (the select-mode checkbox th keeps its own w-8). -->
      <table class="w-full text-sm table-fixed">
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
            <th class="px-4 py-2.5 font-medium w-[18%]">
              Name
            </th>
            <th class="px-4 py-2.5 font-medium w-[6%]">
              Type
            </th>
            <th class="px-4 py-2.5 font-medium w-[19.5%]">
              Schedule
            </th>
            <th class="px-4 py-2.5 font-medium w-[7%]">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium w-[6%]">
              Agent
            </th>
            <th class="px-4 py-2.5 font-medium w-[8%]">
              Channel
            </th>
            <th class="px-4 py-2.5 font-medium w-[19.5%]">
              Next Run
            </th>
            <th class="px-4 py-2.5 font-medium w-[6%]">
              Retries
            </th>
            <th class="px-4 py-2.5 font-medium text-right w-[10%]">
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
                  :aria-expanded="expandedIds.has(task.id)"
                  :aria-label="`Toggle details for ${task.name}`"
                  @click.stop="toggleExpand(task.id)"
                >
                  <ChevronRightIcon
                    :class="expandedIds.has(task.id) ? 'rotate-90' : ''"
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
              <td class="px-4 py-2.5 text-fg-muted font-mono text-xs">
                {{ deliveryLabel(task) }}
              </td>
              <td class="px-4 py-2.5 text-fg-muted text-xs">
                {{ task.nextRunAt ? formatDateTime(task.nextRunAt as string, zoneForTaskRender(task)) : '—' }}
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
                  <!-- Recurring + a run in flight → Cancel this run (JCLAW-414).
                       Gated on runningRunId, NOT isLive: a recurring task mid-fire
                       is in the RUNNING state (which isLive excludes, since pause
                       only applies to PENDING/ACTIVE), yet that is exactly when a
                       run exists to cancel. cancelRun's backend precondition is
                       run.status == RUNNING, which runningRunId already implies. -->
                  <button
                    v-else-if="!selectMode && isRecurring(task) && task.runningRunId"
                    type="button"
                    class="p-1 text-red-700 dark:text-red-400 hover:text-red-300 transition-colors"
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
                    class="p-1 text-emerald-700 dark:text-emerald-400 hover:text-emerald-300 transition-colors"
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
              v-if="expandedIds.has(task.id)"
              class="bg-muted/20"
            >
              <td
                :colspan="tableColspan"
                class="px-4 py-3"
              >
                <div class="grid gap-6 md:grid-cols-2">
                  <!-- Left column: Instructions stacked over the JCLAW-420
                       Channel editor; Run history fills the right column. -->
                  <div class="space-y-6">
                    <!-- JCLAW-426: task name, read-only with an inline editor
                     behind Edit. Name is required — the editor blocks an empty
                     value before PATCH; the backend re-validates and 400s. -->
                    <section>
                      <div class="flex items-center justify-between mb-1.5">
                        <div class="text-[10px] uppercase tracking-wider font-medium text-fg-muted">
                          Name
                        </div>
                        <button
                          v-if="editingNameId !== task.id"
                          type="button"
                          class="inline-flex items-center gap-1 text-[10px] text-fg-muted hover:text-fg-strong transition-colors bg-transparent border-0 cursor-pointer"
                          @click="startEditName(task)"
                        >
                          <PencilSquareIcon
                            class="h-3 w-3"
                            aria-hidden="true"
                          />
                          Edit
                        </button>
                      </div>

                      <!-- Read-only -->
                      <p
                        v-if="editingNameId !== task.id"
                        class="text-xs text-fg-primary"
                      >
                        {{ task.name }}
                      </p>

                      <!-- Inline editor -->
                      <div
                        v-else
                        class="space-y-2"
                      >
                        <input
                          v-model="editName"
                          type="text"
                          aria-label="Task name"
                          placeholder="Task name…"
                          class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden focus:border-ring transition-colors"
                          @keyup.enter="saveName(task)"
                        >
                        <div class="flex items-center gap-2 pt-0.5">
                          <span class="flex-1" />
                          <button
                            type="button"
                            :disabled="savingName"
                            class="px-2 py-1 text-xs bg-muted border border-input text-fg-muted hover:text-fg-strong disabled:opacity-50 cursor-pointer"
                            @click="cancelEditName"
                          >
                            Cancel
                          </button>
                          <button
                            type="button"
                            :disabled="savingName"
                            class="inline-flex items-center gap-1 px-2 py-1 text-xs bg-muted border border-emerald-600 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-600/10 disabled:opacity-50 cursor-pointer"
                            @click="saveName(task)"
                          >
                            <CheckIcon
                              class="h-3.5 w-3.5"
                              aria-hidden="true"
                            />
                            {{ savingName ? 'Saving…' : 'Save' }}
                          </button>
                        </div>
                        <p
                          v-if="nameError"
                          class="text-xs text-red-700 dark:text-red-400"
                        >
                          {{ nameError }}
                        </p>
                      </div>
                    </section>

                    <!-- Instructions: the JCLAW-260 step list, read-only by
                     default with an inline editor (slice E) behind Edit. -->
                    <section>
                      <div class="flex items-center justify-between mb-1.5">
                        <div class="text-[10px] uppercase tracking-wider font-medium text-fg-muted">
                          Instructions
                        </div>
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

                      <!-- Read-only view (numbered when multi-step, verbatim for one).
                           steps parsed per-row now that several rows can be open. -->
                      <template v-if="editingId !== task.id">
                        <ol
                          v-if="stepsFor(task).length > 1"
                          class="list-decimal list-inside space-y-0.5 text-xs text-fg-primary"
                        >
                          <li
                            v-for="(step, i) in stepsFor(task)"
                            :key="i"
                          >
                            {{ step }}
                          </li>
                        </ol>
                        <p
                          v-else-if="stepsFor(task).length === 1"
                          class="text-xs text-fg-primary whitespace-pre-wrap"
                        >
                          {{ stepsFor(task)[0] }}
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
                              class="w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-fg-strong disabled:opacity-30 disabled:cursor-not-allowed"
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
                              class="w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-fg-strong disabled:opacity-30 disabled:cursor-not-allowed"
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
                              class="w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-red-400"
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
                            class="inline-flex items-center gap-1 px-2 py-1 text-xs bg-muted border border-emerald-600 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-600/10 disabled:opacity-50 cursor-pointer"
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
                          class="text-xs text-red-700 dark:text-red-400"
                        >
                          {{ stepsError }}
                        </p>
                      </div>
                    </section>
                    <!-- JCLAW-420: delivery (output channel), read-only with an
                     inline editor behind Edit. The text input takes a raw
                     JCLAW-417 grammar string (tool:NAME, telegram:ID, none, …);
                     the backend re-validates on PATCH and 400s with a message. -->
                    <section>
                      <div class="flex items-center justify-between mb-1.5">
                        <div class="text-[10px] uppercase tracking-wider font-medium text-fg-muted">
                          Channel
                        </div>
                        <button
                          v-if="editingDeliveryId !== task.id"
                          type="button"
                          class="inline-flex items-center gap-1 text-[10px] text-fg-muted hover:text-fg-strong transition-colors bg-transparent border-0 cursor-pointer"
                          @click="startEditDelivery(task)"
                        >
                          <PencilSquareIcon
                            class="h-3 w-3"
                            aria-hidden="true"
                          />
                          Edit
                        </button>
                      </div>

                      <!-- Read-only view: the humanized 3-state label. -->
                      <p
                        v-if="editingDeliveryId !== task.id"
                        class="text-xs text-fg-primary font-mono"
                      >
                        {{ deliveryLabel(task) }}
                      </p>

                      <!-- JCLAW-455: preflight reachability advisory for a Slack channel the
                       bot can't reach (private/uninvited). Lazily fetched on expand. -->
                      <p
                        v-if="editingDeliveryId !== task.id && deliveryAdvisories[task.id]"
                        class="mt-1 flex items-start gap-1 text-[11px] text-amber-700 dark:text-amber-400"
                      >
                        <span aria-hidden="true">⚠</span>
                        <span>{{ deliveryAdvisories[task.id] }}</span>
                      </p>

                      <!-- Inline editor: raw grammar string → PATCH delivery. Explicit
                       v-if (not v-else): the JCLAW-455 advisory <p> above sits between this
                       and the read-only label, so a v-else would bind to the advisory's
                       condition and render the editor in read-only mode. -->
                      <div
                        v-if="editingDeliveryId === task.id"
                        class="space-y-2"
                      >
                        <input
                          v-model="editDelivery"
                          type="text"
                          aria-label="Delivery channel"
                          placeholder="e.g. slack:#daily-briefings, telegram:123, tool:send_gmail_message, none"
                          class="w-full px-2 py-1 bg-muted border border-input text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden focus:border-ring transition-colors"
                          @keydown.enter.prevent="saveDelivery(task)"
                        >

                        <!-- JCLAW-457: always-present, collapsible grammar helper. Native
                         <details> keeps it space-conserving (closed by default) and a11y-friendly. -->
                        <details class="text-[11px] text-fg-muted">
                          <summary class="cursor-pointer select-none hover:text-fg-strong transition-colors">
                            What can I put here?
                          </summary>
                          <div class="mt-1.5 space-y-1.5 border-l border-input pl-2.5">
                            <p>Where the task's output goes — a channel destination, an in-run tool, or nowhere:</p>
                            <ul class="space-y-1">
                              <li>
                                <code class="font-mono text-fg-strong">slack:#daily-briefings</code>
                                — a Slack channel by name (or
                                <code class="font-mono text-fg-strong">slack:C0123ABCD</code> by id)
                              </li>
                              <li>
                                <code class="font-mono text-fg-strong">telegram:12345</code>
                                — a Telegram chat id
                              </li>
                              <li>
                                <code class="font-mono text-fg-strong">whatsapp:+15551234567</code>
                                — a WhatsApp number (E.164)
                              </li>
                              <li>
                                <code class="font-mono text-fg-strong">tool:send_gmail_message</code>
                                — the agent delivers in-run via that tool
                              </li>
                              <li>
                                <code class="font-mono text-fg-strong">none</code>
                                — no delivery; the output stays in the run
                              </li>
                            </ul>
                            <p>
                              Slack private channels: the bot must be a member — add it under the
                              channel's Integrations (or
                              <code class="font-mono text-fg-strong">/invite</code> it) so it can post
                              and you get notified.
                            </p>
                          </div>
                        </details>

                        <div class="flex items-center gap-2 pt-0.5">
                          <span class="flex-1" />
                          <button
                            type="button"
                            :disabled="savingDelivery"
                            class="px-2 py-1 text-xs bg-muted border border-input text-fg-muted hover:text-fg-strong disabled:opacity-50 cursor-pointer"
                            @click="cancelEditDelivery"
                          >
                            Cancel
                          </button>
                          <button
                            type="button"
                            :disabled="savingDelivery"
                            class="inline-flex items-center gap-1 px-2 py-1 text-xs bg-muted border border-emerald-600 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-600/10 disabled:opacity-50 cursor-pointer"
                            @click="saveDelivery(task)"
                          >
                            <CheckIcon
                              class="h-3.5 w-3.5"
                              aria-hidden="true"
                            />
                            {{ savingDelivery ? 'Saving…' : 'Save' }}
                          </button>
                        </div>
                        <p
                          v-if="deliveryError"
                          class="text-xs text-red-700 dark:text-red-400"
                        >
                          {{ deliveryError }}
                        </p>
                      </div>
                    </section>
                  </div>
                  <!-- Run history: lazy-loaded TaskRuns, most-recent first. -->
                  <section>
                    <div class="text-[10px] uppercase tracking-wider font-medium text-fg-muted mb-1.5">
                      Run history
                    </div>
                    <p
                      v-if="runsLoading[task.id]"
                      class="text-xs text-fg-muted"
                    >
                      Loading…
                    </p>
                    <p
                      v-else-if="runsError[task.id]"
                      class="text-xs text-red-700 dark:text-red-400"
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
                          @click="openTrace(run, task.id)"
                        >
                          <div class="flex flex-wrap items-center gap-2">
                            <span
                              :class="statusColors[run.status ?? '']"
                              class="font-mono"
                            >{{ run.status }}</span>
                            <span class="text-fg-muted">{{ run.startedAt ? formatDateTime(run.startedAt, zoneForTaskRender(task)) : '—' }}</span>
                            <span
                              v-if="run.durationMs != null"
                              class="text-fg-muted"
                            >· {{ (run.durationMs / 1000).toFixed(1) }}s</span>
                            <span
                              v-else-if="run.status === 'RUNNING'"
                              class="text-blue-700 dark:text-blue-400"
                            >· {{ elapsedSince(run.startedAt) }} elapsed</span>
                            <span
                              v-if="run.deliveryStatus && run.deliveryStatus !== 'NOT_REQUESTED'"
                              class="text-fg-muted"
                            >· {{ run.deliveryStatus }}</span>
                          </div>
                          <p
                            v-if="run.error"
                            class="text-red-700 dark:text-red-400 mt-0.5 whitespace-pre-wrap break-words"
                          >
                            {{ run.error }}
                          </p>
                          <p
                            v-else-if="run.outputSummary"
                            class="text-fg-muted mt-0.5 whitespace-pre-wrap break-words line-clamp-3"
                          >
                            {{ run.outputSummary }}
                          </p>
                          <p
                            v-else-if="run.status === 'RUNNING' && run.latestTurnPreview"
                            class="text-blue-300/80 mt-0.5 whitespace-pre-wrap break-words line-clamp-2 italic"
                          >
                            {{ run.latestTurnPreview }}
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

    <ScheduleCalendar
      v-else-if="view === 'calendar'"
      :items="tasks ?? []"
      @open-run="openTraceForRun"
    />

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
        class="text-sm text-red-700 dark:text-red-400"
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
              class="text-[10px] text-amber-700 dark:text-amber-400"
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
