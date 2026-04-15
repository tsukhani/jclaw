<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const { _state: state, _resolve } = useConfirm()

const confirmBtn = ref<HTMLButtonElement | null>(null)
const lastFocus = ref<HTMLElement | null>(null)

function onCancel() { _resolve(false) }
function onConfirm() { _resolve(true) }

function onKeydown(e: KeyboardEvent) {
  if (!state.open) return
  if (e.key === 'Escape') { e.preventDefault(); onCancel() }
  else if (e.key === 'Enter') { e.preventDefault(); onConfirm() }
}

// Focus management: remember what had focus before, restore on close.
watch(() => state.open, async (open) => {
  if (open) {
    lastFocus.value = (document.activeElement as HTMLElement) ?? null
    await nextTick()
    confirmBtn.value?.focus()
  } else if (lastFocus.value) {
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
      <div v-if="state.open"
           class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm"
           @click.self="onCancel"
           role="dialog"
           aria-modal="true">
        <div class="w-full max-w-md mx-4 bg-neutral-50 dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 shadow-2xl">
          <div class="px-5 py-4 border-b border-neutral-200 dark:border-neutral-800">
            <h2 v-if="state.title" class="text-sm font-semibold text-neutral-900 dark:text-white">{{ state.title }}</h2>
            <h2 v-else class="text-sm font-semibold text-neutral-900 dark:text-white">Confirm</h2>
          </div>

          <div class="px-5 py-4">
            <p class="text-xs text-neutral-700 dark:text-neutral-300 leading-relaxed whitespace-pre-line">{{ state.message }}</p>
          </div>

          <div class="px-5 py-3 border-t border-neutral-200 dark:border-neutral-800 flex items-center justify-end gap-2">
            <button type="button"
                    @click="onCancel"
                    class="px-3 py-1.5 text-xs text-neutral-600 dark:text-neutral-400 hover:text-neutral-900 dark:hover:text-white transition-colors">
              {{ state.cancelText }}
            </button>
            <button ref="confirmBtn"
                    type="button"
                    @click="onConfirm"
                    :class="[
                      'px-3 py-1.5 text-xs border transition-colors',
                      state.variant === 'danger'
                        ? 'bg-red-50 dark:bg-red-950/40 border-red-200 dark:border-red-900/60 text-red-700 dark:text-red-300 hover:bg-red-100 dark:hover:bg-red-900/40 hover:text-red-800 dark:hover:text-red-200'
                        : 'bg-emerald-50 dark:bg-emerald-950/40 border-emerald-200 dark:border-emerald-900/60 text-emerald-700 dark:text-emerald-300 hover:bg-emerald-100 dark:hover:bg-emerald-900/40 hover:text-emerald-800 dark:hover:text-emerald-200'
                    ]">
              {{ state.confirmText }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
