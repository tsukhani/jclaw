<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDownIcon } from '@heroicons/vue/24/outline'
import { CheckCircleIcon } from '@heroicons/vue/16/solid'
import type { ChecklistItem } from '~/types/api'

// Once the turn ends, an in_progress step is only the agent's last record, not work under way.
const props = defineProps<{ items: readonly ChecklistItem[], running: boolean }>()

const open = ref(true)
const doneCount = computed(() => props.items.filter(item => item.status === 'completed').length)
const current = computed(() => props.running ? props.items.find(item => item.status === 'in_progress') : undefined)

function isActive(item: ChecklistItem): boolean {
  return props.running && item.status === 'in_progress'
}

function statusLabel(item: ChecklistItem): string {
  if (item.status === 'completed') return 'done'
  if (item.status === 'pending') return 'to do'
  return props.running ? 'in progress' : 'not finished'
}
</script>

<template>
  <section
    data-testid="chat-checklist"
    class="mx-auto w-full max-w-3xl px-4 pb-2"
  >
    <div class="rounded-lg border border-border bg-surface-elevated overflow-hidden text-sm">
      <button
        type="button"
        data-testid="chat-checklist-toggle"
        class="flex w-full min-w-0 items-center gap-2 px-3 py-2 text-left text-fg-muted hover:text-fg-strong
               focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-current"
        :aria-expanded="open"
        :aria-controls="open ? 'chat-checklist-items' : undefined"
        @click="open = !open"
      >
        <ChevronDownIcon
          class="w-3.5 h-3.5 shrink-0 transition-transform motion-reduce:transition-none"
          :class="{ '-rotate-90': !open }"
          aria-hidden="true"
        />
        <span class="font-medium text-fg-strong">Checklist</span>
        <span
          data-testid="chat-checklist-count"
          class="text-xs shrink-0"
        >{{ doneCount }} of {{ items.length }} done</span>
        <span
          v-if="!open && current"
          class="min-w-0 truncate text-xs"
        >· {{ current.activeForm }}</span>
      </button>
      <ul
        v-if="open"
        id="chat-checklist-items"
        class="border-t border-border px-3 py-2 space-y-1 max-h-48 overflow-y-auto"
      >
        <li
          v-for="(item, i) in items"
          :key="i"
          data-testid="chat-checklist-item"
          :data-status="item.status"
          class="flex items-start gap-2"
        >
          <span
            class="flex w-4 h-5 shrink-0 items-center justify-center"
            aria-hidden="true"
          >
            <CheckCircleIcon
              v-if="item.status === 'completed'"
              class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
            />
            <span
              v-else-if="isActive(item)"
              class="w-3.5 h-3.5 rounded-full border-2 border-current border-t-transparent text-blue-700 dark:text-blue-300
                     animate-spin motion-reduce:animate-none"
            />
            <span
              v-else
              class="w-3.5 h-3.5 rounded-full border border-current text-fg-muted"
            />
          </span>
          <span
            class="min-w-0"
            :class="{
              'text-fg-muted line-through': item.status === 'completed',
              'font-medium text-fg-strong': isActive(item),
              'text-fg-primary': item.status !== 'completed' && !isActive(item),
            }"
          >{{ isActive(item) ? item.activeForm : item.content }}</span>
          <span class="sr-only">({{ statusLabel(item) }})</span>
        </li>
      </ul>
    </div>
  </section>
</template>
