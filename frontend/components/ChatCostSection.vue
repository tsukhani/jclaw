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
import {
  ArrowDownTrayIcon, Bars3Icon, ChevronDownIcon, ChevronUpIcon,
  PresentationChartLineIcon,
} from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'
import {
  computeFleetCost,
  formatCostUsd,
  listChannelsInRows,
  type FleetCostBreakdown,
  type FleetCostFilter,
  type FleetCostPerModel,
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

// JCLAW-280: provider modality + monthly subscription price. Drives the
// per-token vs subscription partition of the Chat Cost dashboard. Defaults
// (empty maps) are safe: every row is treated as PER_TOKEN and the
// subscription subsection collapses to nothing until /api/providers loads.
interface ProviderInfo {
  name: string
  paymentModality: 'PER_TOKEN' | 'SUBSCRIPTION'
  subscriptionMonthlyUsd: number
  supportedModalities: ('PER_TOKEN' | 'SUBSCRIPTION')[]
}
const { data: providersInfo } = useFetch<ProviderInfo[]>('/api/providers', {
  default: () => [],
})

const modalityByProvider = computed(() => {
  const m = new Map<string, 'PER_TOKEN' | 'SUBSCRIPTION'>()
  for (const p of providersInfo.value ?? []) m.set(p.name, p.paymentModality)
  return m
})

// Partition raw rows by modality. Rows whose modelProvider doesn't match a
// known SUBSCRIPTION provider fall into the per-token bucket — that covers
// rows from registry-removed providers and any pre-JCLAW-280 rows where the
// modality wasn't yet a concept.
const perTokenRows = computed<FleetCostRow[]>(() =>
  rows.value.filter(r => modalityByProvider.value.get(r.modelProvider ?? '') !== 'SUBSCRIPTION'),
)
const subscriptionRows = computed<FleetCostRow[]>(() =>
  rows.value.filter(r => modalityByProvider.value.get(r.modelProvider ?? '') === 'SUBSCRIPTION'),
)

// Configured subscription providers. Drives the fee accrual — the
// operator is paying the monthly regardless of in-window activity, so a
// quiet week still shows the fee. Distinct from
// `subscriptionRows`-derived stats, which reflect only activity that
// actually happened.
const configuredSubscriptionProviders = computed(() => {
  const out: ProviderInfo[] = []
  for (const p of providersInfo.value ?? []) {
    if (p.paymentModality === 'SUBSCRIPTION') out.push(p)
  }
  return out
})

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

// JCLAW-280: per-modality breakdowns. The original `breakdown` stays as the
// whole-fleet aggregate (still used by the agent/channel filter availability
// logic and as the source of `hasData`); the partitioned breakdowns drive
// the rendered subsections.
const perTokenBreakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(perTokenRows.value, filter.value),
)
const subscriptionBreakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(subscriptionRows.value, filter.value),
)

// Window length in days — used to pro-rate the subscription monthly fee.
// For the "all time" window we fall back to the full row set's earliest
// timestamp (not just subscription rows): the operator is asking "how
// much did I spend over all my recorded activity?" and answering that
// in terms of subscription-only data would understate the window when
// the operator has months of per-token activity but only recently
// started a subscription.
const windowDays = computed(() => {
  if (selectedWindow.value === '7d') return 7
  if (selectedWindow.value === '30d') return 30
  const allRows = rows.value
  if (allRows.length === 0) return 30
  let earliest = Date.now()
  for (const r of allRows) {
    const t = Date.parse(r.timestamp)
    if (!isNaN(t) && t < earliest) earliest = t
  }
  const spanMs = Math.max(0, Date.now() - earliest)
  return Math.max(1, spanMs / (24 * 60 * 60 * 1000))
})

// Pro-rated subscription accrual = sum of monthly fees across every
// configured subscription provider × (window_days / 30). Independent of
// in-window activity — the operator is paying the monthly fee whether
// they used the provider this week or not.
const subscriptionFee = computed(() => {
  let sumMonthly = 0
  for (const p of configuredSubscriptionProviders.value) {
    sumMonthly += Number(p.subscriptionMonthlyUsd) || 0
  }
  return sumMonthly * (windowDays.value / 30)
})

// Subscription subsection visibility — render whenever the operator has
// at least one subscription provider configured, so the standing fee
// accrual is always surfaced even on weeks with no subscription-modality
// activity. Per-token visibility is governed by the pre-existing
// `hasPaidData` (defined below) which also gates the per-model table —
// keeping a single source of truth means the strip and the table can't
// disagree about whether to render.
const hasSubscriptionSection = computed(() => configuredSubscriptionProviders.value.length > 0)

// Combined total = per-token actuals + pro-rated subscription accrual.
const combinedTotal = computed(() => perTokenBreakdown.value.total + subscriptionFee.value)

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

// ── Per-model table sort state ────────────────────────────────────────────
// Operator clicks a column header to sort. Numeric columns default to
// descending (biggest spend first feels more useful at a glance); the
// Model column defaults to ascending (alphabetical reads naturally).
// Click the same column again to flip direction; click a different column
// to switch and reset to that column's natural default direction.
type SortColumn = 'model' | 'turnCount' | 'total' | 'prompt' | 'completion' | 'reasoning' | 'cached'
type SortDir = 'asc' | 'desc'

const sortBy = ref<SortColumn>('total')
const sortDir = ref<SortDir>('desc')

function defaultDirFor(col: SortColumn): SortDir {
  return col === 'model' ? 'asc' : 'desc'
}

function toggleSort(col: SortColumn) {
  if (sortBy.value === col) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  }
  else {
    sortBy.value = col
    sortDir.value = defaultDirFor(col)
  }
}

function modelLabel(m: FleetCostPerModel): string {
  return m.modelProvider ? `${m.modelProvider}/${m.modelId}` : m.modelId
}

// Restrict the per-model breakdown to models that actually contributed
// to per-token cost — operator's question is "which models cost what,"
// not "what activity happened on free tiers." Subscription-provider rows
// are excluded by sourcing from perTokenBreakdown rather than the whole
// breakdown: their per-row "cost" is bundled into the monthly fee and
// double-counting it here would inflate the totals.
const paidPerModel = computed<FleetCostPerModel[]>(() =>
  perTokenBreakdown.value.perModel.filter(m => m.total > 0),
)

// Distinguishes "no data at all in this window" from "all per-token data
// was free-tier" — the second case needs its own empty-state message so
// the operator isn't confused about why the table looks empty when the
// summary row shows positive turn counts.
const hasPaidData = computed(() => paidPerModel.value.length > 0)

/**
 * Format a subscription fee in plain-currency style — "$100" when the
 * amount is whole dollars, "$99.99" otherwise. Distinct from
 * {@link formatCostUsd} which renders four decimals and is used for the
 * per-token side where sub-cent precision matters; subscription fees are
 * always whole-dollar amounts at the source, so trailing-zero noise just
 * looks like a programmatic artifact.
 */
function formatSubscriptionFee(amount: number): string {
  const rounded = Math.round(amount * 100) / 100
  return rounded % 1 === 0 ? `$${rounded.toFixed(0)}` : `$${rounded.toFixed(2)}`
}

const sortedPerModel = computed<FleetCostPerModel[]>(() => {
  const rows = [...paidPerModel.value]
  const dir = sortDir.value === 'asc' ? 1 : -1
  rows.sort((a, b) => {
    let cmp = 0
    if (sortBy.value === 'model') {
      cmp = modelLabel(a).localeCompare(modelLabel(b))
    }
    else if (sortBy.value === 'turnCount') {
      cmp = a.turnCount - b.turnCount
    }
    else {
      // total / prompt / completion / reasoning / cached are all numeric
      // members of FleetCostPerModel with the same name as the column.
      cmp = (a[sortBy.value] as number) - (b[sortBy.value] as number)
    }
    if (cmp !== 0) return cmp * dir
    // Tie-breaker: alphabetical by model so the order is deterministic
    // when (e.g.) two free-tier models both report $0.00 cost.
    return modelLabel(a).localeCompare(modelLabel(b))
  })
  return rows
})

// JCLAW-280: subscription per-model breakdown. Distinct from `paidPerModel`
// because subscription rows have no per-row cost attribution (their cost
// is the flat monthly fee surfaced in the strip above), so the table
// drops the Cost column and shows only activity stats. Filters out
// zero-turn entries — they shouldn't exist in `subscriptionBreakdown.perModel`
// by construction but the filter guards against future edge cases.
// Fixed sort: turn count descending, model name tie-break. The per-token
// table's interactive sort is overkill here — subscription is a small set
// (usually 1–2 models per provider) and the operator's question is
// "what's running against my subscription," not "which is most expensive."
const subscriptionPerModel = computed<FleetCostPerModel[]>(() => {
  const items = subscriptionBreakdown.value.perModel
    .filter(m => m.turnCount > 0)
  items.sort((a, b) => {
    if (a.turnCount !== b.turnCount) return b.turnCount - a.turnCount
    return modelLabel(a).localeCompare(modelLabel(b))
  })
  return items
})

// Chart geometry. Horizontal bars: one per agent (or per model in chart
// view). Width is proportional to cost share. Inline SVG, no library.
// Same paid-only filter as the table — chart visualizes cost contribution,
// so zero-bars for free-tier rows would be visual noise.
const chartRows = computed(() => {
  // Scoped to per-token activity for the same reason paidPerModel is —
  // subscription costs aren't attributable to individual models or agents.
  const paidModels = perTokenBreakdown.value.perModel.filter(m => m.total > 0)
  const paidAgents = perTokenBreakdown.value.perAgent.filter(a => a.total > 0)
  // Pick the dimension with more entries — usually agent for fleet view,
  // model when filtered to one agent.
  const useModel = paidModels.length >= paidAgents.length
  return useModel
    ? paidModels.map(m => ({
        label: m.modelProvider ? `${m.modelProvider}/${m.modelId}` : m.modelId,
        cost: m.total,
        turnCount: m.turnCount,
      }))
    : paidAgents.map(a => ({
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
// can pivot externally if they need agent or channel cuts. Honors the
// active table sort so the export matches what the operator is looking
// at — useful when they're viewing "sort by reasoning tokens desc" and
// want that ordering preserved in the spreadsheet.
function exportCsv() {
  const header = [
    'modelProvider', 'modelId', 'turnCount', 'totalCost',
    'promptTokens', 'completionTokens', 'reasoningTokens', 'cachedTokens',
  ].join(',')
  const lines = sortedPerModel.value.map(m => [
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
          v-if="hasPaidData"
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
          :disabled="!hasPaidData"
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
      v-else-if="!hasData && !hasSubscriptionSection"
      class="px-4 py-8 text-center text-sm text-fg-muted"
    >
      No conversations match the current filter.
    </div>

    <template v-else>
      <!-- JCLAW-280: subscription subsection (rendered first because it
           reflects an already-committed monthly spend, while per-token is
           accruing-as-you-go — operators read the standing commitment
           before the variable line, matching how P&L statements are
           ordered). Shows turns + tokens for subscription-provider
           activity plus the pro-rated monthly fee. Subscription has no
           per-row cost attribution so this subsection never feeds the
           per-model table or chart below. -->
      <div
        v-if="hasSubscriptionSection"
        class="border-b border-border"
      >
        <div class="px-4 pt-3 pb-1 text-xs font-medium text-fg-muted uppercase tracking-wide">
          Subscription
        </div>
        <div class="px-4 pb-3 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 bg-muted/30">
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Fee (window)
            </div>
            <div
              class="text-sm font-mono text-emerald-700 dark:text-emerald-400"
              :title="`monthly × (window_days / 30) = ${formatSubscriptionFee(subscriptionFee)} over ${windowDays.toFixed(1)} days`"
            >
              {{ formatSubscriptionFee(subscriptionFee) }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Turns
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ subscriptionBreakdown.turnCount.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Prompt tokens
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ subscriptionBreakdown.prompt.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Completion
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ subscriptionBreakdown.completion.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Reasoning
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ subscriptionBreakdown.reasoning.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Cached
            </div>
            <div class="text-sm font-mono text-yellow-700 dark:text-yellow-400">
              {{ subscriptionBreakdown.cached.toLocaleString() }}
            </div>
          </div>
        </div>

        <!-- Per-model breakdown for subscription activity. Drops the Cost
             column (subscription has no per-row cost attribution — the
             fee at the top is the total). Fixed sort by turn count
             descending; interactive sort would be overkill on what's
             usually a 1–2 model set per subscription provider. Only
             rendered in the table view; chart view applies to per-token
             cost contribution only. -->
        <div
          v-if="view === 'table' && subscriptionPerModel.length > 0"
          class="overflow-x-auto border-t border-border"
        >
          <table class="w-full text-xs">
            <thead class="text-fg-muted bg-muted/20">
              <tr>
                <th
                  scope="col"
                  class="text-left px-4 py-2 font-medium"
                >
                  Model
                </th>
                <th
                  scope="col"
                  class="text-right px-3 py-2 font-medium"
                >
                  Turns
                </th>
                <th
                  scope="col"
                  class="text-right px-3 py-2 font-medium"
                >
                  Prompt
                </th>
                <th
                  scope="col"
                  class="text-right px-3 py-2 font-medium"
                >
                  Completion
                </th>
                <th
                  scope="col"
                  class="text-right px-3 py-2 font-medium"
                >
                  Reasoning
                </th>
                <th
                  scope="col"
                  class="text-right px-3 py-2 font-medium"
                >
                  Cached
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="m in subscriptionPerModel"
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
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ m.prompt.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ m.completion.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ m.reasoning.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-yellow-700 dark:text-yellow-400">
                  {{ m.cached.toLocaleString() }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- JCLAW-280: per-token subsection. Summary strip + (further down)
           per-model table/chart. Rendered only when paid per-token activity
           exists in the window. Subscription-provider turns are excluded by
           sourcing from perTokenBreakdown. -->
      <div
        v-if="hasPaidData"
        class="border-b border-border"
      >
        <div class="px-4 pt-3 pb-1 text-xs font-medium text-fg-muted uppercase tracking-wide">
          Per-token
        </div>
        <div class="px-4 pb-3 grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-3 bg-muted/30">
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Cost
            </div>
            <div class="text-sm font-mono text-emerald-700 dark:text-emerald-400">
              {{ formatCostUsd(perTokenBreakdown.total) }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Turns
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ perTokenBreakdown.turnCount.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Prompt tokens
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ perTokenBreakdown.prompt.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Completion
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ perTokenBreakdown.completion.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Reasoning
            </div>
            <div class="text-sm font-mono text-fg-strong">
              {{ perTokenBreakdown.reasoning.toLocaleString() }}
            </div>
          </div>
          <div>
            <div class="text-xs text-fg-muted mb-0.5">
              Cached
            </div>
            <div class="text-sm font-mono text-yellow-700 dark:text-yellow-400">
              {{ perTokenBreakdown.cached.toLocaleString() }}
            </div>
          </div>
        </div>
      </div>

      <!-- All-free-tier empty state. Only rendered when no paid per-token
           activity AND no subscription activity exist — both subsections
           are suppressed because their aggregates would all be free-tier
           counts under a cost-attribution section. -->
      <div
        v-if="!hasPaidData && !hasSubscriptionSection"
        class="px-4 py-6 text-center text-sm text-fg-muted"
      >
        All turns in this window were on free-tier models — no cost to attribute.
      </div>

      <!--
        Per-model breakdown table. Click any header cell to sort by that
        column; click again to flip direction. Active column shows a
        chevron in its sort direction. Default: Cost descending. Free-tier
        models (total === 0) are filtered out to keep the table focused
        on cost contributors.
      -->
      <div
        v-else-if="view === 'table'"
        class="overflow-x-auto"
      >
        <table class="w-full text-xs">
          <thead class="text-fg-muted bg-muted/20">
            <tr>
              <th
                scope="col"
                class="text-left px-4 py-2 font-medium"
              >
                <button
                  type="button"
                  class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                  :class="sortBy === 'model' ? 'text-fg-strong' : ''"
                  :aria-sort="sortBy === 'model' ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  @click="toggleSort('model')"
                >
                  Model
                  <ChevronUpIcon
                    v-if="sortBy === 'model' && sortDir === 'asc'"
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                  <ChevronDownIcon
                    v-else-if="sortBy === 'model' && sortDir === 'desc'"
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                </button>
              </th>
              <th
                v-for="col in [
                  { key: 'turnCount', label: 'Turns' },
                  { key: 'total', label: 'Cost' },
                  { key: 'prompt', label: 'Prompt' },
                  { key: 'completion', label: 'Completion' },
                  { key: 'reasoning', label: 'Reasoning' },
                  { key: 'cached', label: 'Cached' },
                ] as { key: SortColumn, label: string }[]"
                :key="col.key"
                scope="col"
                class="text-right px-3 py-2 font-medium"
              >
                <button
                  type="button"
                  class="inline-flex items-center gap-1 hover:text-fg-strong transition-colors"
                  :class="sortBy === col.key ? 'text-fg-strong' : ''"
                  :aria-sort="sortBy === col.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'"
                  @click="toggleSort(col.key)"
                >
                  {{ col.label }}
                  <ChevronUpIcon
                    v-if="sortBy === col.key && sortDir === 'asc'"
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                  <ChevronDownIcon
                    v-else-if="sortBy === col.key && sortDir === 'desc'"
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                </button>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="m in sortedPerModel"
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
              <td class="px-3 py-2 text-right font-mono text-emerald-700 dark:text-emerald-400">
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
              <td class="px-3 py-2 text-right font-mono text-yellow-700 dark:text-yellow-400">
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
          <div class="font-mono text-emerald-700 dark:text-emerald-400 tabular-nums shrink-0">
            {{ formatCostUsd(r.cost) }}
            <span class="text-fg-muted">· {{ r.turnCount }}t</span>
          </div>
        </div>
      </div>

      <!-- JCLAW-280: combined total — rendered at the very bottom, below
           the per-model table/chart, so it reads as a footer summing the
           Subscription + Per-Token subsections above. Only rendered when
           at least one subsection has activity; the all-free-tier empty
           state above suppresses everything else in that case. -->
      <div
        v-if="hasPaidData || hasSubscriptionSection"
        class="px-4 py-2 flex items-center justify-between border-t border-border bg-muted/40"
      >
        <div class="text-xs text-fg-muted uppercase tracking-wide">
          Combined total
        </div>
        <div class="text-sm font-mono text-emerald-700 dark:text-emerald-400">
          {{ formatCostUsd(combinedTotal) }}
        </div>
      </div>
    </template>
  </div>
</template>
