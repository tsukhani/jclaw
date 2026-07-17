<script setup lang="ts">
import { CheckIcon, ChevronDownIcon, ClipboardIcon } from '@heroicons/vue/24/outline'
import { LightBulbIcon as LightBulbIconSolid } from '@heroicons/vue/24/solid'
import { renderMarkdown } from '~/utils/chat-markdown'

// The Unsloth-style reasoning card: a "Thought for Xs" header (lightbulb +
// chevron) with an in-place Copy button, above the rendered reasoning body.
// All mutable inputs (collapse, copied flash, streaming HTML) arrive as
// parent-computed props so the card re-renders in lockstep with the
// shallowRef+triggerRef store and the streaming refs. The body v-html is kept
// inside the `v-if="!collapsed"` branch so renderMarkdown only runs when the
// card is actually expanded — exactly as the inline original did.
defineProps<{
  collapsed: boolean
  headerLabel: string
  copied: boolean
  reasoning: string
  agentId: number | null
  isStreaming: boolean
  streamHtml: string
}>()
const emit = defineEmits<{
  (e: 'toggle' | 'copy'): void
}>()
</script>

<template>
  <div class="mb-3 border border-neutral-200 dark:border-neutral-700 rounded-xl overflow-hidden bg-surface-elevated">
    <div class="flex items-center gap-2 px-3 py-2">
      <button
        type="button"
        class="flex-1 flex items-center gap-2 text-left text-xs text-fg-muted hover:text-fg-strong focus:outline-none"
        :title="collapsed ? 'Expand reasoning' : 'Collapse reasoning'"
        @click="emit('toggle')"
      >
        <LightBulbIconSolid
          class="w-3.5 h-3.5 shrink-0 text-amber-700 dark:text-amber-400 drop-shadow-[0_0_4px_rgba(251,191,36,0.55)]"
          aria-hidden="true"
        />
        <span class="font-medium">{{ headerLabel }}</span>
        <ChevronDownIcon
          class="w-3.5 h-3.5 transition-transform ml-1"
          :class="collapsed ? '' : 'rotate-180'"
          aria-hidden="true"
        />
      </button>
      <button
        type="button"
        class="shrink-0 inline-flex items-center gap-1 px-2 py-1 text-xs text-fg-muted hover:text-fg-strong transition-colors"
        :title="copied ? 'Copied' : 'Copy reasoning'"
        @click.stop="emit('copy')"
      >
        <ClipboardIcon
          v-if="!copied"
          class="w-3.5 h-3.5"
          aria-hidden="true"
        />
        <CheckIcon
          v-else
          class="w-3.5 h-3.5 text-emerald-700 dark:text-emerald-400"
          aria-hidden="true"
        />
        <span>Copy</span>
      </button>
    </div>
    <!-- eslint-disable vue/no-v-html -- streamHtml / renderMarkdown are DOMPurify-sanitised before returning. -->
    <div
      v-if="!collapsed"
      data-reasoning-body
      class="prose-chat px-4 pb-4 pt-3 text-sm text-fg-primary break-words max-h-80 overflow-y-auto border-t border-neutral-200 dark:border-neutral-700"
      v-html="isStreaming ? streamHtml : renderMarkdown(reasoning, agentId)"
    />
    <!-- eslint-enable vue/no-v-html -->
  </div>
</template>
