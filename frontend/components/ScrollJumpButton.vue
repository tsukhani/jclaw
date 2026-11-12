<script lang="ts">
import { ArrowDownIcon, ArrowUpIcon } from '@heroicons/vue/24/outline'

/**
 * Scroll padding for a scroller that shows this pill, covering bottom-2 plus the pill's height: a link that Tab scrolls
 * up from below stops above the pill rather than behind it (WCAG 2.4.11, technique C43). Resize it with the pill.
 */
export const SCROLL_JUMP_CLEARANCE = 'scroll-pb-12'
</script>

<script setup lang="ts">
// Attributes and listeners land on the button, not the row: a caller's @click and data-testid address the control.
defineOptions({ inheritAttrs: false })

withDefaults(defineProps<{
  direction: 'up' | 'down'
  align?: 'center' | 'end'
}>(), { align: 'center' })
</script>

<template>
  <!--
  A zero-height sticky row holds the pill at its scroller's visible bottom without adding scroll height, so the caller
  renders it last in the scrolled content, which the row spans: centred on it, or at its right edge.
-->
  <div
    class="sticky bottom-2 h-0 flex items-start pointer-events-none"
    :class="align === 'end' ? 'justify-end' : 'justify-center'"
  >
    <!-- Cancelling the press keeps the button from taking focus, so a reader typing or reading keeps theirs. -->
    <button
      v-bind="$attrs"
      type="button"
      class="pointer-events-auto -translate-y-full inline-flex items-center gap-1.5 rounded-full
             border border-neutral-200 dark:border-neutral-700/50 bg-surface-elevated px-3 py-1.5
             text-xs font-medium text-fg-primary hover:bg-muted transition-colors
             shadow-[0_1px_2px_rgba(0,0,0,0.06),0_6px_14px_rgba(0,0,0,0.08)] dark:shadow-[0_4px_16px_#0000004d]
             focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current"
      @mousedown.prevent
    >
      <component
        :is="direction === 'up' ? ArrowUpIcon : ArrowDownIcon"
        class="w-3.5 h-3.5"
        aria-hidden="true"
      />
      <slot />
    </button>
  </div>
</template>
