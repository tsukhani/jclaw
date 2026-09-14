<script setup lang="ts">
// One editable Config DB row: a number, text or select value, or an on/off toggle.
// A refused write (403) is shown under the row with the backend's reason.
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const props = withDefaults(defineProps<{
  configKey: string
  label: string
  kind: 'number' | 'text' | 'select' | 'boolean'
  /** Shown while the key is unset — the default the backend applies. */
  fallback: string
  tip: string
  min?: number
  max?: number
  options?: { value: string, label: string }[]
  labelWidth?: string
}>(), {
  min: undefined,
  max: undefined,
  options: () => [],
  labelWidth: 'w-56',
})

const { configData, saving, refresh } = useSettingsConfig()

const value = computed(() =>
  configData.value?.entries.find(e => e.key === props.configKey)?.value ?? props.fallback)
// The backend readers treat anything but "false" as on.
const isOn = computed(() => value.value.trim().toLowerCase() !== 'false')

const editing = ref(false)
// v-model on a type="number" input yields a number once the text parses.
const draft = ref<string | number>('')
const error = ref<string | null>(null)

function startEdit() {
  draft.value = value.value
  error.value = null
  editing.value = true
}

function cancel() {
  editing.value = false
  error.value = null
}

function messageOf(e: unknown): string {
  // A refused write is 403 {type, code, message}, where message is setWithSideEffects' rejection.
  const data = (e as { data?: { message?: string } })?.data
  return data?.message ?? (e instanceof Error ? e.message : 'Save failed')
}

async function save(next: string) {
  saving.value = true
  error.value = null
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: props.configKey, value: next.trim() } })
    editing.value = false
    await refresh()
  }
  catch (e) {
    error.value = messageOf(e)
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div :data-testid="`config-field-${configKey}`">
    <div class="px-4 py-2.5 flex items-center gap-3">
      <span
        class="text-xs font-mono text-fg-muted shrink-0 flex items-center gap-1.5"
        :class="labelWidth"
      >
        {{ label }}
        <span class="relative group/tip">
          <InformationCircleIcon
            class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
            aria-hidden="true"
          />
          <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-xs text-fg-muted leading-relaxed shadow-xl pointer-events-none">
            {{ tip }}
          </span>
        </span>
      </span>
      <template v-if="kind === 'boolean'">
        <button
          type="button"
          :aria-pressed="isOn"
          :aria-label="label"
          :disabled="saving"
          :class="isOn ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
          class="relative w-9 h-5 rounded-full transition-colors"
          @click="save(isOn ? 'false' : 'true')"
        >
          <span
            :class="isOn ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
        <span class="ml-auto text-[11px] text-fg-muted">
          {{ isOn ? 'on' : 'off' }}
        </span>
      </template>
      <!-- min-w-0: a select's content width is its widest option, so flex-1 alone overflows the row. -->
      <select
        v-else-if="kind === 'select'"
        :value="value"
        :aria-label="label"
        :disabled="saving"
        class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
        @change="save(($event.target as HTMLSelectElement).value)"
      >
        <option
          v-for="o in options"
          :key="o.value"
          :value="o.value"
        >
          {{ o.label }}
        </option>
      </select>
      <template v-else-if="editing">
        <input
          v-model="draft"
          :type="kind === 'number' ? 'number' : 'text'"
          :min="min"
          :max="max"
          :aria-label="label"
          class="w-40 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
        >
        <button
          class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
          title="Save"
          :disabled="saving"
          @click="save(String(draft))"
        >
          <CheckIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
        <button
          class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
          title="Cancel"
          @click="cancel"
        >
          <XMarkIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
      </template>
      <template v-else>
        <span class="flex-1 min-w-0 truncate text-sm text-fg-primary font-mono">{{ value }}</span>
        <button
          class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
          title="Edit"
          @click="startEdit"
        >
          <PencilIcon
            class="w-3.5 h-3.5"
            aria-hidden="true"
          />
        </button>
      </template>
    </div>
    <p
      v-if="error"
      class="px-4 pb-2.5 text-xs text-red-700 dark:text-red-400"
      role="alert"
    >
      {{ error }}
    </p>
  </div>
</template>
