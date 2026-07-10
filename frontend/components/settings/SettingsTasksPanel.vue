<script setup lang="ts">
// Tasks settings panel (JCLAW-259, moved in JCLAW-680). Retention TTL for
// terminal tasks plus the default IANA timezone for CRON / SCHEDULED tasks.
// Moved verbatim from pages/settings.vue. Owns its own (Nuxt-deduped)
// /api/timezones fetch — the same URL the General panel fetches.
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

// ──────────────────── Tasks retention (JCLAW-259) ───────────────────────
// tasks.retentionDays controls TaskCleanupJob's TTL sweep. Default '30';
// '0' (or blank) means "never auto-delete". Mirrors the subagent.* editing
// pattern — same managed-config-key shape, same edit/save/cancel triple.
const tasksRetentionDays = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find(e => e.key === 'tasks.retentionDays')?.value ?? '30'
})

// JCLAW-261: tasks.defaultTimezone is the global default applied to
// CRON / SCHEDULED tasks that don't carry their own per-task timezone.
// When absent from the Config table, the backend falls back to
// application.conf and ultimately ZoneId.systemDefault() — we display
// the effective resolved value from GET /api/timezones rather than
// guess a fallback here, so the UI matches what the scheduler actually
// uses at fire time.
// `default` = effective task-scheduling zone; `appDefault` = effective
// operator wall-clock zone. Both come resolved from the backend so the UI
// never re-implements the fallback chain.
interface TimezonesPayload { timezones: string[], default: string, appDefault: string }
const timezonesPayload = await useFetch<TimezonesPayload>('/api/timezones', {
  default: () => ({ timezones: [], default: 'UTC', appDefault: 'UTC' }),
})
const tasksDefaultTimezone = computed(() => {
  const entries = configData.value?.entries ?? []
  const stored = entries.find(e => e.key === 'tasks.defaultTimezone')?.value
  return stored ?? timezonesPayload.data.value?.default ?? 'UTC'
})

const editingTasksField = ref<string | null>(null)
const tasksFieldEdit = ref('')

async function saveTasksField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingTasksField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- Tasks (JCLAW-259) -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Tasks
    </h2>
    <p class="text-xs text-fg-muted">
      Retention TTL for terminal tasks. <span class="font-mono">TaskCleanupJob</span>
      runs every 24 hours and hard-deletes tasks in
      <span class="font-mono">COMPLETED</span> / <span class="font-mono">FAILED</span> /
      <span class="font-mono">CANCELLED</span> / <span class="font-mono">LOST</span>
      whose <span class="font-mono">updatedAt</span> predates the cutoff, along with
      their full run history (TaskRunMessage → TaskRun → Task). Active tasks
      (<span class="font-mono">PENDING</span> / <span class="font-mono">ACTIVE</span> /
      <span class="font-mono">RUNNING</span>) are never touched. Set to
      <span class="font-mono">0</span> to disable auto-cleanup entirely
      (tasks retained forever).
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            retentionDays
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                Days a terminal task stays in the DB before TaskCleanupJob deletes it. 0 = retention disabled. Max 3650 (≈10 years).
              </span>
            </span>
          </span>
          <template v-if="editingTasksField === 'retentionDays'">
            <input
              v-model="tasksFieldEdit"
              type="number"
              min="0"
              max="3650"
              aria-label="Task retention days"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveTasksField('tasks.retentionDays', tasksFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingTasksField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ tasksRetentionDays }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingTasksField = 'retentionDays'; tasksFieldEdit = tasksRetentionDays"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- JCLAW-261: default IANA timezone for CRON / SCHEDULED tasks
             that don't carry their own. Saved to Config DB, which
             overrides application.conf at runtime. -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 flex items-center gap-1.5">
            defaultTimezone
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-[10px] text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                IANA timezone applied to CRON / SCHEDULED tasks that don't specify their own. Leave unset to follow the General timezone (your operator zone); set it only to run tasks in a different zone. Per-task `timezone` overrides this. INTERVAL / IMMEDIATE are duration-based and ignore timezone entirely.
              </span>
            </span>
          </span>
          <template v-if="editingTasksField === 'defaultTimezone'">
            <select
              v-model="tasksFieldEdit"
              aria-label="Default IANA timezone"
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
              @click="saveTasksField('tasks.defaultTimezone', tasksFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingTasksField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ tasksDefaultTimezone }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingTasksField = 'defaultTimezone'; tasksFieldEdit = tasksDefaultTimezone"
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
