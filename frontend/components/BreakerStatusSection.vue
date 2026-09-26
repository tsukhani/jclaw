<script setup lang="ts">
/**
 * The circuit breakers that are not serving (JCLAW-1170, JCLAW-1301).
 *
 * On the dashboard, immediately above Chat Performance, because that is the blind spot this
 * panel exists to close: an open breaker turns every call away in microseconds, so the
 * latency chart below it *improves* and the error rate stays flat while real work fails.
 * Read anywhere else, those graphs would still be believed.
 *
 * A closed breaker distorts nothing here, so it is shown only beside what it guards: the
 * provider's card in Settings, the server's row on the MCP page, the decision provider's card.
 * Renders nothing while every breaker is serving.
 */
import type { Breaker } from '~/types/api'

const { breakers, refresh } = useBreakers()

const notServing = computed(() => breakers.value.filter(b => b.state !== 'CLOSED'))

const GROUPS: ReadonlyArray<{ subsystem: string, title: string, home?: string }> = [
  { subsystem: 'llm', title: 'LLM providers', home: '/settings?section=providers' },
  { subsystem: 'mcp', title: 'MCP servers', home: '/mcp-servers' },
  { subsystem: 'decision', title: 'Decision providers', home: '/settings?section=decision-providers' },
]
const SERVING_RANK: Record<Breaker['state'], number> = { OPEN: 0, HALF_OPEN: 1, CLOSED: 2 }

/**
 * One section per subsystem, providers first because a provider breaker affects every turn
 * on it and there are few; open before probing within a section. A subsystem the panel does
 * not know still renders, under its own prefix and with no link, rather than vanishing.
 */
const groups = computed(() => {
  const known = new Set(GROUPS.map(g => g.subsystem))
  const extra = [...new Set(notServing.value.map(b => b.subsystem))]
    .filter(s => !known.has(s))
    .map(s => ({ subsystem: s, title: s, home: undefined }))
  return [...GROUPS, ...extra]
    .map(g => ({
      ...g,
      rows: notServing.value
        .filter(b => b.subsystem === g.subsystem)
        .sort((a, b) => SERVING_RANK[a.state] - SERVING_RANK[b.state] || a.target.localeCompare(b.target)),
    }))
    .filter(g => g.rows.length)
})
</script>

<template>
  <div
    v-if="notServing.length"
    class="bg-surface-elevated border border-border mb-8"
    data-testid="breaker-status"
  >
    <div class="px-4 py-3 border-b border-border flex items-center gap-3">
      <h2 class="text-sm font-medium text-fg-primary shrink-0">
        Circuit Breakers
      </h2>
      <span class="px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wider bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300">
        {{ notServing.length }} not serving
      </span>
      <span class="ml-auto text-xs text-fg-muted">
        Calls turned away here never reach the graphs below.
      </span>
    </div>

    <section
      v-for="g in groups"
      :key="g.subsystem"
      :data-testid="`breaker-group-${g.subsystem}`"
    >
      <h3 class="px-4 py-1.5 text-[10px] font-medium uppercase tracking-wider text-fg-muted bg-muted/30 border-b border-border">
        {{ g.title }}
      </h3>
      <div class="divide-y divide-border border-b border-border last:border-b-0">
        <BreakerControl
          v-for="b in g.rows"
          :key="b.name"
          :breaker="b"
          class="px-4 py-2.5"
          :data-testid="`breaker-row-${b.name}`"
          @changed="refresh()"
        >
          <NuxtLink
            v-if="g.home"
            :to="g.home"
            class="text-sm text-fg-strong font-mono truncate hover:underline"
            :title="`Where ${b.target} is configured`"
            data-testid="breaker-home"
          >
            {{ b.target }}
          </NuxtLink>
          <span
            v-else
            class="text-sm text-fg-strong font-mono truncate"
          >{{ b.target }}</span>
        </BreakerControl>
      </div>
    </section>
  </div>
</template>
