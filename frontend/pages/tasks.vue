<script setup lang="ts">
import type { Task } from '~/types/api'
import { ArrowPathIcon, NoSymbolIcon, TrashIcon } from '@heroicons/vue/24/outline'

const statusFilter = ref('')
const typeFilter = ref('')

const url = computed(() => {
  const params = new URLSearchParams()
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (typeFilter.value) params.set('type', typeFilter.value)
  params.set('limit', '50')
  return `/api/tasks?${params}`
})

const { data: tasks, refresh } = await useFetch<Task[]>(url)
const { mutate } = useApiMutation()
const { confirm } = useConfirm()

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

/**
 * The Status column displays "ACTIVE" instead of "PENDING" for recurring
 * tasks (CRON / INTERVAL) — these are ongoing schedules, not work that's
 * waiting to start, so "PENDING" reads incorrectly. The backend enum stays
 * Status.PENDING; this is a display-only rename keyed on Task.Type.
 */
function displayStatus(task: Task): string {
  if (task.status === 'PENDING' && (task.type === 'CRON' || task.type === 'INTERVAL')) {
    return 'ACTIVE'
  }
  return task.status
}

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
    sec = parts[0]!; min = parts[1]!; hour = parts[2]!
    dom = parts[3]!; mon = parts[4]!; dow = parts[5]!
  }
  else if (parts.length === 5) {
    min = parts[0]!; hour = parts[1]!; dom = parts[2]!; mon = parts[3]!; dow = parts[4]!
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

// A11y: stable ids for filter selects
const statusSelectId = useId()
const typeSelectId = useId()
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Tasks
      </h1>
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

    <!-- Filters -->
    <div class="flex gap-3 mb-4">
      <label :for="statusSelectId">
        <span class="sr-only">Status filter</span>
        <select
          :id="statusSelectId"
          v-model="statusFilter"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
          <option value="">
            All statuses
          </option>
          <option
            v-for="s in ['PENDING', 'RUNNING', 'LOST', 'COMPLETED', 'FAILED', 'CANCELLED']"
            :key="s"
            :value="s"
          >
            {{ s }}
          </option>
        </select>
      </label>
      <label :for="typeSelectId">
        <span class="sr-only">Type filter</span>
        <select
          :id="typeSelectId"
          v-model="typeFilter"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
          <option value="">
            All types
          </option>
          <option
            v-for="t in ['IMMEDIATE', 'SCHEDULED', 'INTERVAL', 'CRON']"
            :key="t"
            :value="t"
          >
            {{ t }}
          </option>
        </select>
      </label>
    </div>

    <div class="bg-surface-elevated border border-border">
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
          <tr
            v-for="task in tasks"
            :key="task.id"
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
              {{ task.name }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted font-mono text-xs">
              {{ task.type }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">
              {{ humanSchedule(task) }}
            </td>
            <td class="px-4 py-2.5">
              <span
                :class="statusColors[displayStatus(task)]"
                class="text-xs font-mono"
              >{{ displayStatus(task) }}</span>
            </td>
            <td class="px-4 py-2.5 text-fg-muted">
              {{ task.agentName || '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">
              {{ task.nextRunAt ? new Date(task.nextRunAt).toLocaleString() : '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">
              {{ task.retryCount }}/{{ task.maxRetries }}
            </td>
            <td class="px-4 py-2.5 text-right">
              <div class="inline-flex items-center gap-1">
                <button
                  v-if="!selectMode && task.status === 'PENDING'"
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
        </tbody>
      </table>
      <div
        v-if="!tasks?.length"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No tasks found
      </div>
    </div>
  </div>
</template>
