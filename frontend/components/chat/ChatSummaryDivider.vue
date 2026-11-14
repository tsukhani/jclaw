<script setup lang="ts">
/**
 * A summary the agent reads, after the last message it covers: from here up to the previous summary, the agent is
 * given the summary in those messages' place. It links to the same summary on the conversation page, which shows
 * its text.
 */
import { ArrowsPointingInIcon } from '@heroicons/vue/24/outline'
import { summaryHeadline, type SummaryMarker } from '~/utils/summary-markers'

defineProps<{
  marker: SummaryMarker
  conversationId: number
}>()
</script>

<template>
  <div
    class="flex items-center gap-3 text-xs text-fg-muted"
    data-testid="chat-summary-divider"
  >
    <span
      class="flex-1 border-t border-dashed border-border"
      aria-hidden="true"
    />
    <NuxtLink
      :to="`/conversations/${conversationId}#summary-${marker.number}`"
      class="flex items-center gap-1.5 whitespace-nowrap hover:text-fg-strong hover:underline"
      data-testid="chat-summary-divider-link"
    >
      <ArrowsPointingInIcon
        class="h-3.5 w-3.5 shrink-0"
        aria-hidden="true"
      />
      <span>{{ summaryHeadline(marker) }}</span>
      <span class="sr-only">, read it on the conversation page</span>
    </NuxtLink>
    <span
      class="flex-1 border-t border-dashed border-border"
      aria-hidden="true"
    />
  </div>
</template>
