<script setup lang="ts">
import { ChevronDownIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'

defineProps<{ runs: SubagentChip[], expandedIds: Set<number> }>()
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
</script>

<template>
  <div
    data-testid="subagent-stack"
    class="mx-auto w-full max-w-3xl px-4 pt-3"
  >
    <!-- max-h-36 fits four minimized rows; an expanded chip needs room for its transcript. -->
    <ul
      aria-label="Subagents spawned in this conversation"
      class="flex flex-col gap-1 overflow-y-auto overscroll-contain"
      :class="expandedIds.size ? 'max-h-[50vh]' : 'max-h-36'"
    >
      <li
        v-for="run in runs"
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
            :title="run.childAgentName ?? undefined"
          >{{ chipName(run) }}</span>
          <span
            data-testid="subagent-chip-status"
            class="ml-auto shrink-0 text-[10px] font-mono uppercase tracking-wide"
          >{{ statusWords[run.status] }}</span>
          <button
            type="button"
            data-testid="subagent-chip-toggle"
            :aria-expanded="expandedIds.has(run.id)"
            :aria-controls="`subagent-chip-panel-${run.id}`"
            :aria-label="`${expandedIds.has(run.id) ? 'Collapse' : 'Expand'} ${chipName(run)}`"
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
            @click="emit('close', run.id)"
          >
            <XMarkIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
        <div
          v-if="expandedIds.has(run.id)"
          :id="`subagent-chip-panel-${run.id}`"
          data-testid="subagent-chip-expanded"
          class="max-h-[40vh] overflow-y-auto overscroll-contain border-t border-neutral-200 dark:border-neutral-700
                 bg-surface-elevated text-fg-strong"
        >
          <slot
            name="expanded"
            :run="run"
          />
        </div>
      </li>
    </ul>
  </div>
</template>
