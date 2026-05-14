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
  type MessageUsage,
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
// Provider-chip filter for the Subscription subsection. When set, the
// subscription per-model table + tfoot Total + Combined Total all narrow
// to that provider's models and prorated bill. Click the same chip again
// to clear. Persisted alongside the other filter dropdowns.
const selectedSubscriptionProvider = ref<string | null>(null)

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
      subscriptionProvider: string | null
    }>
    if (parsed.window && (parsed.window in WINDOW_LABELS)) selectedWindow.value = parsed.window
    if (parsed.agentId !== undefined) selectedAgentId.value = parsed.agentId
    if (parsed.channel !== undefined) selectedChannel.value = parsed.channel
    if (parsed.view === 'chart' || parsed.view === 'table') view.value = parsed.view
    if (parsed.subscriptionProvider !== undefined) selectedSubscriptionProvider.value = parsed.subscriptionProvider
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
      subscriptionProvider: selectedSubscriptionProvider.value,
    }))
  }
  catch { /* ignore */ }
}

watch([selectedWindow, selectedAgentId, selectedChannel, view, selectedSubscriptionProvider], persistSettings)

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

/**
 * Resolve a row's billing provider — the one that actually served the
 * turn, not the conversation's owning provider. Sources from
 * usageJson.modelProvider (snapshotted at emission time per JCLAW-107)
 * because that's what reflects per-turn billing reality when an agent
 * swaps providers mid-conversation. Falls back to the conversation-level
 * r.modelProvider only when the snapshot is missing (very old rows from
 * before JCLAW-107 captured the field).
 */
function rowBillingProvider(r: FleetCostRow): string | undefined {
  try {
    const u = JSON.parse(r.usageJson) as MessageUsage
    if (u.modelProvider) return u.modelProvider
  }
  catch { /* fall through to conversation-level */ }
  return r.modelProvider
}

// Partition raw rows by modality. Rows whose provider doesn't match a
// known SUBSCRIPTION provider fall into the per-token bucket — that covers
// rows from registry-removed providers and any pre-JCLAW-280 rows where
// the modality wasn't yet a concept.
const perTokenRows = computed<FleetCostRow[]>(() =>
  rows.value.filter(r => modalityByProvider.value.get(rowBillingProvider(r) ?? '') !== 'SUBSCRIPTION'),
)
const subscriptionRows = computed<FleetCostRow[]>(() =>
  rows.value.filter(r => modalityByProvider.value.get(rowBillingProvider(r) ?? '') === 'SUBSCRIPTION'),
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

// Display names for the configured-provider tooltip. Mirrors the
// PROVIDER_LABELS map in settings.vue (small enough that duplicating is
// cheaper than threading a shared util through). Unknown providers fall
// back to their raw registry name.
const PROVIDER_DISPLAY_NAMES: Record<string, string> = {
  'ollama-cloud': 'Ollama Cloud',
  'openrouter': 'OpenRouter',
  'openai': 'OpenAI',
  'together': 'TogetherAI',
  'ollama-local': 'Ollama Local',
  'lm-studio': 'LM Studio',
}
// JCLAW-280: per-provider subscription breakdown rows. Each row appears
// in the Subscription tfoot just above the Total row, with the
// pro-rated monthly fee in the Cost cell. The Total fee is the literal
// sum of these rows, so the operator can see exactly which providers
// are contributing how much. Computed lazily off windowDays so the
// pro-rating updates when the operator switches the time window.
const subscriptionProviderBreakdown = computed(() =>
  configuredSubscriptionProviders.value.map(p => ({
    name: p.name,
    displayName: PROVIDER_DISPLAY_NAMES[p.name] ?? p.name,
    proRatedFee: (Number(p.subscriptionMonthlyUsd) || 0) * (windowDays.value / 30),
  })),
)

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
// Clear the subscription-chip filter if the operator removes that
// provider from configuration (e.g., toggles modality on Settings).
// Mirror the channel reset above so the chip can't get stuck pointing
// at a provider that no longer renders.
watchEffect(() => {
  if (selectedSubscriptionProvider.value !== null
    && !configuredSubscriptionProviders.value.some(p => p.name === selectedSubscriptionProvider.value)) {
    selectedSubscriptionProvider.value = null
  }
})

const filter = computed<FleetCostFilter>(() => ({
  agentId: selectedAgentId.value,
  channelType: selectedChannel.value,
}))

const breakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(rows.value, filter.value),
)

// Subscription rows narrowed by the chip filter. When no chip is
// selected this just re-exports subscriptionRows. The unfiltered version
// (subscriptionRows) is still needed for has-usage checks on the chips
// themselves — otherwise selecting one chip would make every other chip
// look like "no usage" and become un-clickable, trapping the operator
// in the current selection.
const filteredSubscriptionRows = computed<FleetCostRow[]>(() => {
  const sel = selectedSubscriptionProvider.value
  if (sel === null) return subscriptionRows.value
  return subscriptionRows.value.filter(r => rowBillingProvider(r) === sel)
})

// JCLAW-280: per-modality breakdowns. The original `breakdown` stays as the
// whole-fleet aggregate (still used by the agent/channel filter availability
// logic and as the source of `hasData`); the partitioned breakdowns drive
// the rendered subsections.
const perTokenBreakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(perTokenRows.value, filter.value),
)
const subscriptionBreakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(filteredSubscriptionRows.value, filter.value),
)
// Unfiltered subscription breakdown — drives the chip-disabled state.
// A chip is clickable only if its provider had at least one turn in the
// window under the active agent/channel filters; the chip's "has usage"
// check has to consult this, not the chip-filtered version above.
const unfilteredSubscriptionBreakdown = computed<FleetCostBreakdown>(() =>
  computeFleetCost(subscriptionRows.value, filter.value),
)
const subscriptionProvidersWithUsage = computed<Set<string>>(() => {
  const set = new Set<string>()
  for (const m of unfilteredSubscriptionBreakdown.value.perModel) {
    if (m.turnCount > 0 && m.modelProvider) set.add(m.modelProvider)
  }
  return set
})

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
    if (!Number.isNaN(t) && t < earliest) earliest = t
  }
  const spanMs = Math.max(0, Date.now() - earliest)
  return Math.max(1, spanMs / (24 * 60 * 60 * 1000))
})

// Pro-rated subscription accrual = sum of monthly fees across every
// configured subscription provider × (window_days / 30). Independent of
// in-window activity — the operator is paying the monthly fee whether
// they used the provider this week or not. When a chip filter is
// active, this narrows to the selected provider's bill only, so the
// Subscription tfoot Total and the Combined Total flow naturally to
// that provider's number (e.g., $100 for Ollama Cloud rather than
// $120 for the full configured stack).
const subscriptionFee = computed(() => {
  const sel = selectedSubscriptionProvider.value
  if (sel !== null) {
    const p = configuredSubscriptionProviders.value.find(p => p.name === sel)
    return p ? (Number(p.subscriptionMonthlyUsd) || 0) * (windowDays.value / 30) : 0
  }
  let sumMonthly = 0
  for (const p of configuredSubscriptionProviders.value) {
    sumMonthly += Number(p.subscriptionMonthlyUsd) || 0
  }
  return sumMonthly * (windowDays.value / 30)
})

/**
 * Toggle the subscription chip filter — click a chip to scope the
 * subscription table to that provider; click the same chip again to
 * clear back to the all-providers view. Routed through a helper so the
 * disabled-chip case (non-selected + no-usage) can short-circuit
 * without trying to set state.
 */
function onSubscriptionChipClick(name: string) {
  if (selectedSubscriptionProvider.value === name) {
    selectedSubscriptionProvider.value = null
    return
  }
  if (!subscriptionProvidersWithUsage.value.has(name)) return
  selectedSubscriptionProvider.value = name
}

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

// JCLAW-280: combined activity stats — sum the per-token and subscription
// breakdowns so the footer can mirror the per-subsection strip layout
// with grand totals across both modalities.
const combinedTurns = computed(() => perTokenBreakdown.value.turnCount + subscriptionBreakdown.value.turnCount)
const combinedPrompt = computed(() => perTokenBreakdown.value.prompt + subscriptionBreakdown.value.prompt)
const combinedCompletion = computed(() => perTokenBreakdown.value.completion + subscriptionBreakdown.value.completion)
const combinedReasoning = computed(() => perTokenBreakdown.value.reasoning + subscriptionBreakdown.value.reasoning)
const combinedCached = computed(() => perTokenBreakdown.value.cached + subscriptionBreakdown.value.cached)

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
 * Format a stat-strip headline amount in plain-currency style — "$120"
 * when whole, "$99.99" or "$0.05" otherwise. Rounds *up* to the next
 * cent so sub-cent per-token contributions don't silently vanish from
 * the headline figure (e.g., $0.0001 renders as "$0.01" rather than
 * "$0.00"). Used for the Per-Token cost stat, the Subscription fee, and
 * the Combined Total — every headline figure is consistent. The per-
 * model table cells keep four-decimal precision via {@link formatCostUsd}
 * because per-row cost contribution still needs sub-cent granularity.
 */
function formatStatCurrency(amount: number): string {
  if (amount === 0) return '$0'
  const ceiled = Math.ceil(amount * 100) / 100
  return ceiled % 1 === 0 ? `$${ceiled.toFixed(0)}` : `$${ceiled.toFixed(2)}`
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

// Per-provider total tokens within the window — denominator for the
// allocation key. Sums prompt + completion + reasoning + cached: cached
// is included because the operator paid for that work through the
// subscription regardless of whether the provider served it from cache,
// so excluding it would over-allocate to non-cached models. A provider
// with zero usage this window has no entry; the lookup in
// `subscriptionPerModelAllocated` defaults to 0 and short-circuits the
// division to avoid NaN.
const subscriptionProviderTokenTotals = computed(() => {
  const totals = new Map<string, number>()
  for (const m of subscriptionPerModel.value) {
    const provider = m.modelProvider
    if (!provider) continue
    const tokens = m.prompt + m.completion + m.reasoning + m.cached
    totals.set(provider, (totals.get(provider) ?? 0) + tokens)
  }
  return totals
})

// Per-model rows with an `allocatedCost` field — provider's pro-rated
// monthly fee × (this model's tokens / this provider's total tokens this
// window). Subscription bills are flat-rate at the provider level, so any
// per-model attribution is a proxy; we expose it as "allocated cost" with
// a footnote on the section so operators understand it's a derived
// share-of-tokens figure, not actual per-call billing. Picking the
// allocation key (total tokens) is the most defensible default because it
// tracks "work the model did for the operator", which is what the flat
// fee buys.
const subscriptionPerModelAllocated = computed<(FleetCostPerModel & { allocatedCost: number })[]>(() => {
  const feeByProvider = new Map(subscriptionProviderBreakdown.value.map(p => [p.name, p.proRatedFee]))
  return subscriptionPerModel.value.map((m) => {
    const provider = m.modelProvider ?? ''
    const providerTokens = subscriptionProviderTokenTotals.value.get(provider) ?? 0
    const providerFee = feeByProvider.get(provider) ?? 0
    const modelTokens = m.prompt + m.completion + m.reasoning + m.cached
    const allocatedCost = providerTokens > 0
      ? providerFee * (modelTokens / providerTokens)
      : 0
    return { ...m, allocatedCost }
  })
})

// Providers with a configured subscription bill but zero usage this
// window. Their fee is real money the operator is paying, so we surface
// it in a footnote rather than silently dropping it — accounting honesty
// beats spreadsheet neatness. The Total row in the table sums only the
// allocated cost, so `unallocatedSubscriptions` is what makes up the gap
// between the table Total and the bill subtotal.
const unallocatedSubscriptions = computed(() => {
  // When a chip filter is active, every other provider's bill is hidden
  // by design, not unallocated — surfacing them in the footnote would
  // misrepresent the view. The footnote re-appears once the filter is
  // cleared.
  if (selectedSubscriptionProvider.value !== null) return []
  const usedProviders = new Set(
    subscriptionPerModel.value.map(m => m.modelProvider).filter(Boolean),
  )
  return subscriptionProviderBreakdown.value
    .filter(p => !usedProviders.has(p.name))
    .filter(p => p.proRatedFee > 0)
})

// Sum of allocations across all subscription model rows. Equals the
// total bill when every configured subscription provider had usage this
// window; less than the bill when one or more are unallocated (see
// `unallocatedSubscriptions`).
const subscriptionAllocatedTotal = computed(() =>
  subscriptionPerModelAllocated.value.reduce((sum, m) => sum + m.allocatedCost, 0),
)

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

// Subscription chart counterpart of chartRows. Always per-model — flat
// monthly bills don't decompose meaningfully by agent (the same agent
// might use multiple subscription providers), so a per-model view is the
// only attribution that holds up. Honors the chip filter via
// subscriptionPerModelAllocated, so clicking Ollama Cloud collapses the
// chart to that provider's models with their share of $100. Keeps
// modelProvider on each row so the swatch helper can color-key the bar.
const subscriptionChartRows = computed(() =>
  subscriptionPerModelAllocated.value
    .filter(m => m.turnCount > 0)
    .map(m => ({
      label: m.modelProvider ? `${m.modelProvider}/${m.modelId}` : m.modelId,
      cost: m.allocatedCost,
      turnCount: m.turnCount,
      modelProvider: m.modelProvider,
    })),
)

const subscriptionChartMaxCost = computed(() => {
  const max = subscriptionChartRows.value.reduce((m, r) => Math.max(m, r.cost), 0)
  return max === 0 ? 1 : max
})

// Palette for the per-provider color swatch on each Subscription chart
// bar. Listed as literal class strings so Tailwind's JIT can find every
// possible value the helper might return; building them by string
// concatenation would silently render as transparent. Kept off-emerald
// (and off-lime/teal) so the swatch can never be mistaken for the
// emerald cost fill on the bar itself — "emerald = cost" stays a global
// convention; the swatch is purely a provider-identity tag.
const PROVIDER_SWATCH_PALETTE = [
  'bg-sky-500',
  'bg-amber-500',
  'bg-fuchsia-500',
  'bg-rose-500',
  'bg-indigo-500',
  'bg-orange-500',
] as const

/**
 * Map a subscription provider name to a stable swatch color class.
 * Indexed by alphabetical position within configuredSubscriptionProviders
 * rather than by name-hash — the user-facing guarantee is that any two
 * distinct providers in *the operator's actual setup* get different
 * colors (which a hash can't guarantee: real-world strings like
 * "ollama-cloud" and "openai" collide on small palettes). Alphabetical
 * sort makes the assignment deterministic regardless of /api/providers
 * response order. Adding a new provider can shift colors of providers
 * alphabetically after it; acceptable for a setup that changes rarely.
 */
const providerSwatchColorByName = computed<Map<string, string>>(() => {
  const map = new Map<string, string>()
  const sorted = [...configuredSubscriptionProviders.value]
    .sort((a, b) => a.name.localeCompare(b.name))
  sorted.forEach((p, i) => {
    map.set(p.name, PROVIDER_SWATCH_PALETTE[i % PROVIDER_SWATCH_PALETTE.length]!)
  })
  return map
})

function providerSwatchColor(provider: string | undefined): string {
  if (!provider) return 'bg-fg-muted'
  return providerSwatchColorByName.value.get(provider) ?? 'bg-fg-muted'
}

// CSV export. Generates per-model breakdown (one row per model with cost
// and token totals) since that's the most actionable view; the operator
// can pivot externally if they need agent or channel cuts. Honors the
// active table sort so the export matches what the operator is looking
// at — useful when they're viewing "sort by reasoning tokens desc" and
// want that ordering preserved in the spreadsheet.
function exportCsv() {
  const header = [
    'modality', 'provider', 'modelId', 'turnCount', 'cost',
    'promptTokens', 'completionTokens', 'reasoningTokens', 'cachedTokens',
  ].join(',')

  const lines: string[] = []

  // Subscription per-model rows: cost cell carries the proportional
  // allocation (provider's pro-rated bill × this model's token share
  // for the provider). A spreadsheet column summing these reproduces
  // the dashboard's Total — minus any unallocated amount, which is
  // exported as its own row below.
  for (const m of subscriptionPerModelAllocated.value) {
    lines.push([
      'subscription',
      csvCell(m.modelProvider ?? ''),
      csvCell(m.modelId),
      m.turnCount.toString(),
      m.allocatedCost.toFixed(6),
      m.prompt.toString(),
      m.completion.toString(),
      m.reasoning.toString(),
      m.cached.toString(),
    ].join(','))
  }

  // Subscription bills that couldn't be allocated this window — the
  // provider was configured (and billed) but had no usage. Exported as
  // a separate row per provider so accounting math reconciles: sum of
  // allocated rows + sum of these = total bill.
  for (const p of unallocatedSubscriptions.value) {
    lines.push([
      'subscription-unallocated',
      csvCell(p.name),
      '',
      '',
      p.proRatedFee.toFixed(6),
      '', '', '', '',
    ].join(','))
  }

  // Per-token per-model rows in current sort order so the export
  // matches what the operator is looking at on screen.
  for (const m of sortedPerModel.value) {
    lines.push([
      'per-token',
      csvCell(m.modelProvider ?? ''),
      csvCell(m.modelId),
      m.turnCount.toString(),
      m.total.toFixed(6),
      m.prompt.toString(),
      m.completion.toString(),
      m.reasoning.toString(),
      m.cached.toString(),
    ].join(','))
  }

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
  a.remove()
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
          v-if="hasPaidData || subscriptionChartRows.length > 0"
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
          :disabled="!hasPaidData && !hasSubscriptionSection"
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
        class="border-b border-border mt-6 mb-6"
      >
        <div class="px-4 pt-3 pb-3">
          <div class="text-xs font-medium text-fg-muted uppercase tracking-wide">
            Subscription
          </div>
          <!-- Per-provider bill breakdown for the active window. Rendered
               as a flex-wrap grid of clickable chips: clicking a chip
               scopes the table below to that provider's models with the
               tfoot Total summing to that provider's bill; clicking the
               same chip again clears the filter. The aggregate of all
               chips already shows up in the table's tfoot Total row, so
               we deliberately omit a "Total" chip — it would duplicate
               information that the table footer already carries. Chips
               for providers with zero usage this window are visually
               muted and not clickable (selecting one would render an
               empty table). -->
          <div
            v-if="subscriptionProviderBreakdown.length > 0"
            class="mt-3 flex flex-wrap gap-2"
          >
            <button
              v-for="p in subscriptionProviderBreakdown"
              :key="p.name"
              type="button"
              :aria-pressed="selectedSubscriptionProvider === p.name"
              :disabled="selectedSubscriptionProvider !== p.name
                && !subscriptionProvidersWithUsage.has(p.name)"
              class="border min-w-[9rem] text-left transition-colors flex items-stretch"
              :class="selectedSubscriptionProvider === p.name
                ? 'border-emerald-600 dark:border-emerald-400 bg-muted/40'
                : subscriptionProvidersWithUsage.has(p.name)
                  ? 'border-border bg-muted/20 hover:bg-muted/30 cursor-pointer'
                  : 'border-border bg-muted/10 opacity-50 cursor-not-allowed'"
              @click="onSubscriptionChipClick(p.name)"
            >
              <!-- Same per-provider swatch as the chart bar below — the
                   chip and the bar share a single color per provider so
                   the operator can match chip ↔ bar at a glance. Sits
                   on the chip's leading edge as a 6px band, stretched
                   to the chip's full height via items-stretch. -->
              <div
                class="w-1.5 shrink-0"
                :class="providerSwatchColor(p.name)"
              />
              <div class="px-3 py-2 flex-1">
                <div class="text-[10px] text-fg-muted uppercase tracking-wide">
                  {{ p.displayName }}
                </div>
                <div
                  class="mt-0.5 font-mono text-sm"
                  :class="selectedSubscriptionProvider === p.name
                    ? 'text-emerald-700 dark:text-emerald-400'
                    : 'text-fg-primary'"
                >
                  {{ formatStatCurrency(p.proRatedFee) }}
                </div>
              </div>
            </button>
          </div>
        </div>

        <!-- Per-model breakdown for subscription activity. Each row's
             Cost is the provider's pro-rated bill × (this model's
             tokens / this provider's total tokens this window) — the
             flat fee allocated proportionally to work done. Fixed sort
             by turn count descending; interactive sort would be overkill
             on what's usually a 1-2 model set per subscription provider.
             Always rendered when a subscription is configured so the
             Total row shows the fee even on weeks with no activity. -->
        <div
          v-if="view === 'table'"
          class="overflow-x-auto border-t border-border"
        >
          <table class="w-full text-xs table-fixed">
            <thead class="text-fg-muted bg-muted/20">
              <tr>
                <!-- Model column gets a fixed 1/4 share so the 6 stat columns
                     auto-distribute the remaining 75% (12.5% each) under
                     table-fixed. Same width on both per-model tables means
                     their stat columns line up vertically. -->
                <th
                  scope="col"
                  class="text-left px-4 py-2 font-medium w-1/4"
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
                <!-- Cost cell carries the per-model allocation =
                     provider's pro-rated bill × (this model's tokens /
                     this provider's total tokens this window). It's a
                     derived share, not actual per-call billing — the
                     footnote below the table calls that out explicitly. -->
                <th
                  scope="col"
                  class="text-right px-3 py-2 font-medium"
                >
                  Cost*
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="m in subscriptionPerModelAllocated"
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
                <td class="px-3 py-2 text-right font-mono text-emerald-700 dark:text-emerald-400">
                  {{ formatStatCurrency(m.allocatedCost) }}
                </td>
              </tr>
            </tbody>
            <!-- Section total. Stat cells are the column sums of the
                 bodies above; the Cost cell is the sum of allocated
                 per-model costs and equals the bill total iff every
                 configured subscription provider had at least some
                 usage this window. When one or more providers had
                 zero usage, this Total falls short of the bill by
                 exactly the unallocated amount — surfaced in the
                 footnote below. -->
            <tfoot>
              <tr class="bg-muted/30 border-t border-border">
                <td class="px-4 py-2 text-xs font-medium text-fg-muted uppercase tracking-wide">
                  Total
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-primary">
                  {{ subscriptionBreakdown.turnCount.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ subscriptionBreakdown.prompt.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ subscriptionBreakdown.completion.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ subscriptionBreakdown.reasoning.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-yellow-700 dark:text-yellow-400">
                  {{ subscriptionBreakdown.cached.toLocaleString() }}
                </td>
                <td
                  class="px-3 py-2 text-right font-mono text-emerald-700 dark:text-emerald-400"
                  :title="`Sum of per-model allocations. Bill total this window: ${formatStatCurrency(subscriptionFee)}`"
                >
                  {{ formatStatCurrency(subscriptionAllocatedTotal) }}
                </td>
              </tr>
            </tfoot>
          </table>
        </div>

        <!-- Subscription chart view: horizontal bars of allocated cost,
             one bar per model that contributed usage this window. Bar
             widths are relative to the largest allocated cost in the
             current view (so a chip filter to one provider re-scales
             the bars to that provider's range). Suppressed when there
             are no usage rows — the empty-state info lives in the
             unallocated footnote below. -->
        <!-- One grid container for all chart rows (not one grid per row)
             so the auto cost column resolves to a single width across
             rows and every bar's left edge sits at the same x. The
             minmax(0,_1fr) / minmax(0,_3fr) widths override the default
             min-width:auto behavior — without them, the 1fr label
             column would expand to fit the longest label even though
             `truncate` is set, leaving bars at inconsistent positions. -->
        <div
          v-else-if="subscriptionChartRows.length > 0"
          class="px-4 py-3 border-t border-border grid items-center gap-x-3 gap-y-1.5 text-xs grid-cols-[minmax(0,1fr)_minmax(0,3fr)_auto]"
        >
          <template
            v-for="(r, i) in subscriptionChartRows"
            :key="i"
          >
            <div
              class="font-mono text-fg-primary truncate"
              :title="r.label"
            >
              {{ r.label }}
            </div>
            <div class="relative h-5 bg-muted/30 border border-border overflow-hidden">
              <!-- Emerald fill in normal flow so it starts at the bar's
                   left edge (x=0) and its right edge sits at the
                   proportional position — same logical alignment as the
                   per-token chart bars above, which carry no swatch. -->
              <div
                class="h-full bg-emerald-500/30"
                :style="{ width: ((r.cost / subscriptionChartMaxCost) * 100).toFixed(2) + '%' }"
              />
              <!-- Per-provider color swatch overlays the leftmost 6px on
                   top of the emerald — the fill's logical left edge
                   stays at x=0, the swatch is a colored badge that
                   identifies the provider without displacing the bar.
                   Keeps "emerald = cost" intact across the dashboard. -->
              <div
                class="absolute inset-y-0 left-0 w-1.5"
                :class="providerSwatchColor(r.modelProvider)"
                :title="r.modelProvider ?? ''"
              />
            </div>
            <div class="font-mono text-emerald-700 dark:text-emerald-400 tabular-nums shrink-0">
              {{ formatCostUsd(r.cost) }}
              <span class="text-fg-muted">· {{ r.turnCount }}t</span>
            </div>
          </template>
        </div>

        <!-- Footnote: clarifies the allocation, and flags any
             subscription bill that couldn't be allocated because the
             provider had zero usage this window. The Total in the table
             above (or the bars in the chart) equals the bill iff this
             footnote shows no unallocated entries; otherwise the gap is
             exactly the sum of the listed amounts. Rendered in both
             views because the methodology disclaimer applies regardless
             of whether the operator is looking at the table or the
             chart. -->
        <div class="px-4 py-2 text-[11px] text-fg-muted border-t border-border">
          *Subscription cost allocated across models by total tokens (prompt + completion + reasoning + cached).
          <div
            v-if="unallocatedSubscriptions.length > 0"
            class="mt-1"
          >
            <template
              v-for="(p, idx) in unallocatedSubscriptions"
              :key="p.name"
            >
              <span v-if="idx > 0">; </span><span class="font-mono text-fg-primary">{{ formatStatCurrency(p.proRatedFee) }}</span>
              unallocated — {{ p.displayName }} has no usage this window
            </template>
          </div>
        </div>
      </div>

      <!-- JCLAW-280: per-token subsection. Header + (table OR chart based
           on the view toggle). Rendered only when paid per-token activity
           exists in the window. Subscription-provider turns are excluded
           by sourcing from perTokenBreakdown. -->
      <div
        v-if="hasPaidData"
        class="border-b border-border"
      >
        <div class="px-4 pt-3 pb-3 text-xs font-medium text-fg-muted uppercase tracking-wide">
          Per-token
        </div>

        <!--
          Per-model breakdown table. Click any header cell to sort by that
          column; click again to flip direction. Active column shows a
          chevron in its sort direction. Default: Cost descending. Free-tier
          models (total === 0) are filtered out to keep the table focused
          on cost contributors. tfoot Total row carries the column sums and
          the cost grand total for this modality.
        -->
        <div
          v-if="view === 'table'"
          class="overflow-x-auto border-t border-border"
        >
          <table class="w-full text-xs table-fixed">
            <thead class="text-fg-muted bg-muted/20">
              <tr>
                <th
                  scope="col"
                  class="text-left px-4 py-2 font-medium w-1/4"
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
                    { key: 'prompt', label: 'Prompt' },
                    { key: 'completion', label: 'Completion' },
                    { key: 'reasoning', label: 'Reasoning' },
                    { key: 'cached', label: 'Cached' },
                    { key: 'total', label: 'Cost' },
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
                <td class="px-3 py-2 text-right font-mono text-emerald-700 dark:text-emerald-400">
                  {{ formatCostUsd(m.total) }}
                </td>
              </tr>
            </tbody>
            <!-- Section total. Column sums of the body; Cost cell carries
               the per-token grand total via formatStatCurrency for the
               same two-decimal convention used in the Combined Total
               below. -->
            <tfoot>
              <tr class="bg-muted/30 border-t border-border">
                <td class="px-4 py-2 text-xs font-medium text-fg-muted uppercase tracking-wide">
                  Total
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-primary">
                  {{ perTokenBreakdown.turnCount.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ perTokenBreakdown.prompt.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ perTokenBreakdown.completion.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-fg-muted">
                  {{ perTokenBreakdown.reasoning.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-yellow-700 dark:text-yellow-400">
                  {{ perTokenBreakdown.cached.toLocaleString() }}
                </td>
                <td class="px-3 py-2 text-right font-mono text-emerald-700 dark:text-emerald-400">
                  {{ formatStatCurrency(perTokenBreakdown.total) }}
                </td>
              </tr>
            </tfoot>
          </table>
        </div>

        <!--
          Chart view: horizontal bars sorted by cost. Picks per-model when
          the breakdown has more model entries than agent entries (typical
          fleet view); switches to per-agent when an agent filter narrows
          the scope. Inline SVG with relative widths.
        -->
        <!-- Unified grid for all rows so the auto cost column resolves
             to a single width and bars left-align across rows. Same
             pattern as the Subscription chart above. -->
        <div
          v-else
          class="px-4 py-3 border-t border-border grid items-center gap-x-3 gap-y-1.5 text-xs grid-cols-[minmax(0,1fr)_minmax(0,3fr)_auto]"
        >
          <template
            v-for="(r, i) in chartRows"
            :key="i"
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
          </template>
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

      <!-- JCLAW-280: combined total — rendered at the very bottom as a
           single table row sharing the per-model tables' column widths
           (table-fixed + w-1/4 Model column via the explicit colgroup
           below). The "Combined total" label lives in the first cell
           rather than a separate header strip above, so the grand total
           reads as one row that lines up vertically with the section
           Total rows above. Visually demarcated as a *zone* (tinted bg
           + pt-6 breathing room) rather than a bordered row — the wrapper's
           bg flood-fills the gap above the row plus the row itself, so the
           operator reads "everything below the per-token table is the
           combined-total summary" without a hard line stitched between
           the gap and the row. -->
      <div
        v-if="hasPaidData || hasSubscriptionSection"
        class="overflow-x-auto pt-6 bg-muted/30"
      >
        <table class="w-full text-xs table-fixed">
          <!-- Explicit colgroup so table-fixed has a column-width source
               independent of any row. The sr-only thead below applies
               position:absolute (Tailwind's sr-only is width:1px height:1px
               position:absolute), which yanks its cells out of the table
               box tree. Without colgroup, table-fixed loses the w-1/4
               Model-column cue from the absent thead and distributes 7
               equal columns ≈ 14.3% each, leaving the Model column too
               narrow and shifting every stat column left of the per-token
               table's columns above. Colgroup widths sit above row-based
               inference in the table-fixed algorithm, so they apply
               regardless of thead positioning — and they mirror the
               implicit 25% / 12.5%×6 widths the per-modality tables
               above get from their visible thead row. -->
          <colgroup>
            <col class="w-1/4">
            <col>
            <col>
            <col>
            <col>
            <col>
            <col>
          </colgroup>
          <!-- Visually-hidden header so screen readers can announce each
               cell's column. The per-modality tables above already render
               their column headers visibly, and this footer mirrors their
               column layout — no need for a second visible header row,
               but WCAG 2 A requires the structural markup regardless. -->
          <thead class="sr-only">
            <tr>
              <th
                scope="col"
                class="w-1/4"
              >
                Row label
              </th>
              <th scope="col">
                Turns
              </th>
              <th scope="col">
                Prompt
              </th>
              <th scope="col">
                Completion
              </th>
              <th scope="col">
                Reasoning
              </th>
              <th scope="col">
                Cached
              </th>
              <th scope="col">
                Cost
              </th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th
                scope="row"
                class="px-4 py-2 text-left text-xs font-medium text-fg-muted uppercase tracking-wide w-1/4"
              >
                Combined total
              </th>
              <td class="px-3 py-2 text-right font-mono text-fg-primary">
                {{ combinedTurns.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ combinedPrompt.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ combinedCompletion.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-fg-muted">
                {{ combinedReasoning.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-yellow-700 dark:text-yellow-400">
                {{ combinedCached.toLocaleString() }}
              </td>
              <td class="px-3 py-2 text-right font-mono text-emerald-700 dark:text-emerald-400">
                {{ formatStatCurrency(combinedTotal) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>
