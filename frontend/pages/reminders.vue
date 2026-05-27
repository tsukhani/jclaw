<script setup lang="ts">
import type { Task } from '~/types/api'
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
  if (!d) return 'web (auto)'
  if (d.startsWith('web:')) return 'web'
  if (d.startsWith('telegram:')) return 'telegram'
  return d
}

function statusVariant(status: string): string {
  switch (status) {
    case 'ACTIVE':
    case 'PENDING':
      return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300'
    case 'COMPLETED':
      return 'bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300'
    case 'CANCELLED':
      return 'bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-500'
    case 'FAILED':
    case 'LOST':
      return 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-300'
    default:
      return 'bg-zinc-100 text-zinc-700 dark:bg-zinc-800 dark:text-zinc-300'
  }
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

    <!-- Empty state -->
    <section
      v-if="!reminders || reminders.length === 0"
      class="rounded-lg border border-dashed border-zinc-300 bg-zinc-50 px-6 py-12 text-center dark:border-zinc-700 dark:bg-zinc-900/30"
    >
      <BellAlertIcon class="mx-auto h-10 w-10 text-zinc-400" />
      <h2 class="mt-3 text-sm font-medium text-zinc-700 dark:text-zinc-300">
        No reminders yet
      </h2>
      <p class="mt-1 text-sm text-zinc-500 dark:text-zinc-500">
        Open
        <NuxtLink
          to="/chat"
          class="font-medium text-emerald-600 underline-offset-2 hover:underline dark:text-emerald-400"
        >
          a chat
        </NuxtLink>
        and say something like <em>"remind me to pay salaries tomorrow at 9am"</em>
        or <em>"remind me in 5 minutes to take the laundry out"</em>.
      </p>
    </section>

    <!-- Reminders table -->
    <section
      v-else
      class="overflow-hidden rounded-lg border border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900"
    >
      <table class="w-full text-sm">
        <thead class="bg-zinc-50 text-xs uppercase tracking-wide text-zinc-500 dark:bg-zinc-800/60 dark:text-zinc-400">
          <tr>
            <th class="px-4 py-2 text-left font-medium">
              Reminder
            </th>
            <th class="px-4 py-2 text-left font-medium">
              When
            </th>
            <th class="px-4 py-2 text-left font-medium">
              Schedule
            </th>
            <th class="px-4 py-2 text-left font-medium">
              Channel
            </th>
            <th class="px-4 py-2 text-left font-medium">
              Status
            </th>
            <th class="px-4 py-2 text-left font-medium">
              Fired
            </th>
            <th class="px-4 py-2 text-right font-medium">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-zinc-100 dark:divide-zinc-800">
          <tr
            v-for="r in reminders"
            :key="r.id"
            class="hover:bg-zinc-50 dark:hover:bg-zinc-800/40"
          >
            <td class="max-w-md px-4 py-3">
              <div class="font-medium text-zinc-900 dark:text-zinc-100">
                {{ reminderBody(r) }}
              </div>
              <div
                v-if="asString(r.description) && r.name && asString(r.description) !== r.name"
                class="mt-0.5 text-xs text-zinc-500 dark:text-zinc-500"
              >
                {{ r.name }}
              </div>
            </td>
            <td class="px-4 py-3 whitespace-nowrap text-zinc-600 dark:text-zinc-400">
              {{ nextFireLabel(r) }}
            </td>
            <td class="px-4 py-3 whitespace-nowrap font-mono text-xs text-zinc-600 dark:text-zinc-400">
              {{ reminderScheduleDisplay(r) || r.type }}
            </td>
            <td class="px-4 py-3 whitespace-nowrap text-zinc-600 dark:text-zinc-400">
              {{ deliveryLabel(r) }}
            </td>
            <td class="px-4 py-3 whitespace-nowrap">
              <span
                class="rounded-full px-2 py-0.5 text-xs font-medium"
                :class="statusVariant(r.status)"
              >
                {{ r.status }}
              </span>
            </td>
            <td class="px-4 py-3 whitespace-nowrap text-zinc-600 dark:text-zinc-400">
              {{ firedAtLabel(r) }}
            </td>
            <td class="px-4 py-3 whitespace-nowrap text-right">
              <button
                class="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-rose-600 hover:bg-rose-50 dark:text-rose-400 dark:hover:bg-rose-900/30"
                title="Delete"
                @click="deleteOne(r)"
              >
                <TrashIcon class="h-3.5 w-3.5" />
                Delete
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
