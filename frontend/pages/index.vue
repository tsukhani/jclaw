<script setup lang="ts">
const { data: agents } = await useFetch('/api/agents')
const { data: channels } = await useFetch('/api/channels')
const { data: tasks } = await useFetch('/api/tasks?status=PENDING&limit=5')
const { data: logs } = await useFetch<{ events: any[] }>('/api/logs?limit=10')

const agentCount = computed(() => (agents.value as any[])?.length ?? 0)
const enabledAgents = computed(() => (agents.value as any[])?.filter((a: any) => a.enabled).length ?? 0)
const channelCount = computed(() => (channels.value as any[])?.filter((c: any) => c.enabled).length ?? 0)
const pendingTasks = computed(() => (tasks.value as any[])?.length ?? 0)
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Dashboard</h1>

    <!-- Stats -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <div class="text-2xl font-semibold text-white">{{ enabledAgents }}/{{ agentCount }}</div>
        <div class="text-xs text-neutral-500 mt-1">Agents enabled</div>
      </div>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <div class="text-2xl font-semibold text-white">{{ channelCount }}</div>
        <div class="text-xs text-neutral-500 mt-1">Channels active</div>
      </div>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <div class="text-2xl font-semibold text-white">{{ pendingTasks }}</div>
        <div class="text-xs text-neutral-500 mt-1">Tasks pending</div>
      </div>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <div class="text-2xl font-semibold text-white">{{ logs?.events?.length ?? 0 }}</div>
        <div class="text-xs text-neutral-500 mt-1">Recent events</div>
      </div>
    </div>

    <!-- Recent Events -->
    <div class="bg-neutral-900 border border-neutral-800">
      <div class="px-4 py-3 border-b border-neutral-800">
        <h2 class="text-sm font-medium text-neutral-300">Recent Activity</h2>
      </div>
      <div v-if="logs?.events?.length" class="divide-y divide-neutral-800/50">
        <div v-for="event in logs.events" :key="event.id" class="px-4 py-2.5 flex items-start gap-3">
          <span
            :class="{
              'text-red-400': event.level === 'ERROR',
              'text-yellow-400': event.level === 'WARN',
              'text-neutral-500': event.level === 'INFO'
            }"
            class="text-xs font-mono mt-0.5 shrink-0 w-10"
          >{{ event.level }}</span>
          <span class="text-xs text-neutral-500 shrink-0 w-16 font-mono">{{ event.category }}</span>
          <span v-if="event.agentId" class="text-xs text-neutral-600 shrink-0 font-mono">{{ event.agentId }}</span>
          <span class="text-sm text-neutral-300 min-w-0 truncate">{{ event.message }}</span>
          <span class="text-xs text-neutral-600 ml-auto shrink-0">{{ new Date(event.timestamp).toLocaleTimeString() }}</span>
        </div>
      </div>
      <div v-else class="px-4 py-8 text-center text-sm text-neutral-600">
        No recent events
      </div>
    </div>
  </div>
</template>
