<script setup lang="ts">
const categoryFilter = ref('')
const levelFilter = ref('')
const searchFilter = ref('')

const url = computed(() => {
  const params = new URLSearchParams()
  if (categoryFilter.value) params.set('category', categoryFilter.value)
  if (levelFilter.value) params.set('level', levelFilter.value)
  if (searchFilter.value) params.set('search', searchFilter.value)
  params.set('limit', '100')
  return `/api/logs?${params}`
})

const { data: logs, refresh } = await useFetch<{ events: any[] }>(url)

// Auto-refresh every 5 seconds
const autoRefresh = ref(true)
let interval: ReturnType<typeof setInterval>

onMounted(() => {
  interval = setInterval(() => {
    if (autoRefresh.value) refresh()
  }, 5000)
})

onUnmounted(() => {
  clearInterval(interval)
})

const expandedId = ref<number | null>(null)

function toggleExpand(id: number) {
  expandedId.value = expandedId.value === id ? null : id
}

const levelColors: Record<string, string> = {
  ERROR: 'bg-red-400/10 text-red-400 border-red-400/20',
  WARN: 'bg-yellow-400/10 text-yellow-400 border-yellow-400/20',
  INFO: 'bg-neutral-800 text-neutral-400 border-neutral-700',
}

const categories = ['llm', 'channel', 'tool', 'task', 'agent', 'auth', 'system']
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-white">Logs</h1>
      <label class="flex items-center gap-1.5 text-xs text-neutral-500">
        <input type="checkbox" v-model="autoRefresh" class="accent-white" />
        Auto-refresh
      </label>
    </div>

    <!-- Filters -->
    <div class="flex gap-3 mb-4">
      <select v-model="categoryFilter" class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1 focus:outline-none">
        <option value="">All categories</option>
        <option v-for="c in categories" :key="c" :value="c">{{ c }}</option>
      </select>
      <select v-model="levelFilter" class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1 focus:outline-none">
        <option value="">All levels</option>
        <option value="ERROR">ERROR</option>
        <option value="WARN">WARN</option>
        <option value="INFO">INFO</option>
      </select>
      <input v-model="searchFilter" placeholder="Search messages..."
             class="flex-1 max-w-xs px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white
                    placeholder-neutral-600 focus:outline-none focus:border-neutral-600 transition-colors" />
    </div>

    <!-- Events -->
    <div class="bg-neutral-900 border border-neutral-800">
      <div v-if="logs?.events?.length" class="divide-y divide-neutral-800/50">
        <div
          v-for="event in logs.events"
          :key="event.id"
          @click="event.details ? toggleExpand(event.id) : null"
          :class="event.details ? 'cursor-pointer' : ''"
          class="px-4 py-2 hover:bg-neutral-800/30 transition-colors"
        >
          <div class="flex items-start gap-3">
            <span class="text-xs text-neutral-600 shrink-0 w-20 font-mono mt-0.5">
              {{ new Date(event.timestamp).toLocaleTimeString() }}
            </span>
            <span
              :class="levelColors[event.level]"
              class="text-[10px] font-mono px-1.5 py-0.5 border shrink-0"
            >{{ event.level }}</span>
            <span class="text-xs text-neutral-500 shrink-0 w-14 font-mono mt-0.5">{{ event.category }}</span>
            <span class="text-sm text-neutral-300 min-w-0">{{ event.message }}</span>
          </div>
          <div v-if="expandedId === event.id && event.details"
               class="mt-2 ml-[8.5rem] text-xs font-mono text-neutral-500 bg-neutral-800/50 p-2 whitespace-pre-wrap">
            {{ event.details }}
          </div>
        </div>
      </div>
      <div v-else class="px-4 py-8 text-center text-sm text-neutral-600">
        No events matching filters
      </div>
    </div>
  </div>
</template>
