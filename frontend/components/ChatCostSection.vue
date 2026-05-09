<script setup lang="ts">
/**
 * Fleet-level cost dashboard section (JCLAW-28). Renders above the Chat
 * Performance section on the home dashboard. Aggregates persisted token
 * usage and cost across all conversations matching the operator's filter
 * combination of agent + channel + time window.
 *
 * <p>Two visual contrasts with Chat Performance kept deliberately:
 * <ul>
 *   <li>No reset button. Cost is durable persisted data; mixing the
 *       reset affordance from the latency section would mislead.</li>
 *   <li>Time window selector (7d/30d/all-time). Latency is in-memory
 *       and trivially "current"; cost spans whatever history exists.</li>
 * </ul>
 *
 * <p>Single fetch per window change; agent + channel filter changes are
 * client-side over the loaded rows. Keeps filter switches instant and
 * the network surface small.
 */
import { ArrowDownTrayIcon, Bars3Icon, PresentationChartLineIcon } from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'
import {
  computeFleetCost,
  formatCostUsd,
  listChannelsInRows,
  type FleetCostBreakdown,
  type FleetCostFilter,
  type FleetCostRow,
} from '~/utils/usage-cost'

interface CostResponse {
  since: string
  rows: FleetCostRow[]
}

const props = defineProps<{
  agents: Agent[] | null | undefined
}>()

type WindowKey = '7d' | '30d' | 'all'
type CostView = 'table' | 'chart'

const WINDOW_LABELS: Record<WindowKey, string> = {
  '7d': 'Last 7 days',
  '30d': 'Last 30 days',
  'all': 'All time',
}

const STORAGE_KEY = 'jclaw:chat-cost:settings'

const selectedWindow = ref<WindowKey>('30d')
const selectedAgentId = ref<number | null>(null)
const selectedChannel = ref<string | null>(null)
const view = ref<CostView>('table')

// Persist filter choices across page reloads — same convention as Chat
// Performance's channel selector. Failure-tolerant for SSR / privacy-mode.
onMounted(() => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw) as Partial<{
      window: WindowKey
      agentId: number | null
      channel: string | null
      view: CostView
    }>
    if (parsed.window && (parsed.window in WINDOW_LABELS)) selectedWindow.value = parsed.window
    if (parsed.agentId !== undefined) selectedAgentId.value = parsed.agentId
    if (parsed.channel !== undefined) selectedChannel.value = parsed.channel
    if (parsed.view === 'chart' || parsed.view === 'table') view.value = parsed.view
  }
  catch { /* ignore */ }
})

function persistSettings() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      window: selectedWindow.value,
      agentId: selectedAgentId.value,
      channel: selectedChannel.value,
      view: view.value,
    }))
  }
  catch { /* ignore */ }
}

watch([selectedWindow, selectedAgentId, selectedChannel, view], persistSettings)

// Compute the ISO `since` param for the current window. "all" sends a
// far-past date so the server returns everything; cleaner than special-
// casing a missing param on both sides.
const sinceParam = computed(() => {
  if (selectedWindow.value === 'all') {
    return new Date(0).toISOString()
  }
  const days = selectedWindow.value === '7d' ? 7 : 30
  const ms = Date.now() - days * 24 * 60 * 60 * 1000
  return new Date(ms).toISOString()
})

const { data: costData, refresh, pending } = useFetch<CostResponse>('/api/metrics/cost', {
  query: { since: sinceParam },
  default: () => ({ since: '', rows: [] }),
  watch: [sinceParam],
})

const rows = computed<FleetCostRow[]>(() => costData.value?.rows ?? [])

// Channel options: only channel kinds that have data in the loaded window.
// Operator filtering by a channel that has no data would be confusing;
// surfacing only-with-data matches Chat Performance's behavior.
const availableChannels = computed(() => listChannelsInRows(rows.value))

// Reset filters that no longer make sense as the data set changes.
watchEffect(() => {
  if (selectedChannel.value !== null
    && !availableChannels.value.includes(selectedChannel.value)) {
    selectedChannel.value = null
  }
})

const filter = computed<FleetCostFilter>(() => ({
  agentId: selectedAgentId.value,
  channelType: selectedChannel.value,
}))

const breakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(rows.value, filter.value),
)

const hasData = computed(() => breakdown.value.turnCount > 0)

// Resolve agent display names — operator sees "main" not "agent #7" in the
// per-agent table. Falls back to the id when the agent has been deleted
// since the rows were emitted.
const agentNameById = computed(() => {
  const map = new Map<number, string>()
  for (const a of props.agents ?? []) map.set(a.id, a.name)
  return map
})

function agentLabel(id: number): string {
  return agentNameById.value.get(id) ?? `agent #${id}`
}

// Chart geometry. Horizontal bars: one per agent (or per model in chart
// view). Width is proportional to cost share. Inline SVG, no library.
const chartRows = computed(() => {
  // Pick the dimension with more entries — usually agent for fleet view,
  // model when filtered to one agent.
  const useModel = breakdown.value.perModel.length >= breakdown.value.perAgent.length
  return useModel
    ? breakdown.value.perModel.map(m => ({
        label: m.modelProvider ? `${m.modelProvider}/${m.modelId}` : m.modelId,
        cost: m.total,
        turnCount: m.turnCount,
      }))
    : breakdown.value.perAgent.map(a => ({
        label: agentLabel(a.agentId),
        cost: a.total,
        turnCount: a.turnCount,
      }))
})

const chartMaxCost = computed(() => {
  const max = chartRows.value.reduce((m, r) => Math.max(m, r.cost), 0)
  // Floor a tiny minimum so a fully-free fleet still renders proportional
  // bars based on turn counts (not actually wired here, but prevents NaN).
  return max === 0 ? 1 : max
})

// CSV export. Generates per-model breakdown (one row per model with cost
// and token totals) since that's the most actionable view; the operator
// can pivot externally if they need agent or channel cuts.
function exportCsv() {
  const header = [
    'modelProvider', 'modelId', 'turnCount', 'totalCost',
    'promptTokens', 'completionTokens', 'reasoningTokens', 'cachedTokens',
  ].join(',')
  const lines = breakdown.value.perModel.map(m => [
    csvCell(m.modelProvider ?? ''),
    csvCell(m.modelId),
    m.turnCount.toString(),
    m.total.toFixed(6),
    m.prompt.toString(),
    m.completion.toString(),
    m.reasoning.toString(),
    m.cached.toString(),
  ].join(','))
  const csv = [header, ...lines].join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  const filterTag = [
    selectedAgentId.value !== null ? `agent-${selectedAgentId.value}` : 'all-agents',
    selectedChannel.value ?? 'all-channels',
    selectedWindow.value,
  ].join('_')
  a.download = `chat-cost_${filterTag}_${new Date().toISOString().slice(0, 10)}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/**
 * Quote a CSV cell when it contains a comma, quote, or newline. Doubles
 * embedded quotes per RFC 4180. Cheap defensive escape so an odd model id
 * never produces a malformed export.
 */
function csvCell(value: string): string {
  if (!/[",\n]/.test(value)) return value
  return '"' + value.replace(/"/g, '""') + '"'
}

defineExpose({ refresh })
</script>

<template>
  <div class="bg-surface-elevated border border-border mb-8">
    <!--
      Three-column header matching the Chat Performance pattern: title +
      view toggle on the left, filter cluster centered, CSV button right.
    -->
    <div class="px-4 py-3 border-b border-border grid grid-cols-[auto_1fr_auto] items-center gap-3">
      <div class="flex items-center gap-3 min-w-0">
        <h2 class="text-sm font-medium text-fg-primary shrink-0">
          Chat Cost
        </h2>
        <div
          v-if="hasData"
          class="inline-flex items-center border border-border overflow-hidden"
          role="tablist"
          aria-label="Chat cost view"
        >
          <button
            type="button"
            role="tab"
            :aria-selected="view === 'table'"
            class="p-1 transition-colors"
            :class="view === 'table'
              ? 'bg-muted text-fg-strong'
              : 'text-fg-muted hover:text-fg-strong'"
            title="Table view"
            @click="view = 'table'"
          >
            <Bars3Icon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="view === 'chart'"
            class="p-1 transition-colors border-l border-border"
            :class="view === 'chart'
              ? 'bg-muted text-fg-strong'
              : 'text-fg-muted hover:text-fg-strong'"
            title="Bar chart view"
            @click="view = 'chart'"
          >
            <PresentationChartLineIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>

      <!-- Filters: three dropdowns side by side, centered -->
      <div class="flex items-center justify-center gap-3 flex-wrap">
        <label
          for="chat-cost-agent"
          class="inline-flex items-center gap-2 text-xs"
        >
          <span class="text-fg-muted">Agent</span>
          <select
            id="chat-cost-agent"
            v-model="selectedAgentId"
            class="bg-surface border border-border text-fg-primary text-xs px-2 py-1 focus:outline-none focus:border-fg-muted"
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
        </label>

        <label
          for="chat-cost-channel"
          class="inline-flex items-center gap-2 text-xs"
        >
          <span class="text-fg-muted">Channel</span>
          <select
            id="chat-cost-channel"
            v-model="selectedChannel"
            class="bg-surface border border-border text-fg-primary text-xs px-2 py-1 focus:outline-none focus:border-fg-muted"
          >
            <option :value="null">
              All channels
            </option>
            <option
              v-for="ch in availableChannels"
              :key="ch"
              :value="ch"
            >
              {{ ch }}
            </option>
          </select>
        </label>

        <label
          for="chat-cost-window"
          class="inline-flex items-center gap-2 text-xs"
        >
          <span class="text-fg-muted">Window</span>
          <select
            id="chat-cost-window"
            v-model="selectedWindow"
            class="bg-surface border border-border text-fg-primary text-xs px-2 py-1 focus:outline-none focus:border-fg-muted"
          >
            <option
              v-for="(label, key) in WINDOW_LABELS"
              :key="key"
              :value="key"
            >
              {{ label }}
            </option>
          </select>
        </label>
      </div>

      <div class="flex items-center gap-3 text-xs shrink-0">
        <button
          type="button"
          class="inline-flex items-center gap-1 text-fg-muted hover:text-fg-strong transition-colors p-1 disabled:opacity-40 disabled:cursor-not-allowed"
          :disabled="!hasData"
          title="Export per-model breakdown to CSV"
          @click="exportCsv"
        >
          <ArrowDownTrayIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
          <span>CSV</span>
        </button>
      </div>
    </div>

    <!-- Body: pending / empty / table / chart -->
    <div
      v-if="pending"
      class="px-4 py-8 text-center text-sm text-fg-muted"
    >
      Loading cost data…
    </div>

    <div
      v-else-if="!hasData"
      class="px-4 py-8 text-center text-sm text-fg-muted"
    >
      No conversations match the current filter.
    </div>

    <template v-else>
      <!-- Aggregate summary row, always visible above the detail breakdown -->
      <div class="px-4 py-3 grid grid-cols-2 sm:grid-cols-5 gap-3 border-b border-border bg-muted/30">
        <div>
          <div class="text-xs text-fg-muted mb-0.5">
            Total cost
          </div>
          <div class="text-sm font-mono text-fg-strong">
            {{ formatCostUsd(breakdown.total) }}
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-muted mb-0.5">
            Turns
          </div>
          <div class="text-sm font-mono text-fg-strong">
            {{ breakdown.turnCount.toLocaleString() }}
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-muted mb-0.5">
            Prompt tokens
          </div>
          <div class="text-sm font-mono text-fg-strong">
            {{ breakdown.prompt.toLocaleString() }}
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-muted mb-0.5">
            Completion
          </div>
          <div class="text-sm font-mono text-fg-strong">
            {{ breakdown.completion.toLocaleString() }}
          </div>
        </div>
        <div>
          <div class="text-xs text-fg-muted mb-0.5">
            Reasoning · Cached
          </div>
          <div class="text-sm font-mono text-fg-strong">
            {{ breakdown.reasoning.toLocaleString() }} · {{ breakdown.cached.toLocaleString() }}
          </div>
        </div>
      </div>

      <!-- Table view: per-model breakdown sorted by cost descending -->
      <div
        v-if="view === 'table'"
        class="overflow-x-auto"
      >
        <table class="w-full text-xs">
          <thead class="text-fg-muted bg-muted/20">
            <tr>
              <th class="text-left px-4 py-2 font-medium">
                Model
              </th>
              <th class="text-right px-3 py-2 font-medium">
                Turns
              </th>
              <th class="text-right px-3 py-2 font-medium">
                Cost
              </th>
              <th class="text-right px-3 py-2 font-medium">
                Prompt
              </th>
              <th class="text-right px-3 py-2 font-medium">
                Completion
              </th>
              <th class="text-right px-3 py-2 font-medium">
                Reasoning
              </th>
              <th class="text-right px-3 py-2 font-medium">
                Cached
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="m in breakdown.perModel"
              :key="m.modelId"
              class="border-t border-border"
            >
              <td class="px-4 py-2 font-mono text-fg-primary">
                <span
                  v-if="m.modelProvider"
                  class="text-fg-muted"
                >{{ m.modelProvider }}/</span>{{ m.modelId }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-primary">
                {{ m.turnCount.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-strong">
                {{ formatCostUsd(m.total) }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ m.prompt.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ m.completion.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ m.reasoning.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ m.cached.toLocaleString() }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!--
        Chart view: horizontal bars sorted by cost. Picks per-model when the
        breakdown has more model entries than agent entries (typical fleet
        view); switches to per-agent when an agent filter narrows the scope.
        Inline SVG with relative widths — no library, scales via flexbox.
      -->
      <div
        v-else
        class="px-4 py-3 space-y-1.5"
      >
        <div
          v-for="(r, i) in chartRows"
          :key="i"
          class="grid grid-cols-[1fr_3fr_auto] items-center gap-3 text-xs"
        >
          <div
            class="font-mono text-fg-primary truncate"
            :title="r.label"
          >
            {{ r.label }}
          </div>
          <div class="relative h-5 bg-muted/30 border border-border overflow-hidden">
            <div
              class="h-full bg-emerald-500/30"
              :style="{ width: ((r.cost / chartMaxCost) * 100).toFixed(2) + '%' }"
            />
          </div>
          <div class="font-mono text-fg-strong tabular-nums shrink-0">
            {{ formatCostUsd(r.cost) }}
            <span class="text-fg-muted">· {{ r.turnCount }}t</span>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
