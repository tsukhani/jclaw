<script setup lang="ts">
/**
 * Reusable callout chip for the in-app User Guide. Three variants pulled
 * from the JCLAW-291 truncation marker palette so the guide reuses the
 * same visual vocabulary the chat surface already taught the operator.
 *
 *   tip       — emerald, "do this" guidance
 *   gotcha    — amber,   "watch out for this"
 *   note      — neutral, "here is some context"
 *
 * Title is optional. Body is rendered via the default slot so callers can
 * embed inline links / code without props plumbing.
 */
import { computed } from 'vue'
import {
  CheckCircleIcon,
  ExclamationTriangleIcon,
  InformationCircleIcon,
} from '@heroicons/vue/24/outline'

interface Props {
  variant?: 'tip' | 'gotcha' | 'note'
  title?: string
}

const props = withDefaults(defineProps<Props>(), { variant: 'note', title: undefined })

const icon = computed(() => {
  if (props.variant === 'tip') return CheckCircleIcon
  if (props.variant === 'gotcha') return ExclamationTriangleIcon
  return InformationCircleIcon
})

const wrapperClass = computed(() => {
  if (props.variant === 'tip') {
    return 'border-emerald-200 dark:border-emerald-900/50 bg-emerald-50/50 dark:bg-emerald-950/20'
  }
  if (props.variant === 'gotcha') {
    return 'border-amber-200 dark:border-amber-900/50 bg-amber-50/50 dark:bg-amber-950/20'
  }
  return 'border-neutral-200 dark:border-neutral-700 bg-surface-elevated'
})

const accentText = computed(() => {
  if (props.variant === 'tip') return 'text-emerald-700 dark:text-emerald-400'
  if (props.variant === 'gotcha') return 'text-amber-700 dark:text-amber-400'
  return 'text-fg-muted'
})

const defaultTitle = computed(() => {
  if (props.variant === 'tip') return 'Tip'
  if (props.variant === 'gotcha') return 'Heads up'
  return 'Note'
})
</script>

<template>
  <div
    :class="['flex gap-2.5 items-start px-3 py-2.5 my-3 rounded-lg border', wrapperClass]"
    :data-testid="`guide-callout-${variant}`"
  >
    <component
      :is="icon"
      :class="['w-4 h-4 shrink-0 mt-0.5', accentText]"
      aria-hidden="true"
    />
    <div class="text-sm text-fg-strong space-y-1 min-w-0">
      <div :class="['text-xs font-medium uppercase tracking-wide', accentText]">
        {{ title ?? defaultTitle }}
      </div>
      <div class="prose-chat">
        <slot />
      </div>
    </div>
  </div>
</template>
