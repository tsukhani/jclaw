<script setup lang="ts">
/**
 * The note above the composer when the context-window trim left earlier messages out of what the agent read for the
 * latest reply. The messages stay saved; the agent's request says so too.
 */
import { ScissorsIcon } from '@heroicons/vue/24/outline'

const props = defineProps<{ dropped: number }>()

const detail = computed(() => props.dropped === 1
  ? '1 earlier message was left out of what the agent read for this reply, to fit the model\'s context window. It is still saved in this conversation.'
  : `${props.dropped} earlier messages were left out of what the agent read for this reply, to fit the model's context window. They are still saved in this conversation.`)
</script>

<template>
  <div
    class="pb-2"
    data-testid="chat-trim-notice"
  >
    <div
      class="flex items-start gap-2.5 rounded-2xl border border-border bg-surface-elevated px-3.5 py-2.5 shadow-sm"
      role="status"
      aria-live="polite"
    >
      <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-amber-100 text-amber-700 dark:bg-amber-400/15 dark:text-amber-300">
        <ScissorsIcon
          class="h-4 w-4"
          aria-hidden="true"
        />
      </span>
      <div class="min-w-0 flex-1">
        <span
          class="text-sm font-medium text-fg-strong"
          data-testid="trim-notice-title"
        >Earlier messages left out</span>
        <p
          class="text-xs text-fg-muted"
          data-testid="trim-notice-detail"
        >
          {{ detail }}
        </p>
      </div>
    </div>
  </div>
</template>
