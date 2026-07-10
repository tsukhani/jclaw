<script setup lang="ts">
import type { LogEvent } from '~/types/api'
import { ChevronRightIcon } from '@heroicons/vue/24/outline'

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

const categories = ['llm', 'channel', 'tool', 'task', 'agent', 'auth', 'system']

// JCLAW-272: subagent lifecycle taxonomy. Emission lands later (JCLAW-265
// spawn, JCLAW-266 limit exceeded, JCLAW-270/273 completion); the filter
// exposes the categories now so they're recognized as soon as events flow.
const subagentCategories = [
  'SUBAGENT_SPAWN',
  'SUBAGENT_COMPLETE',
  'SUBAGENT_ERROR',
  'SUBAGENT_KILL',
  'SUBAGENT_LIMIT_EXCEEDED',
  'SUBAGENT_TIMEOUT',
]

// A11y: stable ids for label/control association
const autoRefreshId = useId()
const categorySelectId = useId()
const levelSelectId = useId()
const searchInputId = useId()

/**
 * Format the event timestamp as "MMM D, YYYY · h:mm:ss AM/PM" — same
 * format the Dashboard's Recent Activity panel uses so a row a user
 * just saw on the dashboard reads identically when they click through
 * to the full Logs page.
 */
function formatTimestamp(iso: string): string {
  const d = new Date(iso)
  const date = d.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
  const time = d.toLocaleTimeString(undefined, {
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
    hour12: true,
  })
  return `${date} · ${time}`
}
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
          <optgroup label="Subagents">
            <option
              v-for="c in subagentCategories"
              :key="c"
              :value="c"
            >
              {{ c }}
            </option>
          </optgroup>
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

    <!-- Events. Column layout mirrors the Dashboard's Recent Activity
         panel (LEVEL | CATEGORY | AGENT | MESSAGE | TIMESTAMP) so the
         two surfaces feel like the same table at different zoom levels.
         Each shrink-0 width matches the dashboard widths exactly. -->
    <div class="bg-surface-elevated border border-border">
      <template v-if="logs?.events?.length">
        <!-- Header row carries a 4-wide chevron slot at the start so the
             data rows below can show a rotating chevron on detail-
             bearing entries without breaking column alignment. Width
             stays fixed whether or not a given row has details — only
             the chevron's opacity differs. -->
        <div class="px-4 py-2 flex items-center gap-3 text-[10px] uppercase tracking-wider font-medium text-fg-muted border-b border-border bg-muted/30">
          <span
            class="shrink-0 w-4"
            aria-hidden="true"
          />
          <span class="shrink-0 w-10">Level</span>
          <span class="shrink-0 w-44">Category</span>
          <span class="shrink-0 w-16">Agent</span>
          <span class="flex-1 min-w-0">Message</span>
          <span class="ml-auto shrink-0 w-48 text-right">Timestamp</span>
        </div>
        <div class="divide-y divide-border">
          <template
            v-for="event in logs.events"
            :key="event.id"
          >
            <button
              v-if="event.details"
              type="button"
              :aria-expanded="expandedId === event.id"
              class="w-full block text-left px-4 py-2.5 hover:bg-muted transition-colors cursor-pointer bg-transparent border-0"
              @click="toggleExpand(event.id)"
            >
              <div class="flex items-start gap-3">
                <ChevronRightIcon
                  :class="expandedId === event.id ? 'rotate-90' : ''"
                  class="h-4 w-4 text-fg-muted shrink-0 mt-0.5 transition-transform"
                  aria-hidden="true"
                />
                <span
                  :class="{
                    'text-red-700 dark:text-red-400': event.level === 'ERROR',
                    'text-yellow-700 dark:text-yellow-400': event.level === 'WARN',
                    'text-fg-muted': event.level === 'INFO',
                  }"
                  class="text-xs font-mono mt-0.5 shrink-0 w-10"
                >{{ event.level }}</span>
                <span
                  :title="event.category"
                  class="text-xs text-fg-muted shrink-0 w-44 font-mono truncate mt-0.5"
                >{{ event.category }}</span>
                <span
                  :title="event.agentId ? String(event.agentId) : ''"
                  class="text-xs text-fg-muted shrink-0 w-16 font-mono truncate mt-0.5"
                >{{ event.agentId || '—' }}</span>
                <span class="text-sm text-fg-primary min-w-0 truncate">{{ event.message }}</span>
                <span class="text-xs text-fg-muted ml-auto shrink-0 w-48 text-right font-mono mt-0.5">{{ formatTimestamp(event.timestamp) }}</span>
              </div>
              <!-- Details payload offset matches the (chevron + level +
                   category + agent) column widths so the JSON aligns
                   under the message column when expanded. -->
              <div
                v-if="expandedId === event.id"
                class="mt-2 ml-[19.5rem] text-xs font-mono text-fg-muted bg-muted p-2 whitespace-pre-wrap"
              >
                {{ event.details }}
              </div>
            </button>
            <div
              v-else
              class="px-4 py-2.5 hover:bg-muted transition-colors"
            >
              <div class="flex items-start gap-3">
                <!-- Hollow circle in the chevron slot keeps column
                     alignment with detail-bearing rows AND signals
                     "this row has no details to expand" — distinguishing
                     a non-clickable row from a broken / missing
                     chevron column. Different glyph shape from the
                     chevron so the eye reads it as "inactive marker"
                     not "muted clickable affordance"; muted color
                     keeps it from competing with the chevrons. -->
                <span
                  class="h-4 w-4 shrink-0 mt-0.5 text-fg-muted text-center text-sm leading-4 select-none"
                  aria-hidden="true"
                >◦</span>
                <span
                  :class="{
                    'text-red-700 dark:text-red-400': event.level === 'ERROR',
                    'text-yellow-700 dark:text-yellow-400': event.level === 'WARN',
                    'text-fg-muted': event.level === 'INFO',
                  }"
                  class="text-xs font-mono mt-0.5 shrink-0 w-10"
                >{{ event.level }}</span>
                <span
                  :title="event.category"
                  class="text-xs text-fg-muted shrink-0 w-44 font-mono truncate mt-0.5"
                >{{ event.category }}</span>
                <span
                  :title="event.agentId ? String(event.agentId) : ''"
                  class="text-xs text-fg-muted shrink-0 w-16 font-mono truncate mt-0.5"
                >{{ event.agentId || '—' }}</span>
                <span class="text-sm text-fg-primary min-w-0 truncate">{{ event.message }}</span>
                <span class="text-xs text-fg-muted ml-auto shrink-0 w-48 text-right font-mono mt-0.5">{{ formatTimestamp(event.timestamp) }}</span>
              </div>
            </div>
          </template>
        </div>
      </template>
      <div
        v-else
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No events matching filters
      </div>
    </div>
  </div>
</template>
