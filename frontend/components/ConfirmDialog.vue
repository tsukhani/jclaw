<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const { _state: state, _resolve } = useConfirm()

const confirmBtn = ref<HTMLButtonElement | null>(null)
const lastFocus = ref<HTMLElement | null>(null)

function onCancel() {
  _resolve(false)
}
function onConfirm() {
  _resolve(true)
}

function onKeydown(e: KeyboardEvent) {
  if (!state.open) return
  if (e.key === 'Escape') {
    e.preventDefault()
    onCancel()
  }
  else if (e.key === 'Enter') {
    e.preventDefault()
    onConfirm()
  }
}

// Focus management: remember what had focus before, restore on close.
watch(() => state.open, async (open) => {
  if (open) {
    lastFocus.value = (document.activeElement as HTMLElement) ?? null
    await nextTick()
    confirmBtn.value?.focus()
  }
  else if (lastFocus.value) {
    lastFocus.value.focus()
    lastFocus.value = null
  }
})

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-150"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-100"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <!-- eslint-disable-next-line vuejs-accessibility/click-events-have-key-events, vuejs-accessibility/no-static-element-interactions -- modal backdrop; Escape is handled globally via document keydown listener -->
      <div
        v-if="state.open"
        class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
        role="dialog"
        aria-modal="true"
        @click.self="onCancel"
      >
        <div class="w-full max-w-md mx-4 bg-surface-elevated border border-border shadow-2xl">
          <div class="px-5 py-4 border-b border-border">
            <h2
              v-if="state.title"
              class="text-sm font-semibold text-fg-strong"
            >
              {{ state.title }}
            </h2>
            <h2
              v-else
              class="text-sm font-semibold text-fg-strong"
            >
              Confirm
            </h2>
          </div>

          <div class="px-5 py-4">
            <p class="text-xs text-fg-primary leading-relaxed whitespace-pre-line">
              {{ state.message }}
            </p>
          </div>

          <div class="px-5 py-3 border-t border-border flex items-center justify-end gap-2">
            <button
              type="button"
              class="px-3 py-1.5 text-xs text-fg-muted hover:text-fg-strong transition-colors"
              @click="onCancel"
            >
              {{ state.cancelText }}
            </button>
            <button
              ref="confirmBtn"
              type="button"
              :class="[
                'px-3 py-1.5 text-xs border transition-colors',
                state.variant === 'danger'
                  ? 'bg-red-50 dark:bg-red-950/40 border-red-200 dark:border-red-900/60 text-red-700 dark:text-red-300 hover:bg-red-100 dark:hover:bg-red-900/40 hover:text-red-800 dark:hover:text-red-200'
                  : 'bg-emerald-50 dark:bg-emerald-950/40 border-emerald-200 dark:border-emerald-900/60 text-emerald-700 dark:text-emerald-300 hover:bg-emerald-100 dark:hover:bg-emerald-900/40 hover:text-emerald-800 dark:hover:text-emerald-200',
              ]"
              @click="onConfirm"
            >
              {{ state.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
