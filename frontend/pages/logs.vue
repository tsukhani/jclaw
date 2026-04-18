<script setup lang="ts">
import type { LogEvent } from '~/types/api'

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

const { data: logs, refresh } = await useFetch<{ events: LogEvent[] }>(url)

// Auto-refresh every 5 seconds
const autoRefresh = ref(true)
let interval: ReturnType<typeof setInterval> | undefined

onMounted(() => {
  interval = setInterval(() => {
    if (autoRefresh.value && !document.hidden) refresh()
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
  ERROR: 'bg-red-100 dark:bg-red-400/10 text-red-700 dark:text-red-400 border-red-300 dark:border-red-400/20',
  WARN: 'bg-yellow-100 dark:bg-yellow-400/10 text-yellow-700 dark:text-yellow-400 border-yellow-300 dark:border-yellow-400/20',
  INFO: 'bg-muted text-fg-muted border-input',
}

const categories = ['llm', 'channel', 'tool', 'task', 'agent', 'auth', 'system']

// A11y: stable ids for label/control association
const autoRefreshId = useId()
const categorySelectId = useId()
const levelSelectId = useId()
const searchInputId = useId()
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Logs
      </h1>
      <label
        :for="autoRefreshId"
        class="flex items-center gap-1.5 text-xs text-fg-muted"
      >
        <input
          :id="autoRefreshId"
          v-model="autoRefresh"
          type="checkbox"
          class="accent-white"
        >
        Auto-refresh
      </label>
    </div>

    <!-- Filters -->
    <div class="flex gap-3 mb-4">
      <label :for="categorySelectId">
        <span class="sr-only">Category filter</span>
        <select
          :id="categorySelectId"
          v-model="categoryFilter"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
          <option value="">
            All categories
          </option>
          <option
            v-for="c in categories"
            :key="c"
            :value="c"
          >
            {{ c }}
          </option>
        </select>
      </label>
      <label :for="levelSelectId">
        <span class="sr-only">Level filter</span>
        <select
          :id="levelSelectId"
          v-model="levelFilter"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
          <option value="">
            All levels
          </option>
          <option value="ERROR">
            ERROR
          </option>
          <option value="WARN">
            WARN
          </option>
          <option value="INFO">
            INFO
          </option>
        </select>
      </label>
      <label
        :for="searchInputId"
        class="flex-1 max-w-xs"
      >
        <span class="sr-only">Search log messages</span>
        <input
          :id="searchInputId"
          v-model="searchFilter"
          placeholder="Search messages..."
          class="w-full px-2 py-1 bg-muted border border-input text-sm text-fg-strong
                      placeholder-fg-muted focus:outline-hidden focus:border-ring transition-colors"
        >
      </label>
    </div>

    <!-- Events -->
    <div class="bg-surface-elevated border border-border">
      <div
        v-if="logs?.events?.length"
        class="divide-y divide-border"
      >
        <template
          v-for="event in logs.events"
          :key="event.id"
        >
          <button
            v-if="event.details"
            type="button"
            class="w-full block text-left px-4 py-2 hover:bg-muted transition-colors cursor-pointer bg-transparent border-0"
            @click="toggleExpand(event.id)"
          >
            <div class="flex items-start gap-3">
              <span class="text-xs text-fg-muted shrink-0 w-20 font-mono mt-0.5">
                {{ new Date(event.timestamp).toLocaleTimeString() }}
              </span>
              <span
                :class="levelColors[event.level]"
                class="text-[10px] font-mono px-1.5 py-0.5 border shrink-0"
              >{{ event.level }}</span>
              <span class="text-xs text-fg-muted shrink-0 w-14 font-mono mt-0.5">{{ event.category }}</span>
              <span class="text-sm text-fg-primary min-w-0">{{ event.message }}</span>
            </div>
            <div
              v-if="expandedId === event.id"
              class="mt-2 ml-[8.5rem] text-xs font-mono text-fg-muted bg-muted p-2 whitespace-pre-wrap"
            >
              {{ event.details }}
            </div>
          </button>
          <div
            v-else
            class="px-4 py-2 hover:bg-muted transition-colors"
          >
            <div class="flex items-start gap-3">
              <span class="text-xs text-fg-muted shrink-0 w-20 font-mono mt-0.5">
                {{ new Date(event.timestamp).toLocaleTimeString() }}
              </span>
              <span
                :class="levelColors[event.level]"
                class="text-[10px] font-mono px-1.5 py-0.5 border shrink-0"
              >{{ event.level }}</span>
              <span class="text-xs text-fg-muted shrink-0 w-14 font-mono mt-0.5">{{ event.category }}</span>
              <span class="text-sm text-fg-primary min-w-0">{{ event.message }}</span>
            </div>
          </div>
        </template>
      </div>
      <div
        v-else
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No events matching filters
      </div>
    </div>
  </div>
</template>
