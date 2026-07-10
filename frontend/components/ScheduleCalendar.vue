<script setup lang="ts">
import type { Task, RecentRunView } from '~/types/api'
import type { ProjectedFire } from '~/utils/calendar'
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/vue/24/outline'

/**
 * Shared schedule calendar (JCLAW-440), extracted from the Tasks page so the
 * Reminders page can reuse it. Renders a month grid of projected fires, or
 * week/day hourly grids.
 *
 * - `items` — the tasks or reminders to project fires for.
 * - `showRuns` (default true) — Tasks passes true: the week/day grid places
 *   actual run blocks (sized by duration, click → emit `open-run`) and only
 *   *upcoming* fire markers. Reminders passes false: no run blocks (reminders
 *   skip the LLM, so a run has no trace); the week/day grid shows fire markers
 *   for past (dimmed) and upcoming fires alike.
 */
const props = withDefaults(defineProps<{
  items: Task[]
  showRuns?: boolean
}>(), { showRuns: true })

const emit = defineEmits<{ (e: 'open-run', run: RecentRunView): void }>()

// Status → text color (month-grid fires). Mirrors the Tasks/Reminders tables.
const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-700 dark:text-yellow-400',
  ACTIVE: 'text-emerald-700 dark:text-emerald-400',
  RUNNING: 'text-blue-700 dark:text-blue-400',
  LOST: 'text-orange-700 dark:text-orange-400',
  COMPLETED: 'text-green-700 dark:text-green-400',
  FAILED: 'text-red-700 dark:text-red-400',
  CANCELLED: 'text-fg-muted',
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

// 1s tick drives the live "now" line + RUNNING run-block growth.
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

// ─────────────────────────── Calendar grid state ────────────────────────────
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

// Actual runs for the week/day grid (Tasks only). Reactive to the visible
// range; refetched on task lifecycle events so blocks stay live. Skipped
// entirely when showRuns is false (Reminders).
const calRunsUrl = computed(() =>
  `/api/task-runs/recent?from=${encodeURIComponent(calRange.value.start.toISOString())}`
  + `&to=${encodeURIComponent(calRange.value.end.toISOString())}&limit=500`)
const { data: recentRuns, refresh: refreshRecent } = useFetch<RecentRunView[]>(calRunsUrl, {
  immediate: props.showRuns,
  default: () => [] as RecentRunView[],
})
if (props.showRuns) {
  const { onEvent } = useEventBus()
  for (const evt of ['task.started', 'task.completed', 'task.failed', 'task.lost']) {
    onEvent(evt, () => void refreshRecent())
  }
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
 * 6-row × 7-col grid covering the visible month, padded so every week starts on
 * Sunday. Each cell carries its date plus the fires projected onto that day.
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
  const nowEpoch = Date.now()
  const gridEnd = new Date(gridStart)
  gridEnd.setDate(gridStart.getDate() + 42)
  const allFires: ProjectedFire[] = []
  for (const t of props.items) {
    allFires.push(...projectFires(t, gridStart, gridEnd))
  }
  for (let i = 0; i < 42; i++) {
    const d = new Date(gridStart)
    d.setDate(gridStart.getDate() + i)
    const dayStart = d.getTime()
    const dayEnd = dayStart + 24 * 60 * 60 * 1000
    const isToday = dayStart === today.getTime()
    cells.push({
      date: d,
      inMonth: d >= monthStart && d < monthEnd,
      isToday,
      isPast: dayStart < today.getTime(),
      // On today's cell, raise the lower bound to `now` so already-elapsed
      // fires drop off and the user sees only what's still upcoming today.
      fires: allFires
        .filter(f => f.fireAt.getTime() >= (isToday ? nowEpoch : dayStart)
          && f.fireAt.getTime() < dayEnd)
        .sort((a, b) => a.fireAt.getTime() - b.fireAt.getTime()),
    })
  }
  return cells
})

// ───────────────────── Week / Day hourly-grid projection ─────────────────────
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
  const runs = props.showRuns ? (recentRuns.value ?? []) : []
  const allFires: ProjectedFire[] = []
  for (const t of props.items) {
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
      const heightPct = Math.min(100 - topPct, Math.max(1.5, rawH))
      dayRuns.push({ run, topPct, heightPct })
    }
    // With runs shown, a past fire already has its run block — so only upcoming
    // fires get markers (no double-count). Without runs (Reminders), past fires
    // get markers too (dimmed) so the day still shows what fired.
    const dayFires: FireMarker[] = allFires
      .filter(f => (props.showRuns ? !f.isPast : true)
        && f.fireAt.getTime() >= dayStart && f.fireAt.getTime() < dayEnd)
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
  return run.durationMs == null ? s : `${s} · ${(run.durationMs / 1000).toFixed(1)}s`
}
</script>

<template>
  <div class="bg-surface-elevated border border-border">
    <div class="flex items-center justify-between gap-3 px-4 py-3 border-b border-border">
      <!-- Granularity: Month / Week / Day -->
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

    <!-- Week (7 cols) / Day (1 col): hourly grid. With runs (Tasks): real run
         blocks sized by duration + upcoming-fire markers. Without (Reminders):
         fire markers for past (dimmed) and upcoming. -->
    <div
      v-else
      class="overflow-auto max-h-[70vh]"
    >
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
            :class="col.isToday ? 'font-semibold text-emerald-700 dark:text-emerald-400' : 'text-fg-primary'"
          >
            {{ col.date.getDate() }}
          </div>
        </div>
      </div>
      <div class="flex">
        <div class="w-12 shrink-0">
          <div
            v-for="h in hours"
            :key="h"
            class="h-12 text-[10px] text-fg-muted text-right pr-1 -translate-y-1.5"
          >
            {{ formatHour(h) }}
          </div>
        </div>
        <div
          v-for="col in calColumns"
          :key="col.date.toISOString()"
          class="flex-1 relative border-l border-border"
          :style="gridBodyStyle"
        >
          <div
            v-for="h in hours"
            :key="h"
            class="h-12 border-b border-border/40"
          />
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
            @click="emit('open-run', blk.run)"
          >
            <span class="font-mono">{{ blk.run.taskName ?? 'run' }}</span>
          </button>
          <!-- Projected fire markers (hollow; past / paused dimmed). -->
          <div
            v-for="(fm, fi) in col.fires"
            :key="`f${fi}`"
            class="absolute left-0.5 right-0.5 flex items-center gap-1 pointer-events-none z-10"
            :class="(fm.fire.taskPaused || fm.fire.isPast) ? 'opacity-40' : ''"
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
</template>
