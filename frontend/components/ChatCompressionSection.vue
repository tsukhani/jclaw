<script setup lang="ts">
/**
 * JCLAW-467, JF-139: Chat Compression dashboard section. GET /api/metrics/compression answers with
 * the totals for the window, agent and channel selected, so every change asks again and the body
 * shows its loading state until the answer lands; ratios, ordering and alert thresholds are worked
 * out here. Table/chart view toggle for the by-type, per-algorithm and per-tool fetch-back-rate
 * breakdowns, and a reset (trash) that clears the recorded metrics. The first-call cache tile
 * reads the turn rows of GET /api/metrics/cost under the same filters.
 */
import { ChartBarIcon, ExclamationTriangleIcon, TableCellsIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { Agent, CompressionTotals } from '~/types/api'
import { cacheShare, computeFleetCost, formatShare, type FleetCostRow } from '~/utils/usage-cost'

const props = defineProps<{ agents: Agent[] | null | undefined }>()

type WindowKey = '7d' | '30d' | 'all'
type View = 'table' | 'chart'

const WINDOWS: { key: WindowKey, label: string }[] = [
  { key: '7d', label: '7d' },
  { key: '30d', label: '30d' },
  { key: 'all', label: 'All' },
]

const NO_TOTALS: CompressionTotals = {
  since: '', agents: [], channels: [], compressions: 0, tokensBefore: 0, tokensAfter: 0, inflationGuards: 0,
  ccrRetrievals: 0, ccrHits: 0, byType: [], byAlgorithm: [], byTool: [],
}

const selectedWindow = ref<WindowKey>('30d')
const selectedAgentId = ref<string | null>(null)
const selectedChannel = ref<string | null>(null)
const view = ref<View>('chart')
const resetting = ref(false)

const since = computed(() => {
  if (selectedWindow.value === 'all') return new Date(0).toISOString()
  const days = selectedWindow.value === '7d' ? 7 : 30
  return new Date(Date.now() - days * 86_400_000).toISOString()
})

const { data, refresh, status, error } = useFetch<CompressionTotals>('/api/metrics/compression', {
  query: {
    since,
    agentId: computed(() => selectedAgentId.value ?? undefined),
    channel: computed(() => selectedChannel.value ?? undefined),
  },
  default: () => NO_TOTALS,
})
const { data: costData, refresh: refreshCost, status: costStatus } = useFetch<{ since: string, rows: FleetCostRow[] }>('/api/metrics/cost', {
  query: { since },
  default: () => ({ since: '', rows: [] }),
})
defineExpose({ refresh: () => Promise.all([refresh(), refreshCost()]) })

// The tiles wait for both reads: either alone reads the other's empty default as zeros.
const settled = (s: string) => s === 'success' || s === 'error'
const hasLoadedOnce = ref(false)
watchEffect(() => {
  if (settled(status.value) && settled(costStatus.value)) hasLoadedOnce.value = true
})
// A refetch keeps the previous answer in `data`, which no longer matches the selection.
const reloading = computed(() => status.value === 'pending' || costStatus.value === 'pending')

// Reserve the height this panel had last visit so the tiles landing don't move
// the page. 349px is the measured natural body height on a populated install.
const { reservedHeight, el: bodyEl } = useStableHeight('compression', 349, hasLoadedOnce)
// And hold the current height while a filter change reloads, so the loading line doesn't collapse the panel.
const reloadHeight = ref<string>()
watch(reloading, (now) => {
  const h = bodyEl.value?.getBoundingClientRect().height
  reloadHeight.value = now && h ? `${Math.round(h)}px` : undefined
})
const bodyHeight = computed(() => reservedHeight.value ?? reloadHeight.value)

const totals = computed<CompressionTotals>(() => data.value ?? NO_TOTALS)
const totalsError = computed(() => (error.value ? apiErrorDetails(error.value) : null))
const costRows = computed<FleetCostRow[]>(() => costData.value?.rows ?? [])

const agentNameById = computed(() => {
  const m = new Map<string, string>()
  for (const a of props.agents ?? []) m.set(String(a.id), a.name)
  return m
})
// Built from both reads: an agent that never compresses still has a first-call cache share.
const availableAgents = computed(() => {
  const ids = new Set<string>(totals.value.agents)
  for (const r of costRows.value) ids.add(String(r.agentId))
  return [...ids]
    .map(id => ({ id, name: agentNameById.value.get(id) ?? `agent #${id}` }))
    .sort((a, b) => a.name.localeCompare(b.name))
})
const availableChannels = computed(() => {
  const s = new Set<string>(totals.value.channels)
  for (const r of costRows.value) s.add(r.channelType)
  return [...s].sort()
})

// Drop a filter selection that no longer exists after a window reload. A failed read leaves `data`
// at its empty default, which lists nothing, so it says nothing about what the window holds.
watch(availableAgents, (list) => {
  if (status.value === 'error') return
  if (selectedAgentId.value && !list.some(a => a.id === selectedAgentId.value)) selectedAgentId.value = null
})
watch(availableChannels, (list) => {
  if (status.value === 'error') return
  if (selectedChannel.value && !list.includes(selectedChannel.value)) selectedChannel.value = null
})

const agg = computed(() => {
  const t = totals.value
  const tokensSaved = t.tokensBefore - t.tokensAfter
  const reduction = t.tokensBefore ? tokensSaved / t.tokensBefore : 0

  const byType = t.byType
    .map(e => ({
      type: e.contentType ?? '—',
      saved: e.tokensBefore - e.tokensAfter,
      ratio: e.tokensBefore ? e.tokensAfter / e.tokensBefore : 0,
    }))
    .sort((a, b) => b.saved - a.saved)
  const byAlgo = t.byAlgorithm
    .map(e => ({ algorithm: e.algorithm ?? '—', count: e.count, saved: e.tokensBefore - e.tokensAfter }))
    .sort((a, b) => b.count - a.count)
  const byTool = t.byTool
    .map(e => ({ ...e, rate: e.fetched / e.compressed }))
    .sort((a, b) => b.rate - a.rate || b.compressed - a.compressed || a.tool.localeCompare(b.tool))

  const guards = t.inflationGuards
  const ccrTotal = t.ccrRetrievals
  const ccrHits = t.ccrHits
  const ccrHitRate = ccrTotal ? ccrHits / ccrTotal : 0

  const alerts: string[] = []
  // A very low kept-ratio is the *designed* outcome for json-smartcrush /
  // code-structural / text-statistical, so on its own it doesn't mean data loss:
  // the elided originals stay durably stored and reachable via ccr_retrieve. It
  // only signals risk when the model actually tries and fails to recover them —
  // so gate the per-type warning on unhealthy CCR retrieval. A low ratio with
  // healthy retrieval (or no retrievals at all) is a well-tuned pipeline.
  const retrievalUnhealthy = ccrTotal > 0 && ccrHitRate < 0.9
  for (const e of byType) {
    if (e.ratio > 0 && e.ratio < 0.1 && retrievalUnhealthy) {
      alerts.push(`${e.type} compression ratio is very low (${Math.round(e.ratio * 100)}% kept) — verify it isn't dropping needed data`)
    }
  }
  const attempts = t.compressions + guards
  if (attempts > 0 && guards / attempts > 0.05) {
    alerts.push(`Inflation-guard rate is ${Math.round((100 * guards) / attempts)}% — the compressor is frequently inflating`)
  }
  if (ccrTotal > 0 && ccrHitRate < 0.5) {
    alerts.push(`CCR cache hit rate is ${Math.round(ccrHitRate * 100)}% — the model may not be retrieving when it should`)
  }

  return {
    tokensBefore: t.tokensBefore, tokensAfter: t.tokensAfter, tokensSaved, reduction, events: t.compressions,
    byType, byAlgo, byTool, guards, ccrTotal, ccrHits, ccrHitRate, alerts,
    hasData: t.compressions > 0 || ccrTotal > 0 || guards > 0,
  }
})

// JF-151: only a turn's first call can reuse what the turn before it cached, so its share is the headline.
const cache = computed(() => {
  const b = computeFleetCost(costRows.value, {
    agentId: selectedAgentId.value === null ? null : Number(selectedAgentId.value),
    channelType: selectedChannel.value,
  })
  return {
    turns: b.turnCount,
    firstCall: cacheShare(b.firstCached, b.firstPrompt),
    allCalls: cacheShare(b.cached, b.prompt),
  }
})

const maxTypeSaved = computed(() => Math.max(1, ...agg.value.byType.map(t => t.saved)))
const maxAlgoCount = computed(() => Math.max(1, ...agg.value.byAlgo.map(a => a.count)))
const windowLabel = computed(() => WINDOWS.find(w => w.key === selectedWindow.value)?.label ?? '')

const { saveError: resetError, attempt } = useSaveAttempt()

async function resetMetrics() {
  if (resetting.value) return
  resetting.value = true
  if (await attempt(() => $fetch('/api/metrics/compression', { method: 'DELETE' }))) await refresh()
  resetting.value = false
}

function pct(n: number) {
  return Math.round(n * 100)
}
function fmt(n: number) {
  return n.toLocaleString()
}
</script>

<template>
  <div class="bg-surface-elevated border border-border mb-8">
    <div class="px-4 py-3 border-b border-border grid grid-cols-[auto_1fr_auto] max-sm:flex max-sm:flex-wrap items-center gap-3">
      <!-- Left: title + view toggle -->
      <div class="flex items-center gap-3 min-w-0">
        <h2 class="text-sm font-medium text-fg-primary shrink-0">
          Chat Compression
        </h2>
        <div
          class="inline-flex items-center border border-border overflow-hidden"
          role="tablist"
          aria-label="Chat compression view"
        >
          <button
            type="button"
            role="tab"
            :aria-selected="view === 'table'"
            title="Table view"
            class="p-1.5"
            :class="view === 'table' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
            @click="view = 'table'"
          >
            <TableCellsIcon class="w-4 h-4" />
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="view === 'chart'"
            title="Chart view"
            class="p-1.5"
            :class="view === 'chart' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
            @click="view = 'chart'"
          >
            <ChartBarIcon class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- Center: window + agent + channel filters -->
      <div class="flex items-center justify-center gap-3 flex-wrap">
        <div class="inline-flex items-center border border-border overflow-hidden">
          <button
            v-for="w in WINDOWS"
            :key="w.key"
            type="button"
            class="px-2.5 py-1 text-xs"
            :class="selectedWindow === w.key ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
            @click="selectedWindow = w.key"
          >
            {{ w.label }}
          </button>
        </div>
        <select
          v-model="selectedAgentId"
          aria-label="Filter by agent"
          class="px-2 py-1 text-xs bg-muted border border-input text-fg-strong"
        >
          <option :value="null">
            All agents
          </option>
          <option
            v-for="a in availableAgents"
            :key="a.id"
            :value="a.id"
          >
            {{ a.name }}
          </option>
        </select>
        <select
          v-model="selectedChannel"
          aria-label="Filter by channel"
          class="px-2 py-1 text-xs bg-muted border border-input text-fg-strong"
        >
          <option :value="null">
            All channels
          </option>
          <option
            v-for="c in availableChannels"
            :key="c"
            :value="c"
          >
            {{ c }}
          </option>
        </select>
      </div>

      <!-- Right: reset -->
      <div class="flex items-center shrink-0">
        <button
          type="button"
          title="Reset compression metrics"
          class="p-1.5 text-fg-muted hover:text-red-600 dark:hover:text-red-400 disabled:opacity-40"
          :disabled="resetting"
          @click="resetMetrics"
        >
          <TrashIcon class="w-4 h-4" />
        </button>
      </div>
    </div>
    <ApiErrorAlert
      :error="resetError"
      class="px-4 py-2.5"
    />
    <ApiErrorAlert
      :error="totalsError"
      class="px-4 py-2.5"
    />

    <!-- Body reserves its last-known height so the tiles landing don't move the
         page. See useStableHeight. -->
    <div
      ref="bodyEl"
      :style="{ minHeight: bodyHeight }"
      :aria-busy="reloading"
    >
      <div
        v-if="!hasLoadedOnce || reloading"
        class="px-4 py-4 text-xs text-fg-muted"
        role="status"
        aria-live="polite"
      >
        Loading compression data…
      </div>
      <div
        v-else-if="!agg.hasData && !cache.turns"
        class="px-4 py-4 text-xs text-fg-muted"
      >
        No compression activity in this window.
      </div>
      <div
        v-else
        class="p-4 space-y-5"
      >
        <!-- KPI tiles: every scalar metric in one scannable row. -->
        <div class="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-5 gap-3">
          <div class="bg-surface border border-border p-3">
            <div class="text-[11px] uppercase tracking-wide text-fg-muted">
              Tokens saved
            </div>
            <div class="mt-1 text-2xl font-semibold leading-none tabular-nums text-emerald-700 dark:text-emerald-400">
              {{ fmt(agg.tokensSaved) }}
            </div>
            <div class="mt-1.5 text-xs text-fg-muted">
              over {{ windowLabel }}
            </div>
          </div>
          <div class="bg-surface border border-border p-3">
            <div class="text-[11px] uppercase tracking-wide text-fg-muted">
              Reduction
            </div>
            <div class="mt-1 text-2xl font-semibold leading-none tabular-nums text-fg-strong">
              {{ pct(agg.reduction) }}%
            </div>
            <div class="mt-1.5 text-xs text-fg-muted tabular-nums">
              {{ fmt(agg.tokensBefore) }} → {{ fmt(agg.tokensAfter) }}
            </div>
          </div>
          <div class="bg-surface border border-border p-3">
            <div class="text-[11px] uppercase tracking-wide text-fg-muted">
              CCR hit rate
            </div>
            <div class="mt-1 text-2xl font-semibold leading-none tabular-nums text-fg-strong">
              {{ agg.ccrTotal ? pct(agg.ccrHitRate) + '%' : '—' }}
            </div>
            <div class="mt-1.5 text-xs text-fg-muted tabular-nums">
              {{ agg.ccrTotal ? agg.ccrHits + ' / ' + agg.ccrTotal + ' hits' : 'no retrievals' }}
            </div>
          </div>
          <div class="bg-surface border border-border p-3">
            <div class="text-[11px] uppercase tracking-wide text-fg-muted">
              Inflation guards
            </div>
            <div class="mt-1 text-2xl font-semibold leading-none tabular-nums text-fg-strong">
              {{ agg.guards }}
            </div>
            <div class="mt-1.5 text-xs text-fg-muted tabular-nums">
              {{ agg.events }} compressions
            </div>
          </div>
          <div class="bg-surface border border-border p-3">
            <div class="text-[11px] uppercase tracking-wide text-fg-muted">
              First-call cache
            </div>
            <div
              class="mt-1 text-2xl font-semibold leading-none tabular-nums text-fg-strong"
              data-testid="cache-share-first-call"
            >
              {{ formatShare(cache.firstCall) }}
            </div>
            <div
              class="mt-1.5 text-xs text-fg-muted tabular-nums"
              data-testid="cache-share-all"
            >
              {{ cache.turns ? formatShare(cache.allCalls) + ' over all calls' : 'no turns' }}
            </div>
          </div>
        </div>

        <!-- Saved by content type -->
        <div v-if="agg.byType.length">
          <div class="text-[11px] uppercase tracking-wide text-fg-muted mb-2">
            Saved by content type
          </div>
          <div
            v-if="view === 'chart'"
            class="space-y-2.5"
          >
            <div
              v-for="t in agg.byType"
              :key="t.type"
              class="flex items-center gap-3"
            >
              <span class="text-xs font-medium text-fg-strong w-12 shrink-0">{{ t.type }}</span>
              <div class="flex-1 h-2 bg-surface rounded-full overflow-hidden">
                <div
                  class="h-full bg-emerald-500 rounded-full transition-[width] duration-500"
                  :style="{ width: (t.saved / maxTypeSaved * 100) + '%' }"
                />
              </div>
              <span class="text-xs text-fg-muted tabular-nums w-32 text-right shrink-0">
                {{ fmt(t.saved) }}
                <span class="text-emerald-700 dark:text-emerald-400 ml-1">↓{{ 100 - pct(t.ratio) }}%</span>
              </span>
            </div>
          </div>
          <div
            v-else
            class="overflow-x-auto"
          >
            <table class="w-full text-xs">
              <thead>
                <tr class="text-fg-muted text-left">
                  <th class="font-normal py-1">
                    Type
                  </th>
                  <th class="font-normal py-1 text-right">
                    Saved
                  </th>
                  <th class="font-normal py-1 text-right">
                    Reduction
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="t in agg.byType"
                  :key="t.type"
                  class="border-t border-border"
                >
                  <td class="py-1 text-fg-strong">
                    {{ t.type }}
                  </td>
                  <td class="py-1 text-right text-fg-strong tabular-nums">
                    {{ fmt(t.saved) }}
                  </td>
                  <td class="py-1 text-right text-fg-muted tabular-nums">
                    {{ 100 - pct(t.ratio) }}%
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Algorithm usage -->
        <div v-if="agg.byAlgo.length">
          <div class="text-[11px] uppercase tracking-wide text-fg-muted mb-2">
            Algorithm usage
          </div>
          <div
            v-if="view === 'chart'"
            class="space-y-2.5"
          >
            <div
              v-for="a in agg.byAlgo"
              :key="a.algorithm"
              class="flex items-center gap-3"
            >
              <span class="text-xs font-medium text-fg-strong w-32 shrink-0 truncate">{{ a.algorithm }}</span>
              <div class="flex-1 h-2 bg-surface rounded-full overflow-hidden">
                <div
                  class="h-full bg-sky-500 rounded-full transition-[width] duration-500"
                  :style="{ width: (a.count / maxAlgoCount * 100) + '%' }"
                />
              </div>
              <span class="text-xs text-fg-muted tabular-nums w-12 text-right shrink-0">{{ a.count }}</span>
            </div>
          </div>
          <div
            v-else
            class="overflow-x-auto"
          >
            <table class="w-full text-xs">
              <thead>
                <tr class="text-fg-muted text-left">
                  <th class="font-normal py-1">
                    Algorithm
                  </th>
                  <th class="font-normal py-1 text-right">
                    Events
                  </th>
                  <th class="font-normal py-1 text-right">
                    Saved
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="a in agg.byAlgo"
                  :key="a.algorithm"
                  class="border-t border-border"
                >
                  <td class="py-1 text-fg-strong">
                    {{ a.algorithm }}
                  </td>
                  <td class="py-1 text-right text-fg-strong tabular-nums">
                    {{ a.count }}
                  </td>
                  <td class="py-1 text-right text-fg-muted tabular-nums">
                    {{ fmt(a.saved) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Each tool's compressed results, and the share ccr_retrieve fetched back whole -->
        <div
          v-if="agg.byTool.length"
          data-testid="fetch-backs-by-tool"
        >
          <div class="text-[11px] uppercase tracking-wide text-fg-muted mb-2">
            Fetch-back rate by tool
          </div>
          <div
            v-if="view === 'chart'"
            class="space-y-2.5"
          >
            <div
              v-for="t in agg.byTool"
              :key="t.tool"
              class="flex items-center gap-3"
            >
              <span class="text-xs font-medium text-fg-strong w-32 shrink-0 truncate">{{ t.tool }}</span>
              <div class="flex-1 h-2 bg-surface rounded-full overflow-hidden">
                <div
                  class="h-full bg-violet-500 rounded-full transition-[width] duration-500"
                  :style="{ width: (t.rate * 100) + '%' }"
                />
              </div>
              <span class="text-xs text-fg-muted tabular-nums w-32 text-right shrink-0">{{ t.fetched }} of {{ t.compressed }} · {{ pct(t.rate) }}%</span>
            </div>
          </div>
          <div
            v-else
            class="overflow-x-auto"
          >
            <table class="w-full text-xs">
              <thead>
                <tr class="text-fg-muted text-left">
                  <th class="font-normal py-1">
                    Tool
                  </th>
                  <th class="font-normal py-1 text-right">
                    Compressed
                  </th>
                  <th class="font-normal py-1 text-right">
                    Fetched back
                  </th>
                  <th class="font-normal py-1 text-right">
                    Rate
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="t in agg.byTool"
                  :key="t.tool"
                  class="border-t border-border"
                >
                  <td class="py-1 text-fg-strong">
                    {{ t.tool }}
                  </td>
                  <td class="py-1 text-right text-fg-strong tabular-nums">
                    {{ t.compressed }}
                  </td>
                  <td class="py-1 text-right text-fg-strong tabular-nums">
                    {{ t.fetched }}
                  </td>
                  <td class="py-1 text-right text-fg-muted tabular-nums">
                    {{ pct(t.rate) }}%
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!--
          Advisory notices, kept subdued and at the foot so they never compete
          with the metrics above: borderless, muted-gray text with only a small
          amber icon as the warning cue.
        -->
        <div
          v-if="agg.alerts.length"
          class="pt-3 border-t border-border space-y-1.5"
        >
          <p
            v-for="(a, i) in agg.alerts"
            :key="i"
            class="flex items-start gap-1.5 text-xs text-fg-muted"
          >
            <ExclamationTriangleIcon
              class="w-3.5 h-3.5 shrink-0 mt-px text-amber-700/80 dark:text-amber-400/80"
              aria-hidden="true"
            />
            <span>{{ a }}</span>
          </p>
        </div>
      </div>
    </div>
  </div>
</template>
