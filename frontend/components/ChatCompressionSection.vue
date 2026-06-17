<script setup lang="ts">
/**
 * JCLAW-467: Chat Compression dashboard section. Mirrors Chat Cost — a single
 * raw-rows fetch per time window from GET /api/metrics/compression, then all
 * aggregation + agent/channel filtering happens client-side so filter switches
 * are instant. Table/chart view toggle for the by-type and per-algorithm
 * breakdowns, and a reset (trash) that clears the recorded metrics.
 */
import { ChartBarIcon, TableCellsIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'

const props = defineProps<{ agents: Agent[] | null | undefined }>()

interface Row {
  timestamp: string
  agentId: string | null
  channel: string | null
  contentType: string | null
  algorithm: string | null
  tokensBefore: number
  tokensAfter: number
  kind: string
  ccrHit: boolean | null
}
interface CompressionResponse { since: string, rows: Row[] }
type WindowKey = '7d' | '30d' | 'all'
type View = 'table' | 'chart'

const WINDOWS: { key: WindowKey, label: string }[] = [
  { key: '7d', label: '7d' },
  { key: '30d', label: '30d' },
  { key: 'all', label: 'All' },
]

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

const { data, refresh } = useFetch<CompressionResponse>('/api/metrics/compression', {
  query: { since },
  default: () => ({ since: '', rows: [] }),
})
defineExpose({ refresh })

const rows = computed<Row[]>(() => data.value?.rows ?? [])

const agentNameById = computed(() => {
  const m = new Map<string, string>()
  for (const a of props.agents ?? []) m.set(String(a.id), a.name)
  return m
})
const availableAgents = computed(() => {
  const ids = new Set<string>()
  for (const r of rows.value) if (r.agentId) ids.add(r.agentId)
  return [...ids]
    .map(id => ({ id, name: agentNameById.value.get(id) ?? `agent #${id}` }))
    .sort((a, b) => a.name.localeCompare(b.name))
})
const availableChannels = computed(() => {
  const s = new Set<string>()
  for (const r of rows.value) if (r.channel) s.add(r.channel)
  return [...s].sort()
})

// Drop a filter selection that no longer exists after a window reload.
watch(availableAgents, (list) => {
  if (selectedAgentId.value && !list.some(a => a.id === selectedAgentId.value)) selectedAgentId.value = null
})
watch(availableChannels, (list) => {
  if (selectedChannel.value && !list.includes(selectedChannel.value)) selectedChannel.value = null
})

const filtered = computed(() => rows.value.filter(r =>
  (selectedAgentId.value === null || r.agentId === selectedAgentId.value)
  && (selectedChannel.value === null || r.channel === selectedChannel.value)))

const agg = computed(() => {
  const comp = filtered.value.filter(r => r.kind === 'COMPRESSION')
  const tokensSaved = comp.reduce((s, r) => s + (r.tokensBefore - r.tokensAfter), 0)

  const typeMap = new Map<string, { before: number, after: number }>()
  for (const r of comp) {
    const t = r.contentType ?? '—'
    const e = typeMap.get(t) ?? { before: 0, after: 0 }
    e.before += r.tokensBefore
    e.after += r.tokensAfter
    typeMap.set(t, e)
  }
  const byType = [...typeMap.entries()]
    .map(([type, v]) => ({ type, saved: v.before - v.after, ratio: v.before ? v.after / v.before : 0 }))
    .sort((a, b) => b.saved - a.saved)

  const algoMap = new Map<string, { count: number, saved: number }>()
  for (const r of comp) {
    const a = r.algorithm ?? '—'
    const e = algoMap.get(a) ?? { count: 0, saved: 0 }
    e.count += 1
    e.saved += r.tokensBefore - r.tokensAfter
    algoMap.set(a, e)
  }
  const byAlgo = [...algoMap.entries()]
    .map(([algorithm, v]) => ({ algorithm, count: v.count, saved: v.saved }))
    .sort((a, b) => b.count - a.count)

  const guards = filtered.value.filter(r => r.kind === 'INFLATION_GUARD').length

  // CCR retrievals are recorded agent-less, so the hit rate is global (unfiltered).
  const ccrRows = rows.value.filter(r => r.kind === 'CCR_RETRIEVAL')
  const ccrTotal = ccrRows.length
  const ccrHits = ccrRows.filter(r => r.ccrHit).length
  const ccrHitRate = ccrTotal ? ccrHits / ccrTotal : 0

  const alerts: string[] = []
  for (const t of byType) {
    if (t.ratio > 0 && t.ratio < 0.1) {
      alerts.push(`${t.type} compression ratio is very low (${Math.round(t.ratio * 100)}% kept) — verify it isn't dropping needed data`)
    }
  }
  const attempts = comp.length + guards
  if (attempts > 0 && guards / attempts > 0.05) {
    alerts.push(`Inflation-guard rate is ${Math.round((100 * guards) / attempts)}% — the compressor is frequently inflating`)
  }
  if (ccrTotal > 0 && ccrHitRate < 0.5) {
    alerts.push(`CCR cache hit rate is ${Math.round(ccrHitRate * 100)}% — the model may not be retrieving when it should`)
  }

  return {
    tokensSaved, byType, byAlgo, guards, ccrTotal, ccrHits, ccrHitRate, alerts,
    hasData: comp.length > 0 || ccrTotal > 0 || guards > 0,
  }
})

const maxTypeSaved = computed(() => Math.max(1, ...agg.value.byType.map(t => t.saved)))
const maxAlgoCount = computed(() => Math.max(1, ...agg.value.byAlgo.map(a => a.count)))
const windowLabel = computed(() => WINDOWS.find(w => w.key === selectedWindow.value)?.label ?? '')

async function resetMetrics() {
  if (resetting.value) return
  resetting.value = true
  try {
    await $fetch('/api/metrics/compression', { method: 'DELETE' })
    await refresh()
  }
  catch (e) {
    console.error('Failed to reset compression metrics:', e)
  }
  finally {
    resetting.value = false
  }
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
    <div class="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
      <h2 class="text-sm font-medium text-fg-primary">
        Chat Compression
      </h2>
      <div class="flex items-center gap-2">
        <div
          class="inline-flex items-center border border-border overflow-hidden"
          role="tablist"
          aria-label="Chat compression view"
        >
          <button
            type="button"
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
            :aria-selected="view === 'chart'"
            title="Chart view"
            class="p-1.5"
            :class="view === 'chart' ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
            @click="view = 'chart'"
          >
            <ChartBarIcon class="w-4 h-4" />
          </button>
        </div>
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

    <div class="px-4 py-2.5 border-b border-border flex flex-wrap items-center gap-3">
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

    <div
      v-if="!agg.hasData"
      class="px-4 py-4 text-xs text-fg-muted"
    >
      No compression activity in this window.
    </div>
    <div
      v-else
      class="p-4 space-y-4"
    >
      <div
        v-if="agg.alerts.length"
        class="space-y-1"
      >
        <div
          v-for="(a, i) in agg.alerts"
          :key="i"
          class="text-xs text-amber-700 bg-amber-50 border border-amber-200 px-2 py-1"
        >
          ⚠ {{ a }}
        </div>
      </div>

      <div>
        <div class="text-xs text-neutral-500">
          Tokens saved ({{ windowLabel }})
        </div>
        <div class="text-lg font-medium text-fg-strong">
          {{ fmt(agg.tokensSaved) }}
        </div>
      </div>

      <div v-if="agg.byType.length">
        <div class="text-xs text-neutral-500 mb-1">
          Saved by content type
        </div>
        <template v-if="view === 'chart'">
          <div
            v-for="t in agg.byType"
            :key="t.type"
            class="flex items-center gap-2 mb-1"
          >
            <span class="text-xs text-fg-strong w-12 shrink-0">{{ t.type }}</span>
            <div class="flex-1 bg-surface h-3">
              <div
                class="bg-emerald-500 h-3"
                :style="{ width: (t.saved / maxTypeSaved * 100) + '%' }"
              />
            </div>
            <span class="text-xs text-neutral-500 w-28 text-right shrink-0">{{ fmt(t.saved) }} ({{ 100 - pct(t.ratio) }}%)</span>
          </div>
        </template>
        <table
          v-else
          class="w-full text-xs"
        >
          <thead>
            <tr class="text-neutral-500 text-left">
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
              <td class="py-1 text-right text-fg-strong">
                {{ fmt(t.saved) }}
              </td>
              <td class="py-1 text-right text-neutral-500">
                {{ 100 - pct(t.ratio) }}%
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="agg.byAlgo.length">
        <div class="text-xs text-neutral-500 mb-1">
          Algorithm usage
        </div>
        <template v-if="view === 'chart'">
          <div
            v-for="a in agg.byAlgo"
            :key="a.algorithm"
            class="flex items-center gap-2 mb-1"
          >
            <span class="text-xs text-fg-strong w-32 shrink-0 truncate">{{ a.algorithm }}</span>
            <div class="flex-1 bg-surface h-3">
              <div
                class="bg-sky-500 h-3"
                :style="{ width: (a.count / maxAlgoCount * 100) + '%' }"
              />
            </div>
            <span class="text-xs text-neutral-500 w-12 text-right shrink-0">{{ a.count }}</span>
          </div>
        </template>
        <table
          v-else
          class="w-full text-xs"
        >
          <thead>
            <tr class="text-neutral-500 text-left">
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
              <td class="py-1 text-right text-fg-strong">
                {{ a.count }}
              </td>
              <td class="py-1 text-right text-neutral-500">
                {{ fmt(a.saved) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="grid grid-cols-2 gap-3">
        <div>
          <div class="text-xs text-neutral-500">
            CCR hit rate
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ agg.ccrTotal ? pct(agg.ccrHitRate) + '%' : '—' }}
            <span
              v-if="agg.ccrTotal"
              class="text-xs text-neutral-500"
            >({{ agg.ccrHits }}/{{ agg.ccrTotal }})</span>
          </div>
        </div>
        <div>
          <div class="text-xs text-neutral-500">
            Inflation guards
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ agg.guards }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
