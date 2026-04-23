<script setup lang="ts">
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '~/components/ui/sheet'

withDefaults(defineProps<{
  open: boolean
  title?: string
  description?: string
}>(), {
  title: 'Details',
  description: '',
})

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

const MIN_WIDTH = 400
const panelWidth = ref(Math.round(window.innerWidth * 0.45))
const isResizing = ref(false)

function clampWidth(w: number): number {
  const maxW = Math.round(window.innerWidth * 0.6)
  return Math.max(MIN_WIDTH, Math.min(w, maxW))
}

function onResizeStart(e: MouseEvent) {
  e.preventDefault()
  isResizing.value = true
  const startX = e.clientX
  const startWidth = panelWidth.value

  function onMove(ev: MouseEvent) {
    // Panel is on the right, so moving left increases width
    panelWidth.value = clampWidth(startWidth + (startX - ev.clientX))
  }

  function onUp() {
    isResizing.value = false
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
  }

  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}
</script>

<template>
  <Sheet
    :open="open"
    @update:open="emit('update:open', $event)"
  >
    <SheetContent
      side="right"
      :class="['max-w-none', isResizing ? 'select-none' : '']"
      :style="{ width: `${panelWidth}px` }"
    >
      <!-- Resize handle -->
      <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- pointer-only resize affordance; panel width also has sensible default and can be adjusted by dragging -->
      <div
        class="absolute inset-y-0 left-0 w-1.5 cursor-col-resize hover:bg-emerald-500/30 active:bg-emerald-500/50 transition-colors z-10"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize panel"
        @mousedown="onResizeStart"
      />

      <!--
        The SheetContent primitive renders its own X close button in the
        top-right corner, so pr-10 reserves room for it and the header
        only carries the title + description.
      -->
      <SheetHeader class="pr-10">
        <SheetTitle class="truncate">
          {{ title }}
        </SheetTitle>
        <SheetDescription v-if="description">
          {{ description }}
        </SheetDescription>
      </SheetHeader>

      <!-- Content slot -->
      <div class="flex-1 overflow-y-auto px-6 pb-6">
        <slot />
      </div>
    </SheetContent>
  </Sheet>
</template>
