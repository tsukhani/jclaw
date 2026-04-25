<script setup lang="ts">
import type { DialogContentEmits, DialogContentProps } from "reka-ui"
import type { HTMLAttributes } from "vue"
import { reactiveOmit } from "@vueuse/core"
// JCLAW-174 (follow-up #2): the panel is a side-sheet that slides in
// from the right edge. The close affordance reads as "push the panel
// back to the right" rather than "destroy" — so the chevron-double-
// right matches that physical metaphor better than an X. The Dialog
// primitives (DialogContent / DialogScrollContent) keep the X since
// modals genuinely close (no edge to slide back to).
import { ChevronDoubleRightIcon } from "@heroicons/vue/24/outline"
import {
  DialogClose,
  DialogContent,
  DialogPortal,
  useForwardPropsEmits,
} from "reka-ui"
import { cn } from "~/composables/ui-utils"
import SheetOverlay from "./SheetOverlay.vue"

interface SheetContentProps extends DialogContentProps {
  class?: HTMLAttributes["class"]
  side?: "top" | "right" | "bottom" | "left"
}

defineOptions({
  inheritAttrs: false,
})

const props = withDefaults(defineProps<SheetContentProps>(), {
  side: "right",
})
const emits = defineEmits<DialogContentEmits>()

const delegatedProps = reactiveOmit(props, "class", "side")

const forwarded = useForwardPropsEmits(delegatedProps, emits)
</script>

<template>
  <DialogPortal>
    <SheetOverlay />
    <DialogContent
      data-slot="sheet-content"
      :class="cn(
        // JCLAW-174 (follow-up #3): text-foreground pairs with the bg-background
        // token here so descendants (including the chevron close icon, which
        // strokes with currentColor) get a theme-aware color. Without it, the
        // DialogContent is portaled outside the JClaw layout and inherits the
        // browser default black, which renders the close glyph invisible on
        // dark backgrounds.
        'bg-background text-foreground data-[state=open]:animate-in data-[state=closed]:animate-out fixed z-50 flex flex-col gap-4 shadow-lg transition ease-in-out data-[state=closed]:duration-300 data-[state=open]:duration-500',
        side === 'right'
          && 'data-[state=closed]:slide-out-to-right data-[state=open]:slide-in-from-right inset-y-0 right-0 h-full w-3/4 border-l sm:max-w-sm',
        side === 'left'
          && 'data-[state=closed]:slide-out-to-left data-[state=open]:slide-in-from-left inset-y-0 left-0 h-full w-3/4 border-r sm:max-w-sm',
        side === 'top'
          && 'data-[state=closed]:slide-out-to-top data-[state=open]:slide-in-from-top inset-x-0 top-0 h-auto border-b',
        side === 'bottom'
          && 'data-[state=closed]:slide-out-to-bottom data-[state=open]:slide-in-from-bottom inset-x-0 bottom-0 h-auto border-t',
        props.class)"
      v-bind="{ ...$attrs, ...forwarded }"
      @open-auto-focus="(e) => e.preventDefault()"
    >
      <slot />

      <DialogClose
        class="ring-offset-background focus:ring-ring data-[state=open]:bg-secondary absolute top-4 right-4 rounded-xs opacity-70 transition-opacity hover:opacity-100 focus:ring-2 focus:ring-offset-2 focus:outline-hidden disabled:pointer-events-none"
      >
        <ChevronDoubleRightIcon class="size-4" />
        <span class="sr-only">Close</span>
      </DialogClose>
    </DialogContent>
  </DialogPortal>
</template>
