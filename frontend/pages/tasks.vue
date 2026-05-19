<script setup lang="ts">
import type { Task } from '~/types/api'
import { TrashIcon } from '@heroicons/vue/24/outline'

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
  RUNNING: 'text-blue-400',
  LOST: 'text-orange-400',
  COMPLETED: 'text-green-400',
  FAILED: 'text-red-400',
  CANCELLED: 'text-neutral-600',
}

// A11y: stable ids for filter selects
const statusSelectId = useId()
const typeSelectId = useId()

/**
 * Bulk-select state mirroring the agents page: row clicks toggle
 * selection while selectMode is on, and a single Delete button
 * sweeps every selected row through the per-row DELETE endpoint.
 */
const selectMode = ref(false)
const selectedIds = ref<Set<number>>(new Set())
const deletingBulk = ref(false)

function enterSelectMode() {
  selectMode.value = true
  selectedIds.value = new Set()
}

function exitSelectMode() {
  selectMode.value = false
  selectedIds.value = new Set()
}

function toggleSelection(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  if (!tasks.value) return
  if (selectedIds.value.size === tasks.value.length) {
    selectedIds.value = new Set()
  }
  else {
    selectedIds.value = new Set(tasks.value.map(t => t.id))
  }
}

async function deleteSelected() {
  if (!selectedIds.value.size) return
  const count = selectedIds.value.size
  const ok = await confirm({
    title: 'Delete tasks',
    message: `Permanently delete ${count} task${count === 1 ? '' : 's'} and their run history? This cannot be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  deletingBulk.value = true
  try {
    // Sequential deletes — the selection is small (user-curated) and
    // each request goes through the same FK-cascade path, so parallel
    // fires would just contend on H2 row locks for the message rows.
    for (const id of selectedIds.value) {
      await $fetch(`/api/tasks/${id}`, { method: 'DELETE' })
    }
    exitSelectMode()
    refresh()
  }
  catch (e) {
    console.error('Failed to delete selected tasks:', e)
  }
  finally {
    deletingBulk.value = false
  }
}
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
                :checked="!!tasks?.length && selectedIds.size === tasks.length"
                :indeterminate.prop="selectedIds.size > 0 && selectedIds.size < (tasks?.length ?? 0)"
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
            <th class="px-4 py-2.5 font-medium">
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
              {{ task.nextRunAt ? new Date(task.nextRunAt).toLocaleString() : '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">
              {{ task.retryCount }}/{{ task.maxRetries }}
            </td>
            <td class="px-4 py-2.5 space-x-2">
              <button
                v-if="!selectMode && task.status === 'PENDING'"
                class="text-xs text-fg-muted hover:text-red-400 transition-colors"
                @click.stop="cancelTask(task.id)"
              >
                Cancel
              </button>
              <button
                v-if="!selectMode && (task.status === 'FAILED' || task.status === 'LOST')"
                class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
                @click.stop="retryTask(task.id)"
              >
                Retry
              </button>
              <button
                v-if="!selectMode"
                class="text-xs text-fg-muted hover:text-red-400 transition-colors"
                :title="`Permanently delete “${task.name}” and its run history`"
                @click.stop="deleteTask(task)"
              >
                Delete
              </button>
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
