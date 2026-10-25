<script setup lang="ts">
import { ClipboardDocumentCheckIcon } from '@heroicons/vue/24/outline'
import type { Message } from '~/types/api'

/**
 * The message that tells a chat how a task run it started ended. It is a USER-role row so the agent
 * reads it, but the operator did not write it, so it renders as a notice. It shows the run's facts
 * only: the output goes where the task delivers it.
 */
const props = defineProps<{ msg: Message }>()

interface ResultMetadata { status?: string, headline?: string, outcome?: string }
const meta = computed(() => (props.msg.metadata ?? {}) as ResultMetadata)
</script>

<template>
  <div
    class="flex justify-start"
    data-testid="chat-task-run-result"
  >
    <div class="max-w-[85%] w-full min-w-0 border border-neutral-200 dark:border-neutral-700 rounded-xl bg-surface-elevated px-3 py-2 flex flex-wrap items-center gap-3">
      <ClipboardDocumentCheckIcon
        class="w-4 h-4 shrink-0"
        :class="meta.status === 'FAILED' ? 'text-red-600 dark:text-red-400' : 'text-fg-muted'"
        aria-hidden="true"
      />
      <p class="min-w-0 flex-1 text-sm text-fg-primary">
        {{ meta.headline ?? 'A task run ended.' }}
        <span
          v-if="meta.outcome"
          class="text-fg-muted"
        >{{ meta.outcome }}</span>
      </p>
      <NuxtLink
        to="/tasks"
        class="text-xs text-emerald-700 dark:text-emerald-400 hover:underline"
      >
        Open tasks
      </NuxtLink>
    </div>
  </div>
</template>
