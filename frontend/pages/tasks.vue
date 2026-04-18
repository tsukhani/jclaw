<script setup lang="ts">
import type { Task } from '~/types/api'

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

async function cancelTask(id: number) {
  await mutate(`/api/tasks/${id}/cancel`, { method: 'POST' })
  refresh()
}

async function retryTask(id: number) {
  await mutate(`/api/tasks/${id}/retry`, { method: 'POST' })
  refresh()
}

const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-400',
  RUNNING: 'text-blue-400',
  COMPLETED: 'text-green-400',
  FAILED: 'text-red-400',
  CANCELLED: 'text-neutral-600',
}

// A11y: stable ids for filter selects
const statusSelectId = useId()
const typeSelectId = useId()
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Tasks
    </h1>

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
            v-for="s in ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']"
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
            v-for="t in ['IMMEDIATE', 'SCHEDULED', 'CRON']"
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
          >
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
                v-if="task.status === 'PENDING'"
                class="text-xs text-fg-muted hover:text-red-400 transition-colors"
                @click="cancelTask(task.id)"
              >
                Cancel
              </button>
              <button
                v-if="task.status === 'FAILED'"
                class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
                @click="retryTask(task.id)"
              >
                Retry
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
