<script setup lang="ts">
/**
 * The card above the composer while a conversation is being compacted, and for a few seconds after.
 *
 * Under way it shows the tokens being compressed and a bar of the rows summarized so far; a compaction that has not
 * said how many rows it has (a `/compact` before its first frame reaches the bus) shows a sliding bar instead, as one
 * merging its earlier summaries does. When it ends it shows the prompt's token count before and after in the
 * provider's units, as the context meter counts them, or, when no summary was stored, why.
 */
import { ArrowsPointingInIcon, CheckCircleIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import type { CompactionCard } from '~/composables/useChatStream'

const props = defineProps<{ card: CompactionCard }>()

function tokens(n: number): string {
  if (n >= 10_000) return `${Math.round(n / 1000)}k`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return String(n)
}

const running = computed(() => props.card.phase === 'start')
const skipped = computed(() => props.card.phase === 'skipped')
const title = computed(() => {
  if (running.value) return 'Compacting conversation'
  return skipped.value ? 'Summary not made' : 'Conversation compacted'
})
const badgeClass = computed(() => {
  if (running.value) return 'bg-violet-100 text-violet-600 dark:bg-violet-400/15 dark:text-violet-300'
  return skipped.value
    ? 'bg-amber-100 text-amber-700 dark:bg-amber-400/15 dark:text-amber-300'
    : 'bg-emerald-100 text-emerald-600 dark:bg-emerald-400/15 dark:text-emerald-300'
})
const percentRows = computed(() => {
  const { done, total } = props.card
  return total && total > 0 && done != null ? Math.min(100, Math.round((done / total) * 100)) : null
})
const merging = computed(() => props.card.stage === 'merging')
// Rows are only counted once the first summary has landed, so a bar at 0 would look stalled; a merge has no count.
const determinate = computed(() => !merging.value && percentRows.value != null && percentRows.value > 0)
const kept = computed(() => {
  const { tokensBefore, tokensAfter } = props.card
  return tokensBefore && tokensAfter != null && tokensBefore > 0
    ? Math.max(0, Math.min(100, Math.round((tokensAfter / tokensBefore) * 100)))
    : null
})
const freed = computed(() => (kept.value == null ? null : 100 - kept.value))
const seconds = ref(0)
let ticker: ReturnType<typeof setInterval> | null = null
watch(running, (now) => {
  if (ticker) clearInterval(ticker)
  ticker = null
  if (!now) return
  seconds.value = 0
  ticker = setInterval(() => {
    seconds.value++
  }, 1000)
}, { immediate: true })
onBeforeUnmount(() => {
  if (ticker) clearInterval(ticker)
})

const took = computed(() => {
  const ms = props.card.ms
  if (ms == null) return null
  const digits = ms < 10_000 ? 1 : 0
  return `${(ms / 1000).toFixed(digits)} s`
})
const summary = computed(() => {
  const { tokensBefore, total, summaries } = props.card
  if (merging.value) return summaries && summaries > 1 ? `Merging ${summaries} earlier summaries into one` : 'Merging earlier summaries'
  const plural = total === 1 ? '' : 's'
  const rows = total ? `${total} earlier message${plural}` : 'earlier messages'
  return tokensBefore ? `Summarizing ${rows} · ${tokens(tokensBefore)} tokens` : `Summarizing ${rows}`
})
</script>

<template>
  <div
    class="pb-2"
    data-testid="chat-compaction-card"
  >
    <div
      class="rounded-2xl border border-border bg-surface-elevated px-3.5 py-2.5 shadow-sm"
      role="status"
      aria-live="polite"
    >
      <div class="flex items-center gap-2.5">
        <span
          class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full"
          :class="badgeClass"
        >
          <ArrowsPointingInIcon
            v-if="running"
            class="h-4 w-4 animate-pulse motion-reduce:animate-none"
            aria-hidden="true"
          />
          <ExclamationTriangleIcon
            v-else-if="skipped"
            class="h-4 w-4"
            aria-hidden="true"
          />
          <CheckCircleIcon
            v-else
            class="h-4 w-4"
            aria-hidden="true"
          />
        </span>
        <div class="min-w-0 flex-1">
          <div class="flex items-baseline justify-between gap-3">
            <span
              class="text-sm font-medium text-fg-strong"
              data-testid="compaction-title"
            >{{ title }}</span>
            <span
              class="shrink-0 font-mono text-xs tabular-nums text-fg-muted"
              data-testid="compaction-time"
            >{{ running ? `${seconds} s` : took }}</span>
          </div>
          <p
            v-if="running"
            class="truncate text-xs text-fg-muted"
            data-testid="compaction-summary"
          >
            {{ summary }}
          </p>
          <p
            v-else-if="skipped && card.reason"
            class="text-xs text-fg-muted"
            data-testid="compaction-skip-reason"
          >
            {{ card.reason }}
          </p>
          <p
            v-else-if="card.tokensBefore != null && card.tokensAfter != null"
            class="text-xs text-fg-muted"
            data-testid="compaction-result"
          >
            <span class="font-mono tabular-nums text-fg-strong">{{ tokens(card.tokensBefore) }} → {{ tokens(card.tokensAfter) }}</span>
            tokens<template v-if="freed != null">
              · {{ freed }}% freed
            </template>
          </p>
          <p
            v-else-if="card.total"
            class="text-xs text-fg-muted"
            data-testid="compaction-result"
          >
            {{ card.total }} earlier message{{ card.total === 1 ? '' : 's' }} summarized
          </p>
        </div>
      </div>

      <div
        v-if="!skipped"
        class="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-border"
        role="progressbar"
        aria-label="Compaction progress"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuenow="running ? (determinate ? percentRows ?? undefined : undefined) : kept ?? undefined"
        :data-testid="running ? (determinate ? 'compaction-bar-rows' : 'compaction-bar-indeterminate') : 'compaction-bar-kept'"
      >
        <div
          v-if="running && !determinate"
          class="compaction-slide h-full w-1/3 rounded-full bg-violet-500"
        />
        <div
          v-else
          class="h-full rounded-full transition-[width] duration-500 motion-reduce:transition-none"
          :class="running ? 'bg-violet-500' : 'bg-emerald-500'"
          :style="{ width: (running ? percentRows : kept) + '%' }"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.compaction-slide {
  animation: compaction-slide 1.4s ease-in-out infinite;
}

@keyframes compaction-slide {
  from {
    transform: translateX(-100%);
  }

  to {
    transform: translateX(300%);
  }
}

@media (prefers-reduced-motion: reduce) {
  .compaction-slide {
    width: 100%;
    animation: none;
    opacity: 0.5;
  }
}
</style>
