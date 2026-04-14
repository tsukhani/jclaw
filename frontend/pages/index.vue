<script setup lang="ts">
import type { Agent, LatencyMetrics, LogEvent } from '~/types/api'

interface ChannelStatus {
  channelType: string
  enabled: boolean
}

const [{ data: agents }, { data: channels }, { data: tasks }, { data: logs }] = await Promise.all([
  useFetch<Agent[]>('/api/agents'),
  useFetch<ChannelStatus[]>('/api/channels'),
  useFetch<unknown[]>('/api/tasks?status=PENDING&limit=5'),
  useFetch<{ events: LogEvent[] }>('/api/logs?limit=10'),
])

const agentCount = computed(() => agents.value?.length ?? 0)
const enabledAgents = computed(() => agents.value?.filter(a => a.enabled).length ?? 0)
const channelCount = computed(() => channels.value?.filter(c => c.enabled).length ?? 0)
const pendingTasks = computed(() => tasks.value?.length ?? 0)

// --- Latency metrics (chat performance panel) ---
// Ordered so the display reads request-lifetime top-to-bottom; entries absent
// from the backend snapshot (e.g. queue_wait when the fork isn't rebuilt, or
// tool_exec when no tool-using agents ran) are simply skipped.
const SEGMENT_ORDER = [
  'queue_wait',
  'prologue',
  'ttft',
  'stream_body',
  'tool_exec',
  'persist',
  'total',
] as const

const SEGMENT_LABELS: Record<string, string> = {
  queue_wait: 'Queue wait',
  prologue: 'Prologue',
  ttft: 'Time to first token',
  stream_body: 'Stream body',
  tool_exec: 'Tool execution',
  persist: 'Persist',
  total: 'Total',
  tool_round_count: 'Tool rounds / turn',
}

const { data: latency, refresh: refreshLatency } = useFetch<LatencyMetrics>(
  '/api/metrics/latency',
  { default: () => ({}) },
)

const latencyRows = computed(() => {
  const m = latency.value ?? {}
  const seen = new Set<string>()
  const rows: Array<{ key: string; label: string; h: NonNullable<LatencyMetrics[string]> }> = []
  for (const key of SEGMENT_ORDER) {
    const h = m[key]
    if (h && h.count > 0) {
      rows.push({ key, label: SEGMENT_LABELS[key] ?? key, h })
      seen.add(key)
    }
  }
  // Surface anything the backend returns that isn't in the canonical list
  // (e.g. tool_round_count, or future segments) so we never silently hide data.
  for (const [key, h] of Object.entries(m)) {
    if (!seen.has(key) && h && h.count > 0) {
      rows.push({ key, label: SEGMENT_LABELS[key] ?? key, h })
    }
  }
  return rows
})

const hasLatencyData = computed(() => latencyRows.value.length > 0)

function formatMs(ms: number): string {
  if (ms < 1) return '<1 ms'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`
}

async function resetLatency() {
  await $fetch('/api/metrics/latency', { method: 'DELETE' })
  await refreshLatency()
}

// Poll every 5s so the dashboard reflects traffic without the user reloading.
let pollTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  pollTimer = setInterval(() => { refreshLatency() }, 5000)
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
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

    <!-- Chat Performance -->
    <div class="bg-neutral-900 border border-neutral-800 mb-8">
      <div class="px-4 py-3 border-b border-neutral-800 flex items-center justify-between">
        <h2 class="text-sm font-medium text-neutral-300">Chat Performance</h2>
        <div class="flex items-center gap-3 text-xs">
          <span class="text-neutral-600">In-memory • resets on JVM restart</span>
          <button
            type="button"
            class="text-neutral-400 hover:text-white transition-colors"
            @click="resetLatency"
          >Reset</button>
        </div>
      </div>

      <div v-if="!hasLatencyData" class="px-4 py-8 text-center text-sm text-neutral-600">
        No samples yet. Send a chat message to populate latency histograms.
      </div>

      <div v-else class="overflow-x-auto">
        <table class="w-full text-xs">
          <thead>
            <tr class="text-neutral-500 border-b border-neutral-800/50">
              <th class="text-left font-normal px-4 py-2">Segment</th>
              <th class="text-right font-normal px-3 py-2">n</th>
              <th class="text-right font-normal px-3 py-2">p50</th>
              <th class="text-right font-normal px-3 py-2">p90</th>
              <th class="text-right font-normal px-3 py-2">p99</th>
              <th class="text-right font-normal px-3 py-2">p999</th>
              <th class="text-right font-normal px-3 py-2">min</th>
              <th class="text-right font-normal px-3 py-2">max</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in latencyRows"
              :key="row.key"
              class="border-b border-neutral-800/30 last:border-b-0"
            >
              <td class="text-neutral-300 px-4 py-2">{{ row.label }}</td>
              <td class="text-right font-mono text-neutral-400 px-3 py-2">{{ row.h.count }}</td>
              <td class="text-right font-mono text-neutral-300 px-3 py-2">{{ formatMs(row.h.p50_ms) }}</td>
              <td class="text-right font-mono text-neutral-300 px-3 py-2">{{ formatMs(row.h.p90_ms) }}</td>
              <td class="text-right font-mono text-neutral-300 px-3 py-2">{{ formatMs(row.h.p99_ms) }}</td>
              <td class="text-right font-mono text-neutral-300 px-3 py-2">{{ formatMs(row.h.p999_ms) }}</td>
              <td class="text-right font-mono text-neutral-500 px-3 py-2">{{ formatMs(row.h.min_ms) }}</td>
              <td class="text-right font-mono text-neutral-500 px-3 py-2">{{ formatMs(row.h.max_ms) }}</td>
            </tr>
          </tbody>
        </table>
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
