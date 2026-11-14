<script setup lang="ts">
/**
 * A stored summary in the conversation transcript, after the last message it covers: a divider naming it, which
 * opens to the summary the agent was given in those messages' place, who wrote it and when, and whether the agent
 * still reads it, so every summary in a long conversation stays open to audit.
 */
import { ArrowsPointingInIcon, ChevronDownIcon } from '@heroicons/vue/24/outline'
import { renderMarkdown } from '~/utils/chat-markdown'
import {
  mergedListLabel, summaryCoversLabel, summaryHeadline, summaryStatusLabel, summaryStatusSentence, type SummaryMarker,
} from '~/utils/summary-markers'

const props = defineProps<{
  marker: SummaryMarker
  agentId: number | null
  /** The page was opened at this marker, as a Chat divider's link opens it, so it starts open. */
  targeted?: boolean
}>()

// Read once: the link opens it on arrival, and the toggle owns it after.
const open = ref(props.targeted === true)
const bodyId = computed(() => `summary-${props.marker.number}-body`)
const covers = computed(() => summaryCoversLabel(props.marker.compaction))
const statusClass = computed(() => ({
  live: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  merged: 'bg-muted text-fg-muted',
  reset: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
})[props.marker.compaction.status])
</script>

<template>
  <div
    :id="`summary-${marker.number}`"
    data-testid="summary-marker"
  >
    <button
      type="button"
      class="flex w-full items-center gap-3 text-xs text-fg-muted hover:text-fg-strong transition-colors"
      :aria-expanded="open"
      :aria-controls="bodyId"
      @click="open = !open"
    >
      <span class="flex-1 border-t border-dashed border-border" />
      <ArrowsPointingInIcon
        class="h-3.5 w-3.5 shrink-0"
        aria-hidden="true"
      />
      <span
        class="min-w-0 whitespace-nowrap max-sm:whitespace-normal"
        data-testid="summary-marker-headline"
      >{{ summaryHeadline(marker) }}</span>
      <span
        class="rounded px-1.5 py-0.5 text-xs font-medium whitespace-nowrap"
        :class="statusClass"
        data-testid="summary-marker-status"
      >{{ summaryStatusLabel(marker) }}</span>
      <ChevronDownIcon
        class="h-3.5 w-3.5 shrink-0 transition-transform"
        :class="open ? '' : '-rotate-90'"
        aria-hidden="true"
      />
      <span class="flex-1 border-t border-dashed border-border" />
    </button>
    <div
      v-if="open"
      :id="bodyId"
      class="mt-2 rounded-md border border-border bg-surface-elevated px-3 py-2"
      data-testid="summary-marker-body"
    >
      <p
        class="text-xs text-fg-muted"
        data-testid="summary-marker-meaning"
      >
        {{ summaryStatusSentence(marker) }}
      </p>
      <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs">
        <dt class="text-fg-muted">
          Written
        </dt>
        <dd class="font-mono tabular-nums text-fg-primary">
          {{ new Date(marker.compaction.compactedAt).toLocaleString() }}
        </dd>
        <dt class="text-fg-muted">
          By
        </dt>
        <dd class="font-mono text-fg-primary break-all">
          {{ marker.compaction.model }}
        </dd>
        <dt class="text-fg-muted">
          Covers
        </dt>
        <dd class="font-mono tabular-nums text-fg-primary">
          {{ covers ?? 'Not recorded for a summary this old' }}
        </dd>
        <template v-if="marker.merges.length">
          <dt class="text-fg-muted">
            Merges
          </dt>
          <dd class="text-fg-primary">
            {{ mergedListLabel(marker.merges) }}
          </dd>
        </template>
      </dl>
      <!-- eslint-disable vue/no-v-html -- renderMarkdown runs content through DOMPurify before returning. -->
      <div
        class="prose-chat mt-3 border-t border-border pt-2 text-sm text-fg-primary"
        data-testid="summary-marker-text"
        v-html="renderMarkdown(marker.compaction.summary, agentId)"
      />
      <!-- eslint-enable vue/no-v-html -->
    </div>
  </div>
</template>
