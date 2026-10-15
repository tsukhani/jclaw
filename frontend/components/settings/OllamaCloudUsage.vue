<script setup lang="ts">
// Ollama Cloud's request history on its provider card (JCLAW-1449). ollama.com reports request
// counts only, so each bar is scaled to the busiest bucket, as Chat Cost's chart scales to its
// largest cost; there is no quota to be a share of.
import type { CloudUsage, CloudUsageBucket, CloudUsageRange } from '~/types/api'

const RANGES: CloudUsageRange[] = ['24h', '7d', '30d']
const range = ref<CloudUsageRange>('7d')

// Lazy: the read reaches ollama.com, and must not hold the panel open while it does.
const { data, status, refresh } = useLazyFetch<CloudUsage>('/api/router/usage/ollama-cloud', {
  query: { range },
})

const reading = computed(() => (data.value?.fetchedAt ? data.value : null))
const loading = computed(() => !reading.value && (status.value === 'idle' || status.value === 'pending'))
const peak = computed(() => Math.max(1, ...(reading.value?.buckets ?? []).map(b => b.requestCount)))

// A whole day, less an hour of slack. The running bucket is cut short, so a complete one decides.
const DAY_MS = 23 * 60 * 60 * 1000
const daily = computed(() => {
  const whole = reading.value?.buckets.find(b => !b.partial)
  return whole ? Date.parse(whole.until) - Date.parse(whole.from) >= DAY_MS : range.value !== '24h'
})

/** Day buckets are UTC days, so they are named in UTC; shorter ones by local time. */
function bucketLabel(b: CloudUsageBucket): string {
  const from = new Date(b.from)
  return daily.value
    ? from.toLocaleDateString(undefined, { month: 'short', day: 'numeric', timeZone: 'UTC' })
    : from.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

function count(n: number): string {
  return n.toLocaleString()
}
</script>

<template>
  <div
    class="px-4 py-2.5"
    data-testid="ollama-cloud-usage"
  >
    <div class="flex max-sm:flex-wrap items-center gap-3">
      <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">Cloud usage</span>
      <div
        class="inline-flex items-center border border-border overflow-hidden"
        role="group"
        aria-label="Cloud usage range"
      >
        <button
          v-for="r in RANGES"
          :key="r"
          type="button"
          class="px-2.5 py-1 text-xs"
          :class="range === r ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:text-fg-strong'"
          :aria-pressed="range === r"
          @click="range = r"
        >
          {{ r }}
        </button>
      </div>
      <span
        v-if="reading"
        class="ml-auto text-xs text-fg-muted"
        data-testid="ollama-cloud-usage-total"
      >
        Total <span class="font-mono text-fg-primary">{{ count(reading.total) }}</span> requests
      </span>
    </div>

    <p
      v-if="loading"
      class="mt-2 text-xs text-fg-muted"
    >
      Loading usage…
    </p>
    <div
      v-else-if="!reading"
      class="mt-2 flex items-center gap-2 text-xs text-amber-700 dark:text-amber-400"
      role="alert"
    >
      Could not read usage from Ollama Cloud.
      <button
        type="button"
        class="underline hover:text-fg-strong"
        @click="refresh()"
      >
        Retry
      </button>
    </div>
    <p
      v-else-if="reading.buckets.length === 0"
      class="mt-2 text-xs text-fg-muted"
    >
      No requests in this range.
    </p>
    <!-- One grid for every row so the bars share a left edge (Chat Cost's chart does the same). -->
    <div
      v-else
      class="mt-2 max-h-72 overflow-y-auto grid items-center gap-x-3 gap-y-1 text-xs grid-cols-[auto_minmax(0,1fr)_auto]"
    >
      <template
        v-for="b in reading.buckets"
        :key="b.from"
      >
        <span
          class="font-mono text-fg-muted whitespace-nowrap"
          :title="`${new Date(b.from).toLocaleString()} – ${new Date(b.until).toLocaleString()}`"
        >{{ bucketLabel(b) }}</span>
        <div
          class="relative h-4 bg-muted/30 border border-border overflow-hidden"
          aria-hidden="true"
        >
          <div
            class="h-full"
            :class="b.partial ? 'bg-emerald-500/15' : 'bg-emerald-500/30'"
            :style="{ width: `${((b.requestCount / peak) * 100).toFixed(2)}%` }"
          />
        </div>
        <span class="font-mono text-fg-primary tabular-nums text-right whitespace-nowrap">
          {{ count(b.requestCount) }}<span
            v-if="b.partial"
            class="text-fg-muted"
          > · in progress</span>
        </span>
      </template>
    </div>
  </div>
</template>
