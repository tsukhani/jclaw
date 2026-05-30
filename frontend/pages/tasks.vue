<script setup lang="ts">
import type { Task, TaskRunView, TaskRunMessageView } from '~/types/api'
import {
  ArrowDownIcon,
  ArrowPathIcon,
  ArrowUpIcon,
  CalendarDaysIcon,
  CheckIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  NoSymbolIcon,
  PencilSquareIcon,
  PlusIcon,
  Squares2X2Icon,
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
}

// Three layouts: dense table (default), card grid, month calendar. Persisted
// to the URL so refresh / back-forward / shareable links keep the same view.
type View = 'table' | 'cards' | 'calendar'
const route = useRoute()
const router = useRouter()
const view = computed<View>({
  get() {
    const q = route.query.view
    return q === 'cards' || q === 'calendar' ? q : 'table'
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

async function retryTask(id: number) {
  await mutate(`/api/tasks/${id}/retry`, { method: 'POST' })
  refresh()
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

async function toggleExpand(id: number) {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id
  if (runsByTask[id] !== undefined || runsLoading[id]) return
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
// Clicking a run in the history opens the shared PeekPanel and lazy-loads
// that run's task_run_message rows (turn-by-turn trace) from
// /api/task-runs/{id}/messages.
const peekOpen = ref(false)
const peekRun = ref<TaskRunView | null>(null)
const peekMessages = ref<TaskRunMessageView[]>([])
const peekLoading = ref(false)
const peekError = ref<string | null>(null)

async function openTrace(run: TaskRunView) {
  peekRun.value = run
  peekOpen.value = true
  peekMessages.value = []
  peekError.value = null
  peekLoading.value = true
  try {
    peekMessages.value = await $fetch<TaskRunMessageView[]>(`/api/task-runs/${run.id}/messages`)
  }
  catch (e) {
    peekError.value = e instanceof Error ? e.message : 'Failed to load trace'
  }
  finally {
    peekLoading.value = false
  }
}

function closeTrace() {
  peekOpen.value = false
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
      if (!Number.isNaN(n)) return `every ${n === 1 ? '1 hour' : `${n} hours`}`
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
      const [lo, hi] = base === '*'
        ? [min, max]
        : base.includes('-')
          ? base.split('-').map(n => Number.parseInt(n, 10)) as [number, number]
          : [Number.parseInt(base, 10), max]
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

const calendarMonth = ref(startOfMonth(new Date()))

function startOfMonth(d: Date): Date {
  const r = new Date(d)
  r.setDate(1)
  r.setHours(0, 0, 0, 0)
  return r
}

function nextMonthFrom(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth() + 1, 1)
}

function prevMonth() {
  calendarMonth.value = new Date(calendarMonth.value.getFullYear(), calendarMonth.value.getMonth() - 1, 1)
}
function nextMonth() {
  calendarMonth.value = nextMonthFrom(calendarMonth.value)
}
function thisMonth() {
  calendarMonth.value = startOfMonth(new Date())
}

const calendarTitle = computed(() =>
  calendarMonth.value.toLocaleDateString(undefined, { year: 'numeric', month: 'long' }),
)

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

// (JCLAW-304: status/type select ids were retired with their dropdowns.
// FilterBar manages its own ARIA labels on the underlying input.)
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <div class="flex items-baseline gap-3">
        <h1 class="text-lg font-semibold text-fg-strong">
          Tasks
        </h1>
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
      </div>
      <div class="flex items-center gap-2">
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

    <!-- Filters + view switcher. JCLAW-304 replaces the legacy status/type
         select dropdowns with a FilterBar consistent with /conversations
         and /subagents. The bar's `q:KEYWORD` token drives FTS over the
         TASK Lucene scope (task.name + task.description virtual doc);
         `status:`, `type:`, and `agent:` are equality keys the backend
         intersects with the FTS hit set. -->
    <div class="flex flex-wrap items-center gap-3 mb-4">
      <div class="flex-1 min-w-[280px]">
        <FilterBar
          storage-key="tasks"
          placeholder="Filter... (e.g., q:summary status:PENDING type:CRON)"
          :filter-keys="['q', 'status', 'type', 'agent']"
          @update:filters="onFiltersChanged"
        />
      </div>
      <!-- View switcher: table / cards / calendar. State persists in URL
           (?view=cards|calendar) so refresh and shareable links survive. -->
      <div
        class="ml-auto inline-flex border border-input divide-x divide-input"
        role="tablist"
        aria-label="Task view"
      >
        <button
          v-for="opt in ([
            { id: 'table', label: 'Table', icon: TableCellsIcon },
            { id: 'cards', label: 'Cards', icon: Squares2X2Icon },
            { id: 'calendar', label: 'Calendar', icon: CalendarDaysIcon },
          ] as const)"
          :key="opt.id"
          type="button"
          role="tab"
          :aria-selected="view === opt.id"
          :title="`${opt.label} view`"
          class="px-3 py-1.5 inline-flex items-center gap-1.5 text-xs transition-colors"
          :class="view === opt.id
            ? 'bg-muted text-fg-strong'
            : 'text-fg-muted hover:text-fg-strong'"
          @click="view = opt.id"
        >
          <component
            :is="opt.icon"
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
          {{ opt.label }}
        </button>
      </div>
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
                  <button
                    v-if="!selectMode && (task.status === 'PENDING' || task.status === 'ACTIVE')"
                    type="button"
                    class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                    :title="task.type === 'CRON' || task.type === 'INTERVAL'
                      ? `Stop recurring schedule for “${task.name}”`
                      : `Cancel “${task.name}”`"
                    :aria-label="`Cancel ${task.name}`"
                    @click.stop="cancelTask(task.id)"
                  >
                    <NoSymbolIcon
                      class="w-4 h-4"
                      aria-hidden="true"
                    />
                  </button>
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

    <!-- Cards view: one card per task. Same data shape as the table —
         denser per-card layout, friendlier on wide screens. -->
    <div
      v-else-if="view === 'cards'"
    >
      <div
        v-if="!tasks?.length"
        class="bg-surface-elevated border border-border px-4 py-8 text-center text-sm text-fg-muted"
      >
        No tasks found
      </div>
      <div
        v-else
        class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3"
      >
        <div
          v-for="task in tasks"
          :key="task.id"
          class="bg-surface-elevated border border-border p-4 flex flex-col gap-3"
        >
          <div class="flex items-start justify-between gap-2">
            <div class="min-w-0">
              <div class="text-sm font-medium text-fg-strong truncate">
                {{ task.name }}
              </div>
              <div class="text-xs text-fg-muted font-mono mt-0.5">
                {{ task.type }} · {{ humanSchedule(task) }}
              </div>
            </div>
            <span
              :class="statusColors[task.status]"
              class="text-[10px] font-mono shrink-0"
            >{{ task.status }}</span>
          </div>
          <dl class="text-xs grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <dt class="text-fg-muted">
              Agent
            </dt>
            <dd class="text-fg-primary truncate">
              {{ task.agentName || '—' }}
            </dd>
            <dt class="text-fg-muted">
              Next run
            </dt>
            <dd class="text-fg-primary">
              {{ task.nextRunAt ? formatTaskTimestamp(task.nextRunAt as string, zoneForTaskRender(task)) : '—' }}
            </dd>
            <dt class="text-fg-muted">
              Retries
            </dt>
            <dd class="text-fg-primary">
              {{ task.retryCount }}/{{ task.maxRetries }}
            </dd>
          </dl>
          <div class="flex items-center justify-end gap-1 pt-1 border-t border-border">
            <button
              v-if="task.status === 'PENDING' || task.status === 'ACTIVE'"
              type="button"
              class="p-1 text-fg-muted hover:text-red-400 transition-colors"
              :title="task.type === 'CRON' || task.type === 'INTERVAL'
                ? `Stop recurring schedule for “${task.name}”`
                : `Cancel “${task.name}”`"
              :aria-label="`Cancel ${task.name}`"
              @click="cancelTask(task.id)"
            >
              <NoSymbolIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
            <button
              v-if="task.status === 'FAILED' || task.status === 'LOST'"
              type="button"
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              :title="`Retry “${task.name}”`"
              :aria-label="`Retry ${task.name}`"
              @click="retryTask(task.id)"
            >
              <ArrowPathIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
            <button
              type="button"
              class="p-1 text-fg-muted hover:text-red-400 transition-colors"
              :title="`Permanently delete “${task.name}” and its run history`"
              :aria-label="`Delete ${task.name}`"
              @click="deleteTask(task)"
            >
              <TrashIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Calendar view: month grid with projected fires. CRON expansion
         covers the patterns humanCron understands; INTERVAL steps from
         nextRunAt; SCHEDULED / IMMEDIATE pin to their nextRunAt date.
         Unrecognized cron expressions just show the next fire so the
         operator at least knows something is scheduled. -->
    <div
      v-else
      class="bg-surface-elevated border border-border"
    >
      <div class="flex items-center justify-between px-4 py-3 border-b border-border">
        <div class="text-sm font-medium text-fg-strong">
          {{ calendarTitle }}
        </div>
        <div class="inline-flex items-center gap-1">
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            title="Previous month"
            aria-label="Previous month"
            @click="prevMonth"
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
            @click="thisMonth"
          >
            Today
          </button>
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            title="Next month"
            aria-label="Next month"
            @click="nextMonth"
          >
            <ChevronRightIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
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
              ]"
              :title="`${fire.taskName} · ${fire.fireAt.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit', hour12: true })}`"
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
    </div>

    <!-- PeekPanel: turn-by-turn execution trace for a clicked TaskRun (slice P). -->
    <PeekPanel
      :open="peekOpen"
      :title="peekRun ? `Run trace — ${peekRun.status ?? ''}` : 'Run trace'"
      :description="peekRun && peekRun.startedAt ? new Date(peekRun.startedAt).toLocaleString() : ''"
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
