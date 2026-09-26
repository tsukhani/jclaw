<script setup lang="ts">
/**
 * One circuit breaker's state and the operator's handle on it (JCLAW-1301). The dashboard, a
 * provider's card in Settings and a server's row on the MCP page all render this, so the three
 * cannot describe the same breaker differently. The default slot sits after the state badge.
 */
import type { Breaker } from '~/types/api'

const props = defineProps<{ breaker: Breaker }>()
const emit = defineEmits<{ changed: [] }>()

const { mutate, loading, errorDetails } = useApiMutation()
const { confirm } = useConfirm()

const action = computed(() => props.breaker.state === 'CLOSED' ? 'Isolate' : 'Restore')

function stateClass(state: Breaker['state']) {
  if (state === 'OPEN') return 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300'
  if (state === 'HALF_OPEN') return 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300'
  return 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300'
}

/** Why it is where it is, phrased so an operator's own decision never reads as a fault. */
function why(b: Breaker) {
  if (b.manual) return b.state === 'CLOSED' ? 'restored by you' : 'isolated by you'
  if (b.state === 'CLOSED') return b.samples ? recentCalls(b.samples) : 'no calls yet'
  if (b.state === 'HALF_OPEN') return 'probing — the next calls decide'
  return `tripped on ${b.reason?.toLowerCase().replaceAll('_', ' ') ?? 'failures'}`
}

function recentCalls(n: number) {
  return n === 1 ? '1 recent call' : `${n} recent calls`
}

async function isolate() {
  const target = props.breaker.target
  const ok = await confirm({
    title: `Isolate ${target}?`,
    message: `Calls to ${target} will be turned away until the cooldown elapses or you restore it. `
      + 'Use this to take a failing provider out of rotation, or to drill the failover path.',
    confirmText: 'Isolate',
    variant: 'danger',
  })
  if (!ok) return
  await mutate('/api/breakers/trip', { method: 'POST', body: { name: props.breaker.name } })
  emit('changed')
}

async function restore() {
  await mutate('/api/breakers/reset', { method: 'POST', body: { name: props.breaker.name } })
  emit('changed')
}
</script>

<template>
  <div class="min-w-0">
    <div class="flex flex-wrap items-center gap-x-3 gap-y-1.5">
      <span
        class="px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider shrink-0"
        :class="stateClass(breaker.state)"
        data-testid="breaker-state"
      >{{ breaker.state.replace('_', ' ') }}</span>
      <slot />
      <span class="text-xs text-fg-muted">{{ why(breaker) }}</span>
      <span
        v-if="breaker.samples"
        class="text-xs text-fg-muted font-mono"
      >{{ breaker.failures }}/{{ breaker.samples }} failed</span>
      <span
        v-if="breaker.slowCalls"
        class="text-xs text-fg-muted font-mono"
      >{{ breaker.slowCalls }} slow</span>
      <button
        type="button"
        class="ml-auto px-2 py-1 text-xs border border-border text-fg-strong hover:bg-muted/40 transition-colors disabled:opacity-50"
        :aria-label="`${action} ${breaker.target}`"
        :disabled="loading"
        @click="breaker.state === 'CLOSED' ? isolate() : restore()"
      >
        {{ action }}
      </button>
    </div>
    <ApiErrorAlert
      :error="errorDetails"
      class="mt-2"
    />
  </div>
</template>
