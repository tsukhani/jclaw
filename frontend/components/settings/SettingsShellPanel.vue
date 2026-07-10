<script setup lang="ts">
// Shell Execution settings panel (JCLAW-680). Allowlist and timeout for the
// shell tool; per-agent enable/disable lives on the Tools page. Moved verbatim
// from pages/settings.vue. Self-contained on the shared config store.
import {
  CheckIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

// JCLAW-172: Browser (Playwright) and global Shell-Enabled toggles were
// removed. The browser tool is always headless; both tools register
// unconditionally and per-agent enable/disable lives on the Tools page.
// Shell allowlist + timeouts remain operator-tunable below.

// Shell execution config
const SHELL_KEYS = ['shell.allowlist', 'shell.defaultTimeoutSeconds', 'shell.maxTimeoutSeconds', 'shell.maxOutputBytes'] as const

const shellConfig = computed(() => {
  const entries = configData.value?.entries ?? []
  const map = new Map<string, string>()
  for (const e of entries) {
    if ((SHELL_KEYS as readonly string[]).includes(e.key)) {
      map.set(e.key, e.value)
    }
  }
  return {
    allowlist: map.get('shell.allowlist') ?? '',
    defaultTimeout: map.get('shell.defaultTimeoutSeconds') ?? '30',
    maxTimeout: map.get('shell.maxTimeoutSeconds') ?? '300',
    maxOutput: map.get('shell.maxOutputBytes') ?? '102400',
  }
})

const shellAllowlistEdit = ref('')
const shellTimeoutEdit = ref('')
const editingShellField = ref<string | null>(null)

function startShellEdit(field: string, value: string) {
  editingShellField.value = field
  if (field === 'allowlist') shellAllowlistEdit.value = value
  else shellTimeoutEdit.value = value
}

async function saveShellField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingShellField.value = null
    refresh()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- Shell Execution -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Shell Execution
    </h2>
    <p class="text-xs text-fg-muted">
      Allowlist and timeout for the shell tool. Per-agent enable/disable
      lives on the Tools page; this section configures the shared
      execution policy.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <!-- Allowlist -->
        <div class="px-4 py-2.5 flex items-start gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0 pt-0.5">allowlist</span>
          <template v-if="editingShellField === 'allowlist'">
            <textarea
              v-model="shellAllowlistEdit"
              rows="3"
              aria-label="Shell allowlist"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden resize-none"
            />
            <div class="flex flex-col gap-1">
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                @click="saveShellField('shell.allowlist', shellAllowlistEdit)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingShellField = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </div>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ shellConfig.allowlist || '(not set)' }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="startShellEdit('allowlist', shellConfig.allowlist)"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <!-- Default timeout -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">defaultTimeoutSeconds</span>
          <template v-if="editingShellField === 'timeout'">
            <input
              v-model="shellTimeoutEdit"
              type="number"
              min="1"
              max="300"
              aria-label="Shell default timeout seconds"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveShellField('shell.defaultTimeoutSeconds', shellTimeoutEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingShellField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ shellConfig.defaultTimeout }}s</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="startShellEdit('timeout', shellConfig.defaultTimeout)"
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
