<script setup lang="ts">
import { InformationCircleIcon } from '@heroicons/vue/24/outline'
import { TooltipContent, TooltipPortal, TooltipProvider, TooltipRoot, TooltipTrigger } from 'reka-ui'

// Reka supplies what the CSS hover tips lacked: focus opens, aria-describedby, a hoverable gap, Esc dismisses (WCAG 1.4.13).
withDefaults(defineProps<{
  label?: string
  contentClass?: string
  triggerClass?: string
  align?: 'start' | 'center' | 'end'
}>(), {
  label: 'More information',
  contentClass: 'w-64',
  triggerClass: '-m-1.5 p-1.5',
  align: 'start',
})
</script>

<template>
  <TooltipProvider :delay-duration="100">
    <TooltipRoot>
      <TooltipTrigger as-child>
        <button
          type="button"
          :aria-label="label"
          class="inline-flex items-center justify-center rounded-full text-fg-muted cursor-help"
          :class="triggerClass"
        >
          <slot name="icon">
            <InformationCircleIcon
              class="w-3 h-3"
              aria-hidden="true"
            />
          </slot>
        </button>
      </TooltipTrigger>
      <TooltipPortal>
        <TooltipContent
          side="bottom"
          :align="align"
          :side-offset="4"
          :collision-padding="8"
          class="z-50 px-2.5 py-2 bg-muted border border-input text-xs text-fg-muted leading-relaxed shadow-xl"
          :class="contentClass"
        >
          <slot />
        </TooltipContent>
      </TooltipPortal>
    </TooltipRoot>
  </TooltipProvider>
</template>
