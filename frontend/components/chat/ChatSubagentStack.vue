<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { ChevronDownIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'

const props = defineProps<{
  runs: SubagentChip[]
  expandedIds: Set<number>
  conversationId?: number | null
  /** Set when the chip list was cut short: the number of runs the conversation has in all. */
  allRunsTotal?: number | null
}>()
const emit = defineEmits<{ toggle: [id: number], close: [id: number] }>()
defineSlots<{ expanded?: (props: { run: SubagentChip }) => unknown }>()

// The badge colours of pages/subagents.vue, so a chip reads the same as its row there.
const chipColors: Record<SubagentRunStatus, string> = {
  RUNNING: 'bg-blue-100 dark:bg-blue-400/10 text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-400/20',
  COMPLETED: 'bg-emerald-100 dark:bg-emerald-400/10 text-emerald-700 dark:text-emerald-400 border-emerald-300 dark:border-emerald-400/20',
  FAILED: 'bg-red-100 dark:bg-red-400/10 text-red-700 dark:text-red-400 border-red-300 dark:border-red-400/20',
  KILLED: 'bg-yellow-100 dark:bg-yellow-400/10 text-yellow-700 dark:text-yellow-400 border-yellow-300 dark:border-yellow-400/20',
  TIMEOUT: 'bg-orange-100 dark:bg-orange-400/10 text-orange-700 dark:text-orange-400 border-orange-300 dark:border-orange-400/20',
}

const dotColors: Record<SubagentRunStatus, string> = {
  RUNNING: 'bg-blue-500 animate-pulse',
  COMPLETED: 'bg-emerald-500',
  FAILED: 'bg-red-500',
  KILLED: 'bg-yellow-500',
  TIMEOUT: 'bg-orange-500',
}

const statusWords: Record<SubagentRunStatus, string> = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  KILLED: 'Killed',
  TIMEOUT: 'Timed out',
}

function chipName(run: SubagentChip): string {
  return run.label?.trim() || run.childAgentName || `Run #${run.id}`
}

// A labelled chip still names its child agent, which is how the Subagents page lists the run.
function chipTitle(run: SubagentChip): string | undefined {
  const label = run.label?.trim()
  if (!label) return run.childAgentName ?? undefined
  return run.childAgentName ? `${label} · ${run.childAgentName}` : label
}

function chipAccessibleName(run: SubagentChip): string {
  const label = run.label?.trim()
  return label && run.childAgentName ? `${label} (${run.childAgentName})` : chipName(run)
}

const toggleButtons = new Map<number, HTMLElement>()
function bindToggle(id: number, el: unknown) {
  if (el instanceof HTMLElement) toggleButtons.set(id, el)
  else toggleButtons.delete(id)
}

// The close button removes its own row, so hand focus to the neighbouring chip rather than the page body.
function close(index: number) {
  const run = props.runs[index]
  if (!run) return
  const neighbour = props.runs[index + 1] ?? props.runs[index - 1]
  emit('close', run.id)
  if (neighbour) void nextTick(() => toggleButtons.get(neighbour.id)?.focus())
}

// A chip expanded low in the stack would open its panel below the scroller's fold.
watch(() => props.expandedIds, (now, was) => {
  const opened = [...now].filter(id => !was.has(id)).at(-1)
  const row = opened == null ? null : toggleButtons.get(opened)?.closest('li')
  if (typeof row?.scrollIntoView === 'function') row.scrollIntoView({ block: 'nearest' })
}, { flush: 'post' })

const announcement = ref('')
const lastStatus = new Map<number, SubagentRunStatus>()
watch(() => props.runs.map(r => r.status), () => {
  const ended: string[] = []
  for (const run of props.runs) {
    if (lastStatus.get(run.id) === 'RUNNING' && run.status !== 'RUNNING') {
      ended.push(`${chipName(run)}: ${statusWords[run.status]}`)
    }
    lastStatus.set(run.id, run.status)
  }
  if (!ended.length) return
  // A live region ignores a write of the text it already holds, so a repeat is cleared first.
  const text = ended.join('. ')
  announcement.value = ''
  void nextTick(() => {
    announcement.value = text
  })
}, { immediate: true })
</script>

<template>
  <div
    data-testid="subagent-stack"
    class="mx-auto w-full max-w-3xl px-4 pt-3"
  >
    <p
      data-testid="subagent-stack-announcer"
      class="sr-only"
      aria-live="polite"
    >
      {{ announcement }}
    </p>
    <!-- max-h-36 fits four minimized rows; an expanded chip needs room for its transcript. -->
    <ul
      v-if="runs.length"
      aria-label="Subagents spawned in this conversation"
      class="flex flex-col gap-1 overflow-y-auto overscroll-contain"
      :class="expandedIds.size ? 'max-h-[50vh]' : 'max-h-36'"
    >
      <li
        v-for="(run, index) in runs"
        :key="run.id"
        data-testid="subagent-chip"
        :data-status="run.status"
        class="shrink-0 text-xs border rounded"
        :class="chipColors[run.status]"
      >
        <div class="flex items-center gap-2 min-w-0 px-2 py-1">
          <span
            data-testid="subagent-chip-dot"
            class="w-1.5 h-1.5 shrink-0 rounded-full"
            :class="dotColors[run.status]"
            aria-hidden="true"
          />
          <span
            data-testid="subagent-chip-label"
            class="font-mono truncate min-w-0"
            :title="chipTitle(run)"
          >{{ chipName(run) }}</span>
          <span
            data-testid="subagent-chip-status"
            class="ml-auto shrink-0 text-[10px] font-mono uppercase tracking-wide"
          >{{ statusWords[run.status] }}</span>
          <button
            :ref="(el: unknown) => bindToggle(run.id, el)"
            type="button"
            data-testid="subagent-chip-toggle"
            :aria-expanded="expandedIds.has(run.id)"
            :aria-controls="expandedIds.has(run.id) ? `subagent-chip-panel-${run.id}` : undefined"
            :aria-label="`${expandedIds.has(run.id) ? 'Collapse' : 'Expand'} ${chipAccessibleName(run)}`"
            class="shrink-0 p-0.5 rounded hover:bg-black/5 dark:hover:bg-white/10
                   focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-current"
            @click="emit('toggle', run.id)"
          >
            <ChevronDownIcon
              class="w-3.5 h-3.5 transition-transform"
              :class="{ 'rotate-180': expandedIds.has(run.id) }"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            data-testid="subagent-chip-close"
            :aria-label="`Close ${chipName(run)}`"
            class="shrink-0 p-0.5 rounded hover:bg-black/5 dark:hover:bg-white/10
                   focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-current"
            @click="close(index)"
          >
            <XMarkIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
        <!-- Not a scroller: the panel inside owns the height budget, so its footer stays in view. -->
        <div
          v-if="expandedIds.has(run.id)"
          :id="`subagent-chip-panel-${run.id}`"
          data-testid="subagent-chip-expanded"
          class="border-t border-neutral-200 dark:border-neutral-700 bg-surface-elevated text-fg-strong"
        >
          <slot
            name="expanded"
            :run="run"
          />
        </div>
      </li>
    </ul>
    <NuxtLink
      v-if="allRunsTotal && conversationId"
      :to="`/subagents?parentConversationId=${conversationId}`"
      data-testid="subagent-stack-all-runs"
      class="mt-1 block text-right text-xs text-fg-muted underline-offset-2 hover:text-fg-strong hover:underline"
    >
      View all {{ allRunsTotal }} on the Subagents page
    </NuxtLink>
  </div>
</template>
