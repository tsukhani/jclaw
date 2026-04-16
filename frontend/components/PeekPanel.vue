<script setup lang="ts">
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from '~/components/ui/sheet'

const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  description?: string
  /** Full-page route to navigate to on Cmd+Enter */
  popOutRoute?: string
}>(), {
  title: 'Details',
  description: '',
})

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
  (e: 'next'): void
  (e: 'prev'): void
}>()

const router = useRouter()

// ── Resize state ────────────────────────────────────────────────────────────
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

// ── Keyboard shortcuts ──────────────────────────────────────────────────────
function handleKeydown(e: KeyboardEvent) {
  if (!props.open) return

  // Cmd+Enter → pop out to full page
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter' && props.popOutRoute) {
    e.preventDefault()
    emit('update:open', false)
    router.push(props.popOutRoute)
    return
  }

  // Arrow up/down → cycle items
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    emit('next')
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    emit('prev')
  }
}

onMounted(() => document.addEventListener('keydown', handleKeydown))
onUnmounted(() => document.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <Sheet :open="open" @update:open="emit('update:open', $event)">
    <SheetContent
      side="right"
      :class="['max-w-none', isResizing ? 'select-none' : '']"
      :style="{ width: `${panelWidth}px` }"
    >
      <!-- Resize handle -->
      <div
        class="absolute inset-y-0 left-0 w-1.5 cursor-col-resize hover:bg-emerald-500/30 active:bg-emerald-500/50 transition-colors z-10"
        @mousedown="onResizeStart"
      />

      <!-- Header -->
      <SheetHeader class="pr-10">
        <div class="flex items-center gap-2">
          <SheetTitle class="flex-1 truncate">{{ title }}</SheetTitle>
          <button
            v-if="popOutRoute"
            @click="emit('update:open', false); router.push(popOutRoute)"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors shrink-0"
            title="Open full page (Cmd+Enter)"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
            </svg>
          </button>
          <button
            @click="emit('update:open', false)"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors shrink-0"
            title="Close panel (Esc)"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 5l7 7-7 7M5 5l7 7-7 7" />
            </svg>
          </button>
        </div>
        <SheetDescription v-if="description">{{ description }}</SheetDescription>
      </SheetHeader>

      <!-- Content slot -->
      <div class="flex-1 overflow-y-auto px-6 pb-6">
        <slot />
      </div>
    </SheetContent>
  </Sheet>
</template>
