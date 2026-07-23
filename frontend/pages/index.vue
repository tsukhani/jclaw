<script setup lang="ts">
import {
  ChartBarIcon,
  QueueListIcon,
  TableCellsIcon,
  TrashIcon,
  VideoCameraIcon,
} from '@heroicons/vue/24/outline'
import type { Agent, LatencyHistogram, LogEvent } from '~/types/api'

// --- Latency metrics (chat performance panel) ---
// Row assembly (top-level order, prologue_* child nesting, chart-vs-table
// split) lives in ~/utils/latency-rows for unit-testability without
// mounting the dashboard.
import { buildLatencyRows, buildChartSeries, UNKNOWN_CHANNEL } from '~/utils/latency-rows'

interface ActiveChannelsResponse {
  count: number
  channelTypes: string[]
}

/**
 * Fetch a total-count via /api/tasks?status=X&limit=1 reading the
 * X-Total-Count header. limit=1 keeps the payload tiny — we don't
 * need the rows, only the count — and the header is set by the
 * backend list endpoint regardless of pagination. Falls back to the
 * response body's array length if the header is missing (test stubs
 * via registerEndpoint don't simulate response headers).
 */
// `scope` narrows by payloadType. Default excludes reminders so the dashboard
// "Tasks" card counts the same set the /tasks page shows (background automation
// only); the Reminders card passes payloadType=reminder to count the other
// half. Without this, a recurring reminder inflated the Tasks "Active" count
// above what /tasks displayed.
async function fetchTaskCount(status: string, scope = 'excludePayloadType=reminder'): Promise<number> {
  const res = await $fetch.raw<unknown[]>(`/api/tasks?status=${status}&${scope}&limit=1`)
  const headerTotal = res.headers.get('x-total-count')
  return headerTotal ? Number.parseInt(headerTotal, 10) : (res._data?.length ?? 0)
}

const [
  { data: agents },
  { data: activeChannels },
  { data: activeTaskCount, refresh: refreshActiveTasks },
  { data: runningTaskCount, refresh: refreshRunningTasks },
  { data: pendingTaskCount, refresh: refreshPendingTasks },
  { data: activeReminderCount, refresh: refreshActiveReminders },
  { data: pendingReminderCount, refresh: refreshPendingReminders },
  { data: logs, refresh: refreshLogs },
  { data: conversationCount },
] = await Promise.all([
  useFetch<Agent[]>('/api/agents'),
  // /api/channels/active aggregates the transport-backed channels
  // (telegram bindings + slack/whatsapp ChannelConfig) into a single
  // count. The in-app "web" chat is deliberately excluded so this number
  // matches the channel cards on /channels. The plain /api/channels
  // endpoint reads only ChannelConfig and silently misses Telegram
  // bindings — that's why the previous dashboard showed "0 channels
  // active" while Telegram polled messages live. See
  // ChannelStatusService.activeChannelTypes for the per-channel logic.
  useFetch<ActiveChannelsResponse>('/api/channels/active',
    { default: () => ({ count: 0, channelTypes: [] }) }),
  // Three task-status buckets surfaced as separate sub-stats in the
  // 4th dashboard card. ACTIVE = recurring (CRON / INTERVAL) in
  // steady state; RUNNING = currently firing; PENDING = one-shot
  // (SCHEDULED / IMMEDIATE) waiting to fire. Each call asks for one
  // row of a single status and reads the true total off the
  // X-Total-Count header — mirroring the conversation-count pattern
  // below — so the displayed number is accurate regardless of how
  // many task rows exist.
  useAsyncData<number>('dashboard-active-task-count', () => fetchTaskCount('ACTIVE')),
  useAsyncData<number>('dashboard-running-task-count', () => fetchTaskCount('RUNNING')),
  useAsyncData<number>('dashboard-pending-task-count', () => fetchTaskCount('PENDING')),
  // Reminders are payloadType=reminder tasks — counted separately for their
  // own card (ACTIVE = recurring, PENDING = one-shot waiting to fire).
  useAsyncData<number>('dashboard-active-reminder-count', () => fetchTaskCount('ACTIVE', 'payloadType=reminder')),
  useAsyncData<number>('dashboard-pending-reminder-count', () => fetchTaskCount('PENDING', 'payloadType=reminder')),
  useFetch<{ events: LogEvent[] }>('/api/logs?limit=10'),
  // Total conversation count: ask the listing endpoint with a tiny limit
  // and read X-Total-Count from the response headers — same pattern the
  // conversations page uses (see pages/conversations/index.vue). Falls
  // back to the body's array length if the header is missing (test stubs
  // via registerEndpoint don't simulate response headers).
  useAsyncData<number>('dashboard-conversation-count', async () => {
    const res = await $fetch.raw<unknown[]>('/api/conversations?limit=1')
    const headerTotal = res.headers.get('x-total-count')
    return headerTotal ? Number.parseInt(headerTotal, 10) : (res._data?.length ?? 0)
  }),
])

// Workspace disk footprint — a runaway shell loop once grew a 30 GiB file in
// workspace/main/ unnoticed; this line makes such growth visible on the
// dashboard. bytes = -1 (walk failed) hides the line rather than lying.
const { data: workspaceStats } = await useFetch<{ bytes: number }>('/api/workspace/stats',
  { default: () => ({ bytes: -1 }) })
const workspaceBytes = computed(() => workspaceStats.value?.bytes ?? -1)
/** Number and adaptive unit split apart: the card shows the value big and names the unit in the label. */
const workspaceSize = computed(() => splitSize(Math.max(0, workspaceBytes.value)))
/** Amber past 10 GiB — big enough to never flag normal use, small enough to catch a runaway early. */
const WORKSPACE_WARN_BYTES = 10 * 1024 * 1024 * 1024

const agentCount = computed(() => agents.value?.length ?? 0)
const enabledAgents = computed(() => agents.value?.filter(a => a.enabled).length ?? 0)
const channelCount = computed(() => activeChannels.value?.count ?? 0)
const activeTasks = computed(() => activeTaskCount.value ?? 0)
const runningTasks = computed(() => runningTaskCount.value ?? 0)
const pendingTasks = computed(() => pendingTaskCount.value ?? 0)
const activeReminders = computed(() => activeReminderCount.value ?? 0)
const pendingReminders = computed(() => pendingReminderCount.value ?? 0)
const totalConversations = computed(() => conversationCount.value ?? 0)

// Chat Performance latency is time-windowed + filterable (JCLAW-515): the panel reads
// server-aggregated percentiles from GET /api/metrics/latency/rows, driven by a 7d/30d/All
// window plus agent and channel filters. The endpoint returns the same {segment: histogram}
// shape the live snapshot did, so the table/chart renderers below are unchanged — only the
// data source and the header controls changed.
interface LatencyRowsResponse {
  since: string
  channels: string[]
  segments: Record<string, LatencyHistogram>
}
type LatencyWindow = '7d' | '30d' | 'all'
const LAT_WINDOWS: { key: LatencyWindow, label: string }[] = [
  { key: '7d', label: '7d' },
  { key: '30d', label: '30d' },
  { key: 'all', label: 'All' },
]
const latencyWindow = ref<LatencyWindow>('30d')
const latencyAgentId = ref<number | null>(null)
const latencyChannel = ref<string | null>(null)

const latencySince = computed(() => {
  if (latencyWindow.value === 'all') return new Date(0).toISOString()
  const days = latencyWindow.value === '7d' ? 7 : 30
  return new Date(Date.now() - days * 86_400_000).toISOString()
})
const latencyQuery = computed<Record<string, string>>(() => {
  const q: Record<string, string> = { since: latencySince.value }
  if (latencyAgentId.value != null) q.agentId = String(latencyAgentId.value)
  if (latencyChannel.value != null) q.channel = latencyChannel.value
  return q
})

const { data: latency, refresh: refreshLatency } = useFetch<LatencyRowsResponse>(
  '/api/metrics/latency/rows',
  { query: latencyQuery, default: () => ({ since: '', channels: [], segments: {} }), watch: [latencyQuery] },
)

// The UNKNOWN_CHANNEL bucket is system-internal LLM traffic (embedding recall,
// slash compaction, skill promotion), not a chat channel — keep it out of the
// selector. A stale 'unknown' selection self-clears via the watch below.
const latencyChannels = computed(() =>
  (latency.value?.channels ?? []).filter(c => c !== UNKNOWN_CHANNEL))
const latencySegments = computed<Record<string, LatencyHistogram>>(() => latency.value?.segments ?? {})

// Drop a channel filter that's no longer present after a window/agent reload.
watch(latencyChannels, (list) => {
  if (latencyChannel.value && !list.includes(latencyChannel.value)) latencyChannel.value = null
})

const latencyRows = computed(() =>
  buildLatencyRows<LatencyHistogram>(latencySegments.value),
)
const latencyChartSeries = computed(() =>
  buildChartSeries<LatencyHistogram>(latencySegments.value),
)

const hasLatencyData = computed(() => Object.keys(latencySegments.value).length > 0)

type LatencyView = 'table' | 'chart'
const latencyView = ref<LatencyView>('table')

// Recent Activity has two views: the EventLog feed ('all') and a video-job status table ('video').
// Video jobs aren't EventLog rows, so the 'video' view pulls VideoGenerationJob rows from a dedicated
// endpoint. It's lazy (immediate: false) — most operators never switch, so we don't pay the query on
// every dashboard load; it fetches on first switch and then rides the 5 s poll tick while active.
interface VideoGenJob {
  id: number
  state: string
  prompt: string | null
  percent: number | null
  errorMessage: string | null
  conversationId: number | null
  createdAt: string | null
}
const { data: recentVideoJobs, refresh: refreshVideoJobs } = useFetch<VideoGenJob[]>(
  '/api/videogen/jobs/recent', { immediate: false, default: () => [] },
)
type ActivityView = 'all' | 'video'
const activityView = ref<ActivityView>('all')
function setActivityView(v: ActivityView) {
  activityView.value = v
  if (v === 'video') refreshVideoJobs()
}

function formatMs(ms: number): string {
  if (ms < 1) return '<1 ms'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`
}

async function resetLatency() {
  await $fetch('/api/metrics/latency/rows', { method: 'DELETE' })
  await refreshLatency()
}

// All three dashboard panels (Chat Performance, Recent Activity, Chat Cost)
// refresh on the same 5 s tick. ChatCostSection owns its own useFetch but
// exposes refresh() via defineExpose so we can drive it from here. Keeping
// the single timer in one place is what guarantees the panels never drift
// out of phase — staggered timers produced a per-panel pending flicker
// that looked like the dashboard flashing on every refresh.
const chatCostRef = ref<{ refresh: () => Promise<void> } | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null
/**
 * Format the Recent Activity timestamp as "MMM D, YYYY · h:mm:ss AM/PM".
 * Uses the browser's locale-resolved separators but pins the format to
 * 12-hour clock so operators have a consistent AM/PM marker regardless
 * of OS locale settings.
 */
function formatActivityTimestamp(iso: string): string {
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

onMounted(() => {
  pollTimer = setInterval(() => {
    refreshLatency()
    refreshLogs()
    // Only poll video jobs while their view is showing — no cost when the operator is on the events feed.
    if (activityView.value === 'video') refreshVideoJobs()
    chatCostRef.value?.refresh()
    // Keep the per-status task counts in lockstep with the rest of the
    // dashboard's 5 s tick. Mostly cheap (3 indexed COUNT queries),
    // and an operator watching the dashboard during a task fire wants
    // to see RUNNING tick up and back down without a manual reload.
    refreshActiveTasks()
    refreshRunningTasks()
    refreshPendingTasks()
    refreshActiveReminders()
    refreshPendingReminders()
  }, 5000)
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Dashboard
    </h1>

    <!-- Stats. Each card carries a small uppercase title at the top
         that names the entity (Agents / Conversations / Channels /
         Tasks / Reminders); the big number + small label below it
         describe which slice of that entity we're counting. The Tasks
         card splits its body into three sub-stats — ACTIVE (recurring
         CRON/INTERVAL in steady state), RUNNING (currently firing),
         PENDING (one-shot SCHEDULED/IMMEDIATE waiting); Reminders
         (payloadType=reminder tasks, kept off /tasks) splits into ACTIVE
         (recurring) and PENDING (one-shot). The sub-stats stay vertically
         packed so every card matches height in the grid. -->
    <div class="grid grid-cols-2 lg:grid-cols-5 gap-4 mb-8">
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-[10px] font-medium uppercase tracking-wider text-fg-muted mb-3">
          Agents
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <div class="text-2xl font-semibold text-fg-strong leading-none">
              {{ enabledAgents }}/{{ agentCount }}
            </div>
            <div class="text-xs text-fg-muted mt-1.5">
              Active
            </div>
          </div>
          <!-- Workspace disk footprint: the agents' shared on-disk workspace,
               amber past the warn threshold so a runaway file (e.g. a shell
               loop appending forever) is visible without reaching for ncdu. -->
          <div
            v-if="workspaceBytes >= 0"
            data-testid="workspace-size"
          >
            <div
              class="text-2xl font-semibold leading-none"
              :class="workspaceBytes >= WORKSPACE_WARN_BYTES ? 'text-amber-700 dark:text-amber-400' : 'text-fg-strong'"
              data-testid="workspace-size-value"
            >
              {{ workspaceSize.value }}
            </div>
            <div
              class="text-xs text-fg-muted mt-1.5"
              data-testid="workspace-size-label"
            >
              Size (in {{ workspaceSize.unit }})
            </div>
          </div>
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-[10px] font-medium uppercase tracking-wider text-fg-muted mb-3">
          Conversations
        </div>
        <div class="text-2xl font-semibold text-fg-strong leading-none">
          {{ totalConversations.toLocaleString() }}
        </div>
        <div class="text-xs text-fg-muted mt-1.5">
          Total
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-[10px] font-medium uppercase tracking-wider text-fg-muted mb-3">
          Channels
        </div>
        <div class="text-2xl font-semibold text-fg-strong leading-none">
          {{ channelCount }}
        </div>
        <div class="text-xs text-fg-muted mt-1.5">
          Active
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-[10px] font-medium uppercase tracking-wider text-fg-muted mb-3">
          Tasks
        </div>
        <div class="grid grid-cols-3 gap-3">
          <div>
            <div class="text-2xl font-semibold text-fg-strong leading-none">
              {{ activeTasks }}
            </div>
            <div class="text-xs text-fg-muted mt-1.5">
              Active
            </div>
          </div>
          <div>
            <div class="text-2xl font-semibold text-fg-strong leading-none">
              {{ runningTasks }}
            </div>
            <div class="text-xs text-fg-muted mt-1.5">
              Running
            </div>
          </div>
          <div>
            <div class="text-2xl font-semibold text-fg-strong leading-none">
              {{ pendingTasks }}
            </div>
            <div class="text-xs text-fg-muted mt-1.5">
              Pending
            </div>
          </div>
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-[10px] font-medium uppercase tracking-wider text-fg-muted mb-3">
          Reminders
        </div>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <div class="text-2xl font-semibold text-fg-strong leading-none">
              {{ activeReminders }}
            </div>
            <div class="text-xs text-fg-muted mt-1.5">
              Active
            </div>
          </div>
          <div>
            <div class="text-2xl font-semibold text-fg-strong leading-none">
              {{ pendingReminders }}
            </div>
            <div class="text-xs text-fg-muted mt-1.5">
              Pending
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Chat Cost (JCLAW-28): persisted aggregated token usage and cost.
         Driven on the parent's 5 s tick (see pollTimer below) so all three
         dashboard panels refresh in lockstep — avoids the per-panel pending
         flicker that staggered timers caused. -->
    <ChatCostSection
      ref="chatCostRef"
      :agents="agents"
    />

    <!-- Chat Performance -->
    <div class="bg-surface-elevated border border-border mb-8">
      <!--
        Three-column header so the channel selector can center on the row
        regardless of left/right content width. grid-cols-[auto_1fr_auto]
        lets the middle column absorb flex slack while the side clusters
        size to their content (JCLAW-102).
      -->
      <div class="px-4 py-3 border-b border-border grid grid-cols-[auto_1fr_auto] items-center gap-3">
        <div class="flex items-center gap-3 min-w-0">
          <h2 class="text-sm font-medium text-fg-primary shrink-0">
            Chat Performance
          </h2>
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
              class="p-1.5 transition-colors"
              :class="latencyView === 'table'
                ? 'bg-muted text-fg-strong'
                : 'text-fg-muted hover:text-fg-strong'"
              title="Table view"
              @click="latencyView = 'table'"
            >
              <TableCellsIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="latencyView === 'chart'"
              class="p-1.5 transition-colors"
              :class="latencyView === 'chart'
                ? 'bg-muted text-fg-strong'
                : 'text-fg-muted hover:text-fg-strong'"
              title="Distribution chart"
              @click="latencyView = 'chart'"
            >
              <ChartBarIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>
        <!-- Window + agent + channel filters, centered — mirrors Chat Compression / Chat Cost. -->
        <div class="flex items-center justify-center gap-3 flex-wrap">
          <div class="inline-flex items-center border border-border overflow-hidden">
            <button
              v-for="w in LAT_WINDOWS"
              :key="w.key"
              type="button"
              class="px-2.5 py-1 text-xs"
              :class="latencyWindow === w.key ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
              @click="latencyWindow = w.key"
            >
              {{ w.label }}
            </button>
          </div>
          <select
            v-model="latencyAgentId"
            aria-label="Filter by agent"
            class="px-2 py-1 text-xs bg-muted border border-input text-fg-strong"
          >
            <option :value="null">
              All agents
            </option>
            <option
              v-for="a in (agents ?? [])"
              :key="a.id"
              :value="a.id"
            >
              {{ a.name }}
            </option>
          </select>
          <select
            v-model="latencyChannel"
            aria-label="Filter by channel"
            class="px-2 py-1 text-xs bg-muted border border-input text-fg-strong"
          >
            <option :value="null">
              All channels
            </option>
            <option
              v-for="c in latencyChannels"
              :key="c"
              :value="c"
            >
              {{ c }}
            </option>
          </select>
        </div>
        <div class="flex items-center gap-3 text-xs shrink-0">
          <button
            type="button"
            class="text-fg-muted hover:text-red-600 dark:hover:text-red-400 transition-colors p-1"
            title="Clear latency metrics"
            @click="resetLatency"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>

      <div
        v-if="!hasLatencyData"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No latency samples in this window.
      </div>

      <div
        v-else-if="latencyView === 'table'"
        class="overflow-x-auto"
      >
        <!--
          Single table that rotates based on the channel dropdown in the
          header (JCLAW-102). Each channel's distribution is meaningfully
          different (Telegram's Terminal delivery includes outbound Bot-API
          round-trip time, web's doesn't) — the dropdown lets operators pick
          which distribution they're inspecting without comingling them.
        -->
        <table class="w-full text-xs">
          <thead>
            <tr class="text-fg-muted border-b border-border">
              <th class="text-left font-normal px-4 py-2">
                Segment
              </th>
              <th class="text-right font-normal px-3 py-2">
                n
              </th>
              <th class="text-right font-normal px-3 py-2">
                p50
              </th>
              <th class="text-right font-normal px-3 py-2">
                p90
              </th>
              <th class="text-right font-normal px-3 py-2">
                p99
              </th>
              <th class="text-right font-normal px-3 py-2">
                p999
              </th>
              <th class="text-right font-normal px-3 py-2">
                min
              </th>
              <th class="text-right font-normal px-3 py-2">
                max
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in latencyRows"
              :key="row.key"
              :class="row.key === 'total' ? 'bg-muted/50 font-semibold' : 'border-b border-border last:border-b-0'"
            >
              <!--
                Child rows render with indent + muted label + smaller font —
                the Grafana / Datadog / Linear data-table pattern. Indent
                alone plus color contrast is enough to signal "these belong
                to the parent above" without glyphs or border-rules that
                fight against table cell geometry.
              -->
              <td
                class="py-2 px-4"
                :class="row.isChild
                  ? 'text-fg-muted text-[0.95em] pl-10'
                  : 'text-fg-primary'"
              >
                {{ row.label }}
              </td>
              <td class="text-right font-mono text-fg-muted px-3 py-2">
                {{ row.h.count }}
              </td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">
                {{ formatMs(row.h.p50_ms) }}
              </td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">
                {{ formatMs(row.h.p90_ms) }}
              </td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">
                {{ formatMs(row.h.p99_ms) }}
              </td>
              <td class="text-right font-mono text-fg-primary px-3 py-2">
                {{ formatMs(row.h.p999_ms) }}
              </td>
              <td class="text-right font-mono text-fg-muted px-3 py-2">
                {{ formatMs(row.h.min_ms) }}
              </td>
              <td class="text-right font-mono text-fg-muted px-3 py-2">
                {{ formatMs(row.h.max_ms) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div
        v-else
        class="p-4"
      >
        <LatencyOverlayChart :series="latencyChartSeries" />
      </div>
    </div>

    <!-- Chat Compression (JCLAW-467): per-window compression savings + breakdowns. -->
    <ChatCompressionSection :agents="agents" />

    <!-- Recent Events -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 border-b border-border flex items-center gap-3">
        <h2 class="text-sm font-medium text-fg-primary shrink-0">
          Recent Activity
        </h2>
        <div
          class="inline-flex items-center border border-border overflow-hidden"
          role="tablist"
          aria-label="Recent activity view"
        >
          <button
            type="button"
            role="tab"
            :aria-selected="activityView === 'all'"
            class="p-1.5 transition-colors"
            :class="activityView === 'all'
              ? 'bg-muted text-fg-strong'
              : 'text-fg-muted hover:text-fg-strong'"
            title="All activity"
            @click="setActivityView('all')"
          >
            <QueueListIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activityView === 'video'"
            class="p-1.5 transition-colors"
            :class="activityView === 'video'
              ? 'bg-muted text-fg-strong'
              : 'text-fg-muted hover:text-fg-strong'"
            title="Video jobs"
            @click="setActivityView('video')"
          >
            <VideoCameraIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
      <template v-if="activityView === 'all'">
        <div
          v-if="logs?.events?.length"
        >
          <!-- Column headers — same flex template as data rows so the columns
             stay aligned to the same shrink-0 widths. -->
          <div class="px-4 py-2 flex items-center gap-3 text-[10px] uppercase tracking-wider font-medium text-fg-muted border-b border-border bg-muted/30">
            <span class="shrink-0 w-10">Level</span>
            <span class="shrink-0 w-44">Category</span>
            <span class="shrink-0 w-16">Agent</span>
            <span class="flex-1 min-w-0">Message</span>
            <span class="ml-auto shrink-0 w-48 text-right">Timestamp</span>
          </div>
          <div class="divide-y divide-border">
            <div
              v-for="event in logs.events"
              :key="event.id"
              class="px-4 py-2.5 flex items-start gap-3"
            >
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
                :title="event.agentId || ''"
                class="text-xs text-fg-muted shrink-0 w-16 font-mono truncate mt-0.5"
              >{{ event.agentId || '—' }}</span>
              <span class="text-sm text-fg-primary min-w-0 truncate">{{ event.message }}</span>
              <span class="text-xs text-fg-muted ml-auto shrink-0 w-48 text-right font-mono mt-0.5">{{ formatActivityTimestamp(event.timestamp) }}</span>
            </div>
          </div>
        </div>
        <div
          v-else
          class="px-4 py-8 text-center text-sm text-fg-muted"
        >
          No recent events
        </div>
      </template>
      <template v-else>
        <div v-if="recentVideoJobs?.length">
          <!-- Column headers — same flex widths as the rows below. -->
          <div class="px-4 py-2 flex items-center gap-3 text-[10px] uppercase tracking-wider font-medium text-fg-muted border-b border-border bg-muted/30">
            <span class="shrink-0 w-20">State</span>
            <span class="flex-1 min-w-0">Prompt</span>
            <span class="shrink-0 w-48 text-right">Submitted</span>
            <span class="shrink-0 w-36 text-right">Conversation</span>
          </div>
          <div class="divide-y divide-border">
            <div
              v-for="job in recentVideoJobs"
              :key="job.id"
              class="px-4 py-2.5 flex items-center gap-3"
            >
              <span
                class="shrink-0 w-20 px-1.5 py-0.5 text-[10px] uppercase tracking-wide text-center"
                :class="{
                  'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300': job.state === 'SUCCEEDED',
                  'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300': job.state === 'FAILED',
                  'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300': job.state === 'RUNNING' || job.state === 'PENDING',
                }"
              >{{ job.state }}</span>
              <span
                class="flex-1 min-w-0 truncate text-sm text-fg-primary"
                :title="job.prompt ?? ''"
              >{{ job.prompt || '(no prompt)' }}</span>
              <span class="shrink-0 w-48 text-right text-xs text-fg-muted font-mono">{{ job.createdAt ? formatActivityTimestamp(job.createdAt) : '—' }}</span>
              <span class="shrink-0 w-36 text-right">
                <NuxtLink
                  v-if="job.conversationId != null"
                  :to="`/chat?conversation=${job.conversationId}`"
                  class="text-xs text-emerald-700 dark:text-emerald-400 hover:underline"
                >
                  see in conversation
                </NuxtLink>
                <span
                  v-else
                  class="text-xs text-fg-muted"
                >—</span>
              </span>
            </div>
          </div>
        </div>
        <div
          v-else
          class="px-4 py-8 text-center text-sm text-fg-muted"
        >
          No video generation jobs yet.
        </div>
      </template>
    </div>
  </div>
</template>
