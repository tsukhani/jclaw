<script setup lang="ts">
import { FACTORY_BUTTON, FACTORY_INPUT, type SettingsView } from './factory-board'

const LABELS: Record<string, string> = {
  FACTORY_MAX_PARALLEL: 'Stories built at once',
  FACTORY_CPUS: 'CPUs per sandbox',
  FACTORY_POLL_SECONDS: 'Poll interval (seconds)',
  FACTORY_MODEL: 'Model',
}
// The server's rules (FactoryHome): integers are digits and at least 1, the model is one word.
const INTEGER_KEYS = new Set(['FACTORY_MAX_PARALLEL', 'FACTORY_CPUS', 'FACTORY_POLL_SECONDS'])

const { data, refresh } = useLazyFetch<SettingsView>('/api/factory/settings')
const { mutate, loading: saving, errorDetails } = useApiMutation()

const drafts = ref<Record<string, string>>({})
const saved = ref('')

watch(data, (view) => {
  drafts.value = Object.fromEntries((view?.settings ?? []).map(e => [e.key, e.value]))
}, { immediate: true })

function invalid(key: string, value: string): string | null {
  if (INTEGER_KEYS.has(key)) {
    return /^\d+$/.test(value.trim()) && Number(value.trim()) >= 1 ? null : 'A whole number, 1 or more.'
  }
  return /^\S+$/.test(value.trim()) ? null : 'A model name with no spaces.'
}

const errors = computed(() => Object.fromEntries(
  Object.entries(drafts.value).map(([k, v]) => [k, invalid(k, v)])))

const changed = computed(() => {
  const body: Record<string, string> = {}
  for (const e of data.value?.settings ?? []) {
    const draft = drafts.value[e.key]
    if (draft !== undefined && draft.trim() !== e.value) body[e.key] = draft.trim()
  }
  return body
})

const canSave = computed(() =>
  !saving.value && Object.keys(changed.value).length > 0 && Object.values(errors.value).every(e => e === null))

function onInput(key: string, event: Event) {
  saved.value = ''
  drafts.value = { ...drafts.value, [key]: (event.target as HTMLInputElement).value }
}

async function save() {
  if (!canSave.value) return
  saved.value = ''
  const res = await mutate<SettingsView>('/api/factory/settings', { method: 'PUT', body: changed.value })
  if (res === null) return
  saved.value = res.message ?? 'Settings saved.'
  await refresh()
}
</script>

<template>
  <form
    class="space-y-3"
    data-testid="factory-settings"
    @submit.prevent="save"
  >
    <h3 class="text-sm font-medium text-fg-strong">
      Settings
    </h3>
    <p class="text-xs text-fg-muted">
      Changes apply once the factory is idle.
    </p>
    <div
      v-if="data"
      class="bg-surface-elevated border border-border divide-y divide-border"
    >
      <div
        v-for="e in data.settings"
        :key="e.key"
        class="px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3"
      >
        <span class="text-sm text-fg-strong w-56 max-sm:w-full shrink-0 pt-1">{{ LABELS[e.key] ?? e.key }}</span>
        <div class="flex-1 space-y-1">
          <input
            :aria-label="LABELS[e.key] ?? e.key"
            :type="INTEGER_KEYS.has(e.key) ? 'number' : 'text'"
            :min="INTEGER_KEYS.has(e.key) ? 1 : undefined"
            :value="drafts[e.key] ?? ''"
            :class="FACTORY_INPUT"
            :aria-invalid="errors[e.key] !== null"
            :data-testid="`factory-setting-${e.key}`"
            autocomplete="off"
            spellcheck="false"
            @input="onInput(e.key, $event)"
          >
          <p class="text-xs text-fg-muted">
            Default {{ e.defaultValue }}
          </p>
          <p
            v-if="errors[e.key]"
            class="text-xs text-danger"
            :data-testid="`factory-setting-error-${e.key}`"
          >
            {{ errors[e.key] }}
          </p>
        </div>
      </div>
    </div>
    <ApiErrorAlert :error="errorDetails" />
    <div class="flex items-center gap-3">
      <button
        type="submit"
        :class="FACTORY_BUTTON"
        :disabled="!canSave"
        data-testid="factory-settings-save"
      >
        Save
      </button>
      <span
        v-if="saved"
        class="text-xs text-fg-muted"
        role="status"
      >{{ saved }}</span>
    </div>
  </form>
</template>
