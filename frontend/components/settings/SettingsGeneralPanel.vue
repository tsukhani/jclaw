<script setup lang="ts">
// General settings panel (JCLAW-680). Operator-wide settings; Timezone is the
// zone the assistant treats as "now" when its system prompt injects the current
// date/time. Defaults to the server's JVM zone; distinct from the Tasks default
// timezone (which governs CRON scheduling and defaults to UTC).
// Moved verbatim from pages/settings.vue. Owns its own (Nuxt-deduped)
// /api/timezones fetch — the same URL the Tasks panel fetches.
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

// `appDefault` = effective operator wall-clock zone (app.timezone chain → server
// JVM zone), resolved from the backend so the UI never re-implements the fallback
// chain.
interface TimezonesPayload { timezones: string[], default: string, appDefault: string }
const timezonesPayload = await useFetch<TimezonesPayload>('/api/timezones', {
  default: () => ({ timezones: [], default: 'UTC', appDefault: 'UTC' }),
})

// app.timezone — the operator's wall-clock zone the assistant treats as "now".
// Falls back to the backend-resolved appDefault (server JVM zone) when unset.
const appTimezone = computed(() => {
  const entries = configData.value?.entries ?? []
  const stored = entries.find(e => e.key === 'app.timezone')?.value
  return stored ?? timezonesPayload.data.value?.appDefault ?? 'UTC'
})

// General section (operator-wide settings). Separate edit state from Tasks so
// the two timezone controls don't share an "editing" flag.
const editingGeneralField = ref<string | null>(null)
const generalFieldEdit = ref('')

async function saveGeneralField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingGeneralField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- General: operator-wide settings. Timezone is the zone the assistant
       treats as "now" when its system prompt injects the current date/time.
       Defaults to the server's JVM zone; distinct from the Tasks default
       timezone (which governs CRON scheduling and defaults to UTC). -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Timezone
    </h2>
    <p class="text-xs text-fg-muted">
      Your timezone. The assistant uses this as the current date and time in
      every conversation, so it doesn't guess the clock. Defaults to the
      server's timezone; this is separate from the
      <span class="font-mono">Tasks</span> default timezone used for CRON
      scheduling.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            timezone
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                IANA timezone the assistant treats as the current wall-clock time in its system prompt. Defaults to the server's JVM zone when unset.
              </span>
            </span>
          </span>
          <template v-if="editingGeneralField === 'timezone'">
            <select
              v-model="generalFieldEdit"
              aria-label="Operator timezone"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
              <option
                v-for="z in timezonesPayload.data.value?.timezones ?? []"
                :key="z"
                :value="z"
              >
                {{ z }}
              </option>
            </select>
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveGeneralField('app.timezone', generalFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingGeneralField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ appTimezone }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingGeneralField = 'timezone'; generalFieldEdit = appTimezone"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
