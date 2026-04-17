<script setup lang="ts">
import type { Agent, LatencyMetrics, LogEvent } from '~/types/api'

interface ChannelStatus {
  channelType: string
  enabled: boolean
}

const [
  { data: agents },
  { data: channels },
  { data: tasks },
  { data: logs, refresh: refreshLogs },
] = await Promise.all([
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
// Row assembly (top-level order, prologue_* child nesting, chart-vs-table
// split) lives in ~/utils/latency-rows for unit-testability without
// mounting the dashboard.
import { buildLatencyRows, buildChartSeries } from '~/utils/latency-rows'

const { data: latency, refresh: refreshLatency } = useFetch<LatencyMetrics>(
  '/api/metrics/latency',
  { default: () => ({}) },
)

const latencyRows = computed(() => buildLatencyRows((latency.value ?? {}) as any))
const latencyChartSeries = computed(() => buildChartSeries((latency.value ?? {}) as any))

const hasLatencyData = computed(() => latencyRows.value.length > 0)

type LatencyView = 'table' | 'chart'
const latencyView = ref<LatencyView>('table')

function formatMs(ms: number): string {
  if (ms < 1) return '<1 ms'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`
}

async function resetLatency() {
  await $fetch('/api/metrics/latency', { method: 'DELETE' })
  await refreshLatency()
}

// --- Recent Activity (manual refresh) ---
// Auto-refresh is intentionally off here: event rows reorder on every new
// entry, and an agent turn can emit 5-10 events in a couple of seconds —
// the jitter makes the list hard to read. The Logs page is the live-stream
// view; this widget is a snapshot with an explicit refresh action.
const logsLastUpdated = ref<Date | null>(null)
const now = ref(new Date())

function formatAge(then: Date | null, nowRef: Date): string {
  if (!then) return ''
  const s = Math.max(0, Math.floor((nowRef.getTime() - then.getTime()) / 1000))
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  return `${Math.floor(m / 60)}h ago`
}

const logsAgeLabel = computed(() => formatAge(logsLastUpdated.value, now.value))

async function refreshRecentActivity() {
  await refreshLogs()
  logsLastUpdated.value = new Date()
}

// Latency panel continues to poll (numbers don't visually shift rows).
// The same tick updates `now` so the "X ago" caption on Recent Activity
// stays current without a second interval.
let pollTimer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  logsLastUpdated.value = new Date()
  pollTimer = setInterval(() => {
    refreshLatency()
    now.value = new Date()
  }, 5000)
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">Dashboard</h1>

    <!-- Stats -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">{{ enabledAgents }}/{{ agentCount }}</div>
        <div class="text-xs text-fg-muted mt-1">Agents enabled</div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">{{ channelCount }}</div>
        <div class="text-xs text-fg-muted mt-1">Channels active</div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">{{ pendingTasks }}</div>
        <div class="text-xs text-fg-muted mt-1">Tasks pending</div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">{{ logs?.events?.length ?? 0 }}</div>
        <div class="text-xs text-fg-muted mt-1">Recent events</div>
      </div>
    </div>

    <!-- Chat Performance -->
    <div class="bg-surface-elevated border border-border mb-8">
      <div class="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
        <div class="flex items-center gap-3 min-w-0">
          <h2 class="text-sm font-medium text-fg-primary shrink-0">Chat Performance</h2>
          <div
            v-if="hasLatencyData"
            class="inline-flex items-center border border-border overflow-hidden"
            role="tablist"
            aria-label="Chat performance view"
          >
            <button
              type="button"
              role="tab"
              :aria-selected="latencyView === 'table'"
              class="p-1 transition-colors"
              :class="latencyView === 'table'
                ? 'bg-muted text-fg-strong'
                : 'text-fg-muted hover:text-fg-strong'"
              @click="latencyView = 'table'"
              title="Table view"
            >
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 10h18M3 14h18M3 6h18M3 18h18" /></svg>
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="latencyView === 'chart'"
              class="p-1 transition-colors border-l border-border"
              :class="latencyView === 'chart'
                ? 'bg-muted text-fg-strong'
                : 'text-fg-muted hover:text-fg-strong'"
              @click="latencyView = 'chart'"
              title="Distribution chart"
            >
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 3v18h18M7 15l3-4 4 3 5-7" /></svg>
            </button>
          </div>
        </div>
        <div class="flex items-center gap-3 text-xs shrink-0">
          <span class="text-fg-muted hidden sm:inline">In-memory • resets on JVM restart</span>
          <button
            type="button"
            class="text-fg-muted hover:text-fg-strong transition-colors p-1"
            @click="resetLatency"
            title="Reset latency histograms"
          >
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
          </button>
        </div>
      </div>

      <div v-if="!hasLatencyData" class="px-4 py-8 text-center text-sm text-fg-muted">
        No samples yet. Send a chat message to populate latency histograms.
      </div>

      <div v-else-if="latencyView === 'table'" class="overflow-x-auto">
        <table class="w-full text-xs">
          <thead>
            <tr class="text-fg-muted border-b border-border">
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
              :class="row.key === 'total' ? 'border-t-2 border-fg-muted/30 bg-muted/30 font-semibold' : 'border-b border-border last:border-b-0'"
            >
              <!--
                Child rows render with indent + muted label + smaller font —
                the Grafana / Datadog / Linear data-table pattern. Indent
                alone plus color contrast is enough to signal "these belong
                to the parent above" without glyphs or border-rules that
                fight against table cell geometry. Four rows read cleanly
                as a group because their left edges all line up at a
                consistent x-position visibly further in than top-level rows.
              -->
              <td class="py-2 px-4"
                  :class="row.isChild
                    ? 'text-fg-muted text-[0.95em] pl-10'
                    : 'text-fg-primary'">
                {{ row.label }}
              </td>
              <td class="text-right font-mono text-fg-muted px-3 py-2">{{ row.h.count }}</td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">{{ formatMs(row.h.p50_ms) }}</td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">{{ formatMs(row.h.p90_ms) }}</td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">{{ formatMs(row.h.p99_ms) }}</td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">{{ formatMs(row.h.p999_ms) }}</td>
              <td class="text-right font-mono text-fg-muted px-3 py-2">{{ formatMs(row.h.min_ms) }}</td>
              <td class="text-right font-mono text-fg-muted px-3 py-2">{{ formatMs(row.h.max_ms) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-else class="p-4">
        <LatencyOverlayChart :series="latencyChartSeries" />
      </div>
    </div>

    <!-- Recent Events -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
        <h2 class="text-sm font-medium text-fg-primary">Recent Activity</h2>
        <div class="flex items-center gap-3 text-xs shrink-0">
          <span v-if="logsAgeLabel" class="text-fg-muted">{{ logsAgeLabel }}</span>
          <button
            type="button"
            class="text-fg-muted hover:text-fg-strong transition-colors p-1"
            @click="refreshRecentActivity"
            title="Refresh"
          >
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" /></svg>
          </button>
        </div>
      </div>
      <div v-if="logs?.events?.length" class="divide-y divide-border">
        <div v-for="event in logs.events" :key="event.id" class="px-4 py-2.5 flex items-start gap-3">
          <span
            :class="{
              'text-red-400': event.level === 'ERROR',
              'text-yellow-400': event.level === 'WARN',
              'text-fg-muted': event.level === 'INFO'
            }"
            class="text-xs font-mono mt-0.5 shrink-0 w-10"
          >{{ event.level }}</span>
          <span class="text-xs text-fg-muted shrink-0 w-16 font-mono">{{ event.category }}</span>
          <span v-if="event.agentId" class="text-xs text-fg-muted shrink-0 font-mono">{{ event.agentId }}</span>
          <span class="text-sm text-fg-primary min-w-0 truncate">{{ event.message }}</span>
          <span class="text-xs text-fg-muted ml-auto shrink-0">{{ new Date(event.timestamp).toLocaleTimeString() }}</span>
        </div>
      </div>
      <div v-else class="px-4 py-8 text-center text-sm text-fg-muted">
        No recent events
      </div>
    </div>
  </div>
</template>
