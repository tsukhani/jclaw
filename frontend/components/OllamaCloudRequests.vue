<script setup lang="ts">
// Ollama Cloud's request counts beside the Chat Cost fees (JCLAW-1449): a subscription's per-token
// cost reads $0.00, so the count is the figure that says how much of it is being used.
import type { CloudUsage } from '~/types/api'

const REFRESH_MS = 5 * 60 * 1000

const day = useLazyFetch<CloudUsage>('/api/router/usage/ollama-cloud', { query: { range: '24h' } })
const week = useLazyFetch<CloudUsage>('/api/router/usage/ollama-cloud', { query: { range: '7d' } })

const rows = computed(() => [
  { label: 'last 24 hours', usage: day.data.value },
  { label: 'last 7 days', usage: week.data.value },
])
const hasReading = computed(() => rows.value.some(r => r.usage?.fetchedAt))

let timer: ReturnType<typeof setInterval> | null = null

function load() {
  void day.refresh()
  void week.refresh()
}

function start() {
  if (!timer) timer = setInterval(load, REFRESH_MS)
}

function stop() {
  if (timer) clearInterval(timer)
  timer = null
}

// A dashboard left open in a background tab must not keep reaching ollama.com.
function onVisibility() {
  if (document.visibilityState === 'visible') {
    load()
    start()
  }
  else {
    stop()
  }
}

onMounted(() => {
  if (document.visibilityState === 'visible') start()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <div
    v-if="hasReading"
    class="border border-border bg-muted/10 min-w-[9rem] px-3 py-2"
    data-testid="ollama-cloud-requests"
  >
    <div class="text-[10px] text-fg-muted uppercase tracking-wide">
      Ollama Cloud requests
    </div>
    <div
      v-for="r in rows"
      :key="r.label"
      class="mt-0.5 flex items-baseline gap-1.5"
    >
      <span class="font-mono text-sm text-fg-primary tabular-nums">{{ r.usage?.fetchedAt ? r.usage.total.toLocaleString() : '—' }}</span>
      <span class="text-[10px] text-fg-muted">{{ r.label }}</span>
    </div>
  </div>
</template>
