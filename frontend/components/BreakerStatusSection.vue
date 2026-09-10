<script setup lang="ts">
/**
 * Per-subsystem circuit-breaker state (JCLAW-1170).
 *
 * On the dashboard, immediately above Chat Performance, because that is the blind spot this
 * panel exists to close: an open breaker turns every call away in microseconds, so the
 * latency chart below it *improves* and the error rate stays flat while real work fails.
 * Read anywhere else, those graphs would still be believed.
 *
 * Renders nothing until a breaker exists. They are minted on first use, so a fresh install
 * has none and an empty box would say only "this feature is here", which is not a signal.
 */
const REFRESH_MS = 10_000

interface Breaker {
  name: string
  /** Registry-name prefix: `llm` or `mcp`. */
  subsystem: string
  /** What the breaker guards — a provider name, an MCP server name. */
  target: string
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  samples: number
  failures: number
  slowCalls: number
  /** What moved it into `state`; null before it has ever moved. */
  reason: string | null
  /** Whether `reason` was an operator's decision rather than the breaker acting alone. */
  manual: boolean
}

const { data, refresh } = useLazyFetch<Breaker[]>('/api/breakers')
const { mutate, loading } = useApiMutation()
const { confirm } = useConfirm()

const breakers = computed(() => data.value ?? [])
const degraded = computed(() => breakers.value.filter(b => b.state !== 'CLOSED').length)

let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  timer = setInterval(() => refresh(), REFRESH_MS)
})
onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  timer = null
})

function stateClass(state: Breaker['state']) {
  if (state === 'OPEN') return 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300'
  if (state === 'HALF_OPEN') return 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'
  return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300'
}

/** Why it is where it is, phrased so an operator's own decision never reads as a fault. */
function why(b: Breaker) {
  if (b.manual) return b.state === 'CLOSED' ? 'restored by you' : 'isolated by you'
  if (b.state === 'CLOSED') return b.samples ? `${b.samples} recent calls` : 'no calls yet'
  if (b.state === 'HALF_OPEN') return 'probing — the next calls decide'
  return `tripped on ${b.reason?.toLowerCase().replace(/_/g, ' ') ?? 'failures'}`
}

async function isolate(b: Breaker) {
  const ok = await confirm({
    title: `Isolate ${b.target}?`,
    message: `Calls to ${b.target} will be turned away until the cooldown elapses or you restore it. `
      + 'Use this to take a failing provider out of rotation, or to drill the failover path.',
    confirmText: 'Isolate',
    variant: 'danger',
  })
  if (!ok) return
  await mutate('/api/breakers/trip', { method: 'POST', body: { name: b.name } })
  await refresh()
}

async function restore(b: Breaker) {
  await mutate('/api/breakers/reset', { method: 'POST', body: { name: b.name } })
  await refresh()
}
</script>

<template>
  <div
    v-if="breakers.length"
    class="bg-surface-elevated border border-border mb-8"
    data-testid="breaker-status"
  >
    <div class="px-4 py-3 border-b border-border flex items-center gap-3">
      <h2 class="text-sm font-medium text-fg-primary shrink-0">
        Circuit Breakers
      </h2>
      <span
        v-if="degraded"
        class="px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300"
      >
        {{ degraded }} not serving
      </span>
      <span class="ml-auto text-xs text-fg-muted">
        Calls turned away here never reach the graphs below.
      </span>
    </div>

    <div class="divide-y divide-border">
      <div
        v-for="b in breakers"
        :key="b.name"
        class="px-4 py-2.5 flex flex-wrap items-center gap-x-3 gap-y-1.5"
        :data-testid="`breaker-row-${b.name}`"
      >
        <span
          class="px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider shrink-0"
          :class="stateClass(b.state)"
        >{{ b.state.replace('_', ' ') }}</span>
        <span class="text-sm text-fg-strong font-mono truncate">{{ b.target }}</span>
        <span class="text-[10px] uppercase tracking-wider text-fg-muted shrink-0">{{ b.subsystem }}</span>
        <span class="text-xs text-fg-muted">{{ why(b) }}</span>
        <span
          v-if="b.samples"
          class="text-xs text-fg-muted font-mono"
        >{{ b.failures }}/{{ b.samples }} failed</span>
        <span
          v-if="b.slowCalls"
          class="text-xs text-fg-muted font-mono"
        >{{ b.slowCalls }} slow</span>
        <button
          type="button"
          class="ml-auto px-2 py-1 text-xs border border-border text-fg-strong hover:bg-muted/40 transition-colors disabled:opacity-50"
          :disabled="loading"
          @click="b.state === 'CLOSED' ? isolate(b) : restore(b)"
        >
          {{ b.state === 'CLOSED' ? 'Isolate' : 'Restore' }}
        </button>
      </div>
    </div>
  </div>
</template>
