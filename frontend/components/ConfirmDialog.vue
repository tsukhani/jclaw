<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const { _state: state, _resolve } = useConfirm()

const confirmBtn = ref<HTMLButtonElement | null>(null)
const requireInput = ref<HTMLInputElement | null>(null)
const lastFocus = ref<HTMLElement | null>(null)

// Typed text the user has entered for the requireText gate. Reset on each
// open so a previous dialog's input doesn't leak forward.
const requireValue = ref('')

// A11y: bind <label for=> to <input id=> so the label-has-for /
// form-control-has-label rules pass and assistive tech announces the
// "Type 'delete' to confirm" prompt as part of the input.
const requireInputId = useId()

const requireSatisfied = computed(() => {
  if (!state.requireText) return true
  return requireValue.value.trim() === state.requireText
})

function onCancel() {
  _resolve(false)
}
function onConfirm() {
  if (!requireSatisfied.value) return
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
    requireValue.value = ''
    await nextTick()
    // When a typed-text gate is active, focus the input instead of the
    // confirm button — the button is disabled until the user types, so
    // landing focus on it produces a confusing "I can't tab into the gate"
    // moment. The input keeps Enter routing to onConfirm via the global
    // keydown listener.
    if (state.requireText) requireInput.value?.focus()
    else confirmBtn.value?.focus()
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
      <!-- NOSONAR(Web:S6819) — modal needs role="dialog" + aria-modal for screen readers; the HTML <dialog> element has incompatible open/close semantics with Vue Teleport + Transition. -->
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
            <label
              v-if="state.requireText"
              :for="requireInputId"
              class="block mt-3"
            >
              <span class="block text-[11px] text-fg-muted mb-1">
                Type <code class="font-mono text-fg-strong">{{ state.requireText }}</code> to confirm
              </span>
              <input
                :id="requireInputId"
                ref="requireInput"
                v-model="requireValue"
                type="text"
                autocomplete="off"
                class="w-full px-2 py-1.5 text-xs font-mono bg-surface border border-border focus:border-ring focus:outline-none text-fg-primary"
              >
            </label>
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
              :disabled="!requireSatisfied"
              :class="[
                'px-3 py-1.5 text-xs border transition-colors disabled:opacity-40 disabled:cursor-not-allowed',
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
