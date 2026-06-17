<script setup lang="ts">
/**
 * JCLAW-467: per-agent compression metrics dashboard. Renders the savings
 * rollups, by-type and per-algorithm breakdowns (horizontal bars, no chart
 * dependency), CCR hit rate, inflation-guard count, threshold alerts, and a CSV
 * export link. Fed by GET /api/agents/{id}/compression-metrics.
 */
import type { CompressionMetricsSummary } from '~/types/api'

const props = defineProps<{ agentId: number }>()

const metrics = ref<CompressionMetricsSummary | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    metrics.value = await $fetch<CompressionMetricsSummary>(
      `/api/agents/${props.agentId}/compression-metrics`)
  }
  catch (e) {
    console.error('Failed to load compression metrics:', e)
    metrics.value = null
  }
  finally {
    loading.value = false
  }
}

watch(() => props.agentId, load, { immediate: true })

const maxTypeSaved = computed(() =>
  Math.max(1, ...(metrics.value?.ratioByType.map(t => t.tokensBefore - t.tokensAfter) ?? [0])))
const maxAlgoCount = computed(() =>
  Math.max(1, ...(metrics.value?.algorithmUsage.map(a => a.count) ?? [0])))
const hasActivity = computed(() => {
  const m = metrics.value
  return !!m && (m.tokensSaved30d > 0 || m.ccrRetrievals > 0 || m.inflationGuardCount > 0)
})
const csvHref = computed(() => `/api/agents/${props.agentId}/compression-metrics?format=csv`)

function pct(n: number) {
  return Math.round(n * 100)
}
function fmt(n: number) {
  return n.toLocaleString()
}
</script>

<template>
  <div class="bg-surface-elevated border border-border">
    <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
      <span class="text-sm font-medium text-fg-strong">Compression Metrics</span>
      <a
        v-if="hasActivity"
        :href="csvHref"
        class="text-xs text-neutral-500 hover:text-fg-strong"
      >Export CSV</a>
    </div>

    <div
      v-if="loading"
      class="px-4 py-3 text-xs text-neutral-500"
    >
      Loading…
    </div>
    <div
      v-else-if="!hasActivity"
      class="px-4 py-3 text-xs text-neutral-500"
    >
      No compression activity yet for this agent.
    </div>
    <div
      v-else-if="metrics"
      class="p-4 space-y-4"
    >
      <!-- Alerts -->
      <div
        v-if="metrics.alerts.length"
        class="space-y-1"
      >
        <div
          v-for="(a, i) in metrics.alerts"
          :key="i"
          class="text-xs text-amber-700 bg-amber-50 border border-amber-200 px-2 py-1"
        >
          ⚠ {{ a }}
        </div>
      </div>

      <!-- Tokens saved rollups -->
      <div class="grid grid-cols-3 gap-3">
        <div>
          <div class="text-xs text-neutral-500">
            Saved 24h
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ fmt(metrics.tokensSaved24h) }}
          </div>
        </div>
        <div>
          <div class="text-xs text-neutral-500">
            Saved 7d
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ fmt(metrics.tokensSaved7d) }}
          </div>
        </div>
        <div>
          <div class="text-xs text-neutral-500">
            Saved 30d
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ fmt(metrics.tokensSaved30d) }}
          </div>
        </div>
      </div>

      <!-- Saved by content type -->
      <div v-if="metrics.ratioByType.length">
        <div class="text-xs text-neutral-500 mb-1">
          Saved by content type (30d)
        </div>
        <div
          v-for="t in metrics.ratioByType"
          :key="t.contentType"
          class="flex items-center gap-2 mb-1"
        >
          <span class="text-xs text-fg-strong w-12 shrink-0">{{ t.contentType }}</span>
          <div class="flex-1 bg-surface h-3">
            <div
              class="bg-emerald-500 h-3"
              :style="{ width: ((t.tokensBefore - t.tokensAfter) / maxTypeSaved * 100) + '%' }"
            />
          </div>
          <span class="text-xs text-neutral-500 w-28 text-right shrink-0">
            {{ fmt(t.tokensBefore - t.tokensAfter) }} ({{ 100 - pct(t.ratio) }}%)
          </span>
        </div>
      </div>

      <!-- Algorithm usage -->
      <div v-if="metrics.algorithmUsage.length">
        <div class="text-xs text-neutral-500 mb-1">
          Algorithm usage (30d)
        </div>
        <div
          v-for="a in metrics.algorithmUsage"
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
      </div>

      <!-- CCR hit rate + inflation guards -->
      <div class="grid grid-cols-2 gap-3">
        <div>
          <div class="text-xs text-neutral-500">
            CCR hit rate
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ metrics.ccrRetrievals ? pct(metrics.ccrHitRate) + '%' : '—' }}
            <span
              v-if="metrics.ccrRetrievals"
              class="text-xs text-neutral-500"
            >({{ metrics.ccrHits }}/{{ metrics.ccrRetrievals }})</span>
          </div>
        </div>
        <div>
          <div class="text-xs text-neutral-500">
            Inflation guards (30d)
          </div>
          <div class="text-sm font-medium text-fg-strong">
            {{ metrics.inflationGuardCount }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
