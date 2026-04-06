<script setup lang="ts">
const statusFilter = ref('')
const typeFilter = ref('')

const url = computed(() => {
  const params = new URLSearchParams()
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (typeFilter.value) params.set('type', typeFilter.value)
  params.set('limit', '50')
  return `/api/tasks?${params}`
})

const { data: tasks, refresh } = await useFetch<any[]>(url)

async function cancelTask(id: number) {
  await $fetch(`/api/tasks/${id}/cancel`, { method: 'POST' })
  refresh()
}

async function retryTask(id: number) {
  await $fetch(`/api/tasks/${id}/retry`, { method: 'POST' })
  refresh()
}

const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-400',
  RUNNING: 'text-blue-400',
  COMPLETED: 'text-green-400',
  FAILED: 'text-red-400',
  CANCELLED: 'text-neutral-600',
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Tasks</h1>

    <!-- Filters -->
    <div class="flex gap-3 mb-4">
      <select v-model="statusFilter" class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1 focus:outline-none">
        <option value="">All statuses</option>
        <option v-for="s in ['PENDING','RUNNING','COMPLETED','FAILED','CANCELLED']" :key="s" :value="s">{{ s }}</option>
      </select>
      <select v-model="typeFilter" class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1 focus:outline-none">
        <option value="">All types</option>
        <option v-for="t in ['IMMEDIATE','SCHEDULED','CRON']" :key="t" :value="t">{{ t }}</option>
      </select>
    </div>

    <div class="bg-neutral-900 border border-neutral-800">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-neutral-800 text-left text-xs text-neutral-500">
            <th class="px-4 py-2.5 font-medium">Name</th>
            <th class="px-4 py-2.5 font-medium">Type</th>
            <th class="px-4 py-2.5 font-medium">Status</th>
            <th class="px-4 py-2.5 font-medium">Agent</th>
            <th class="px-4 py-2.5 font-medium">Next Run</th>
            <th class="px-4 py-2.5 font-medium">Retries</th>
            <th class="px-4 py-2.5 font-medium">Actions</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-neutral-800/50">
          <tr v-for="task in tasks" :key="task.id">
            <td class="px-4 py-2.5 text-neutral-300">{{ task.name }}</td>
            <td class="px-4 py-2.5 text-neutral-500 font-mono text-xs">{{ task.type }}</td>
            <td class="px-4 py-2.5">
              <span :class="statusColors[task.status]" class="text-xs font-mono">{{ task.status }}</span>
            </td>
            <td class="px-4 py-2.5 text-neutral-400">{{ task.agentName || '—' }}</td>
            <td class="px-4 py-2.5 text-neutral-500 text-xs">{{ task.nextRunAt ? new Date(task.nextRunAt).toLocaleString() : '—' }}</td>
            <td class="px-4 py-2.5 text-neutral-500 text-xs">{{ task.retryCount }}/{{ task.maxRetries }}</td>
            <td class="px-4 py-2.5 space-x-2">
              <button v-if="task.status === 'PENDING'" @click="cancelTask(task.id)"
                      class="text-xs text-neutral-500 hover:text-red-400 transition-colors">Cancel</button>
              <button v-if="task.status === 'FAILED'" @click="retryTask(task.id)"
                      class="text-xs text-neutral-500 hover:text-white transition-colors">Retry</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="!tasks?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No tasks found
      </div>
    </div>
  </div>
</template>
