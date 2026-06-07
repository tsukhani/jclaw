<script setup lang="ts">
import type { Task, TaskStats } from '~/types/api'
import { BellAlertIcon, TrashIcon } from '@heroicons/vue/24/outline'

// BellAlertIcon retained for the empty-state placeholder; the header no
// longer carries it to match the /channels page's plain h1 convention.

definePageMeta({ title: 'Reminders' })

// Mirror the /tasks page's API shape but scope to payloadType=reminder
// (backend filter added in ApiTasksController.list). The Tasks list
// itself excludes reminders via excludePayloadType=reminder, so a given
// task row never appears on both pages.
const url = '/api/tasks?payloadType=reminder&limit=200'
const { data: reminders, refresh } = await useFetch<Task[]>(url)

// Reminder-scoped KPI strip — the same aggregate the /tasks page shows, but
// filtered to payloadType=reminder so reminder counts + run KPIs live here
// (and stay out of the Tasks page, which excludes them).
const { data: reminderStats } = await useFetch<TaskStats>('/api/tasks/stats?payloadType=reminder')

const { mutate } = useApiMutation()
const { confirm } = useConfirm()

async function deleteOne(r: Task) {
  const ok = await confirm({
    title: 'Delete reminder?',
    message: `"${reminderBody(r)}" — this is permanent.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  await mutate(`/api/tasks/${r.id}`, { method: 'DELETE' })
  await refresh()
}

// The /api/tasks Task type carries description / delivery / scheduleDisplay
// through its open `[key: string]: unknown` index signature rather than
// declared optional properties, so reads need an explicit string narrowing.
function asString(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function reminderBody(r: Task): string {
  return asString(r.description) || r.name
}

function reminderDelivery(r: Task): string {
  return asString(r.delivery)
}

function reminderScheduleDisplay(r: Task): string {
  return asString(r.scheduleDisplay)
}

function nextFireLabel(r: Task): string {
  // For COMPLETED reminders, the "When" column is misleading because the
  // task has already fired — defer to the Fired column for the truth.
  if (r.status === 'COMPLETED') return '—'
  if (!r.nextRunAt) return '—'
  const t = Date.parse(r.nextRunAt)
  if (!Number.isFinite(t)) return r.nextRunAt
  const deltaMs = t - Date.now()
  if (deltaMs < 0) return 'past due'
  const sec = Math.round(deltaMs / 1000)
  if (sec < 90) return `in ${sec}s`
  const min = Math.round(sec / 60)
  if (min < 90) return `in ${min}m`
  const hr = Math.round(min / 60)
  if (hr < 36) return `in ${hr}h`
  return new Date(t).toLocaleString(undefined, {
    weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}

function firedAtLabel(r: Task): string {
  const raw = asString(r.lastFiredAt)
  if (!raw) return '—'
  const t = Date.parse(raw)
  if (!Number.isFinite(t)) return raw
  // For just-fired reminders the absolute timestamp is the most useful info —
  // the operator wants to verify "did it actually run when I said?" — so emit
  // a fixed locale-aware date+time rather than a relative "5m ago" string.
  return new Date(t).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}

function deliveryLabel(r: Task): string {
  const d = reminderDelivery(r)
  // Null/blank delivery means "auto-route to the calling chat" for reminders —
  // distinct from tasks' "none" (JCLAW-420). Keep that behavior.
  if (!d) return 'web (auto)'
  // JCLAW-420: `tool:<name>` self-delivery — show the tool name, not the raw
  // `tool:` prefix, mirroring the Tasks page's deliveryLabel.
  if (d.startsWith('tool:')) return d.slice('tool:'.length)
  if (d.startsWith('web:')) return 'web'
  if (d.startsWith('telegram:')) return 'telegram'
  return d
}

// Status → text color, matching the /tasks table's statusColors so reminders
// read with the same colored-mono convention as the rest of the app (rather
// than the old rounded-pill badges).
const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-400',
  ACTIVE: 'text-emerald-400',
  RUNNING: 'text-blue-400',
  LOST: 'text-orange-400',
  COMPLETED: 'text-green-400',
  FAILED: 'text-red-400',
  CANCELLED: 'text-neutral-600',
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Reminders
    </h1>
    <p class="-mt-4 mb-6 text-sm text-zinc-600 dark:text-zinc-400">
      Personal nudges that fire on a schedule and surface as a toast in the corner of the app
      (or via Telegram if you've configured it). They never go through the LLM —
      the description is what you'll see.
    </p>

    <!-- Reminder KPI strip — reminders never run through the LLM, so per-run
         metrics (Runs today / Success rate) are meaningless here; we only track
         the lifecycle states that matter: Pending (one-shot waiting), Active
         (recurring ongoing), and Failed. Scoped to payloadType=reminder. -->
    <div
      v-if="reminderStats"
      class="grid grid-cols-3 gap-2 mb-6"
    >
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Pending
        </div>
        <div class="text-lg font-semibold text-fg-strong">
          {{ reminderStats.pendingCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Active
        </div>
        <div
          class="text-lg font-semibold"
          :class="reminderStats.activeCount > 0 ? 'text-emerald-400' : 'text-fg-strong'"
        >
          {{ reminderStats.activeCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Failed
        </div>
        <div
          class="text-lg font-semibold"
          :class="reminderStats.failedCount > 0 ? 'text-red-400' : 'text-fg-strong'"
        >
          {{ reminderStats.failedCount }}
        </div>
      </div>
    </div>

    <!-- Empty state -->
    <section
      v-if="!reminders || reminders.length === 0"
      class="border border-dashed border-border bg-surface-elevated px-6 py-12 text-center"
    >
      <BellAlertIcon class="mx-auto h-10 w-10 text-fg-muted" />
      <h2 class="mt-3 text-sm font-medium text-fg-strong">
        No reminders yet
      </h2>
      <p class="mt-1 text-sm text-fg-muted">
        Open
        <NuxtLink
          to="/chat"
          class="font-medium text-emerald-500 underline-offset-2 hover:underline"
        >
          a chat
        </NuxtLink>
        and say something like <em>"remind me to pay salaries tomorrow at 9am"</em>
        or <em>"remind me in 5 minutes to take the laundry out"</em>.
      </p>
    </section>

    <!-- Reminders table — same design-token styling as the /tasks table
         (bg-surface-elevated / border-border / text-fg-* / colored-mono status)
         so the rows read consistently with the rest of the app. -->
    <section
      v-else
      class="bg-surface-elevated border border-border"
    >
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="px-4 py-2.5 font-medium">
              Reminder
            </th>
            <th class="px-4 py-2.5 font-medium">
              When
            </th>
            <th class="px-4 py-2.5 font-medium">
              Schedule
            </th>
            <th class="px-4 py-2.5 font-medium">
              Channel
            </th>
            <th class="px-4 py-2.5 font-medium">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium">
              Fired
            </th>
            <th class="px-4 py-2.5 font-medium text-right">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="r in reminders"
            :key="r.id"
            class="hover:bg-muted/30 transition-colors"
          >
            <td class="max-w-md px-4 py-2.5">
              <div class="text-fg-primary">
                {{ reminderBody(r) }}
              </div>
              <div
                v-if="asString(r.description) && r.name && asString(r.description) !== r.name"
                class="mt-0.5 text-xs text-fg-muted"
              >
                {{ r.name }}
              </div>
            </td>
            <td class="px-4 py-2.5 whitespace-nowrap text-fg-muted text-xs">
              {{ nextFireLabel(r) }}
            </td>
            <td class="px-4 py-2.5 whitespace-nowrap font-mono text-xs text-fg-muted">
              {{ reminderScheduleDisplay(r) || r.type }}
            </td>
            <td class="px-4 py-2.5 whitespace-nowrap font-mono text-xs text-fg-muted">
              {{ deliveryLabel(r) }}
            </td>
            <td class="px-4 py-2.5 whitespace-nowrap">
              <span
                :class="statusColors[r.status]"
                class="text-xs font-mono"
              >{{ r.status }}</span>
            </td>
            <td class="px-4 py-2.5 whitespace-nowrap text-fg-muted text-xs">
              {{ firedAtLabel(r) }}
            </td>
            <td class="px-4 py-2.5 whitespace-nowrap text-right">
              <button
                type="button"
                class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                title="Delete reminder"
                :aria-label="`Delete ${reminderBody(r)}`"
                @click="deleteOne(r)"
              >
                <TrashIcon
                  class="w-4 h-4"
                  aria-hidden="true"
                />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
