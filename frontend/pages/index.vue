<script setup lang="ts">
import {
  ArrowPathIcon,
  Bars3Icon,
  PresentationChartLineIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'
import type { Agent, LatencyHistogram, LatencyMetrics, LogEvent } from '~/types/api'

// --- Latency metrics (chat performance panel) ---
// Row assembly (top-level order, prologue_* child nesting, chart-vs-table
// split) lives in ~/utils/latency-rows for unit-testability without
// mounting the dashboard.
import { buildLatencyRows, buildChartSeries, listAvailableChannels } from '~/utils/latency-rows'
import { loadTourStatus, useGuidedTour } from '~/composables/useGuidedTour'
import TourIntroDialog from '~/components/TourIntroDialog.vue'

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

const { data: latency, refresh: refreshLatency } = useFetch<LatencyMetrics>(
  '/api/metrics/latency',
  { default: () => ({}) },
)

// The payload is now {channel: {segment: hist}}. One channel renders at
// a time, selected via the dropdown; switching channels rotates both the
// table and the chart. Empty channels are suppressed from the dropdown
// so the user never sees an option that would render nothing.
const latencyChannels = computed(() =>
  listAvailableChannels<LatencyHistogram>((latency.value ?? {}) as LatencyMetrics),
)

const LATENCY_CHANNEL_STORAGE_KEY = 'jclaw:chat-perf:selected-channel'
const selectedChannel = ref<string>('')

// Keep selection valid as channels come and go. If the stored/prior
// choice isn't in the current list, fall back to the first available
// (listAvailableChannels puts web first when present).
watchEffect(() => {
  const available = latencyChannels.value
  if (available.length === 0) {
    selectedChannel.value = ''
    return
  }
  if (available.some(c => c.key === selectedChannel.value)) return
  selectedChannel.value = available[0]!.key
})

// ──────────────────────── Guided tour intro dialog ───────────────────────
// First-login auto-trigger. Threshold lives server-side; the session-skip
// flag prevents re-popping after the user clicked Skip and is reloading or
// navigating within the same session. Both gates must pass.
const SESSION_SKIP_KEY = 'jclaw.tour.skippedThisSession'
const showTourIntro = ref(false)
const { start: startTour } = useGuidedTour()

onMounted(async () => {
  if (sessionStorage.getItem(SESSION_SKIP_KEY)) return
  const status = await loadTourStatus()
  // Re-check after the await — defensive against any future writer that
  // sets the flag while the network round-trip is in flight. Today the
  // only writer is onTourSkip in this same component (which can't fire
  // before the dialog is visible), so this is belt-and-suspenders.
  if (sessionStorage.getItem(SESSION_SKIP_KEY)) return
  if (status.shouldAutoShow) showTourIntro.value = true
})

function onTourStart() {
  // Don't write the session-skip flag here — recordStepReached() in
  // useGuidedTour writes the cross-session threshold as soon as the user
  // advances one step, so suppression for "user is taking the tour" is
  // already handled. Not writing here also means a Reset → revisit cycle
  // correctly re-shows this dialog when the threshold has been cleared.
  showTourIntro.value = false
  startTour()
}

function onTourSkip() {
  showTourIntro.value = false
  try {
    sessionStorage.setItem(SESSION_SKIP_KEY, '1')
  }
  catch {
    // private mode
  }
}

onMounted(() => {
  try {
    const stored = localStorage.getItem(LATENCY_CHANNEL_STORAGE_KEY)
    if (stored && latencyChannels.value.some(c => c.key === stored)) {
      selectedChannel.value = stored
    }
  }
  catch { /* SSR / privacy mode — stick with default */ }
})

function onSelectChannel(key: string) {
  selectedChannel.value = key
  try {
    localStorage.setItem(LATENCY_CHANNEL_STORAGE_KEY, key)
  }
  catch { /* ignore */ }
}

const selectedChannelMetrics = computed<Record<string, LatencyHistogram>>(() => {
  const payload = (latency.value ?? {}) as LatencyMetrics
  return payload[selectedChannel.value] ?? {}
})

const latencyRows = computed(() =>
  buildLatencyRows<LatencyHistogram>(selectedChannelMetrics.value),
)
const latencyChartSeries = computed(() =>
  buildChartSeries<LatencyHistogram>(selectedChannelMetrics.value),
)

const hasLatencyData = computed(() => latencyChannels.value.length > 0)

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
    <TourIntroDialog
      :open="showTourIntro"
      @start="onTourStart"
      @skip="onTourSkip"
      @update:open="showTourIntro = $event"
    />
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Dashboard
    </h1>

    <!-- Stats -->
    <div class="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-8">
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">
          {{ enabledAgents }}/{{ agentCount }}
        </div>
        <div class="text-xs text-fg-muted mt-1">
          Agents enabled
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">
          {{ channelCount }}
        </div>
        <div class="text-xs text-fg-muted mt-1">
          Channels active
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">
          {{ pendingTasks }}
        </div>
        <div class="text-xs text-fg-muted mt-1">
          Tasks pending
        </div>
      </div>
      <div class="bg-surface-elevated border border-border p-4">
        <div class="text-2xl font-semibold text-fg-strong">
          {{ logs?.events?.length ?? 0 }}
        </div>
        <div class="text-xs text-fg-muted mt-1">
          Recent events
        </div>
      </div>
    </div>

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
              class="p-1 transition-colors"
              :class="latencyView === 'table'
                ? 'bg-muted text-fg-strong'
                : 'text-fg-muted hover:text-fg-strong'"
              title="Table view"
              @click="latencyView = 'table'"
            >
              <Bars3Icon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="latencyView === 'chart'"
              class="p-1 transition-colors border-l border-border"
              :class="latencyView === 'chart'
                ? 'bg-muted text-fg-strong'
                : 'text-fg-muted hover:text-fg-strong'"
              title="Distribution chart"
              @click="latencyView = 'chart'"
            >
              <PresentationChartLineIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>
        <div class="flex items-center justify-center">
          <!--
            Channel selector. Hidden entirely when no channels have samples
            (matches the "no samples yet" empty state below). Visible even
            when only one channel has samples so the operator can see which
            channel they're looking at; the select is just a static display
            in that case but still communicates context.

            Label both wraps the control and carries for/id to match the
            repo's established pattern (see pages/channels/telegram.vue).
            Satisfies both nesting and id-association strategies of
            vuejs-accessibility/label-has-for.
          -->
          <label
            v-if="latencyChannels.length > 0"
            for="chat-performance-channel"
            class="inline-flex items-center gap-2 text-xs"
          >
            <span class="text-fg-muted">Channel</span>
            <select
              id="chat-performance-channel"
              :value="selectedChannel"
              class="bg-surface border border-border text-fg-primary text-xs px-2 py-1 focus:outline-none focus:border-fg-muted"
              @change="onSelectChannel(($event.target as HTMLSelectElement).value)"
            >
              <option
                v-for="channel in latencyChannels"
                :key="channel.key"
                :value="channel.key"
              >
                {{ channel.label }}
              </option>
            </select>
          </label>
        </div>
        <div class="flex items-center gap-3 text-xs shrink-0">
          <span class="text-fg-muted hidden sm:inline">In-memory • resets on JVM restart</span>
          <button
            type="button"
            class="text-fg-muted hover:text-fg-strong transition-colors p-1"
            title="Reset latency histograms"
            @click="resetLatency"
          >
            <TrashIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>

      <div
        v-if="!hasLatencyData"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No samples yet. Send a chat message to populate latency histograms.
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
              :class="row.key === 'total' ? 'border-t-2 border-fg-muted/30 bg-muted/30 font-semibold' : 'border-b border-border last:border-b-0'"
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

    <!-- Recent Events -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
        <h2 class="text-sm font-medium text-fg-primary">
          Recent Activity
        </h2>
        <div class="flex items-center gap-3 text-xs shrink-0">
          <span
            v-if="logsAgeLabel"
            class="text-fg-muted"
          >{{ logsAgeLabel }}</span>
          <button
            type="button"
            class="text-fg-muted hover:text-fg-strong transition-colors p-1"
            title="Refresh"
            @click="refreshRecentActivity"
          >
            <ArrowPathIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
      <div
        v-if="logs?.events?.length"
        class="divide-y divide-border"
      >
        <div
          v-for="event in logs.events"
          :key="event.id"
          class="px-4 py-2.5 flex items-start gap-3"
        >
          <span
            :class="{
              'text-red-400': event.level === 'ERROR',
              'text-yellow-400': event.level === 'WARN',
              'text-fg-muted': event.level === 'INFO',
            }"
            class="text-xs font-mono mt-0.5 shrink-0 w-10"
          >{{ event.level }}</span>
          <span class="text-xs text-fg-muted shrink-0 w-16 font-mono">{{ event.category }}</span>
          <span
            v-if="event.agentId"
            class="text-xs text-fg-muted shrink-0 font-mono"
          >{{ event.agentId }}</span>
          <span class="text-sm text-fg-primary min-w-0 truncate">{{ event.message }}</span>
          <span class="text-xs text-fg-muted ml-auto shrink-0">{{ new Date(event.timestamp).toLocaleTimeString() }}</span>
        </div>
      </div>
      <div
        v-else
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No recent events
      </div>
    </div>
  </div>
</template>
