<script setup lang="ts">
// Shell Execution settings panel (JCLAW-680). Allowlist and timeout for the
// shell tool; per-agent enable/disable lives on the Tools page. Moved verbatim
// from pages/settings.vue. Self-contained on the shared config store.
import {
  ArrowPathIcon,
  CheckIcon,
  PencilIcon,
  StopIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

// JCLAW-172: Browser (Playwright) and global Shell-Enabled toggles were
// removed. The browser tool is always headless; both tools register
// unconditionally and per-agent enable/disable lives on the Tools page.
// Shell allowlist + timeouts remain operator-tunable below.

// Shell execution config
const SHELL_KEYS = ['shell.allowlist', 'shell.defaultTimeoutSeconds', 'shell.maxTimeoutSeconds', 'shell.maxOutputBytes', 'shell.sandbox', 'shell.backgroundLifetimeHours'] as const

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
    defaultTimeout: map.get('shell.defaultTimeoutSeconds') ?? '120',
    maxTimeout: map.get('shell.maxTimeoutSeconds') ?? '600',
    maxOutput: map.get('shell.maxOutputBytes') ?? '102400',
    sandbox: sandboxChoice(map.get('shell.sandbox')),
    lifetime: map.get('shell.backgroundLifetimeHours') ?? '24',
  }
})

// Normalized the way HarnessSandbox.scope reads it, so an unknown value shows Off, as it runs.
function sandboxChoice(raw: string | undefined): string {
  const v = (raw ?? '').trim().toLowerCase()
  return v === 'true' || v === 'untrusted' ? v : 'false'
}

// Lazy: the backend probes the sandbox binary, which must not suspend the panel.
const { data: sandboxData } = useLazyFetch<{ available: boolean, mechanism: string, reason: string }>('/api/sandbox')

const shellAllowlistEdit = ref('')
const shellTimeoutEdit = ref('')
const shellLifetimeEdit = ref('')
const editingShellField = ref<string | null>(null)

function startShellEdit(field: string, value: string) {
  editingShellField.value = field
  if (field === 'allowlist') shellAllowlistEdit.value = value
  else if (field === 'lifetime') shellLifetimeEdit.value = value
  else shellTimeoutEdit.value = value
}

const { saveError, attempt } = useSaveAttempt()

async function saveShellField(configKey: string, value: string) {
  saving.value = true
  if (await attempt(async () => {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
  })) {
    editingShellField.value = null
    refresh()
  }
  saving.value = false
}

// What agents' shell commands left running in the background (JCLAW-1565).
interface BackgroundProcess {
  pid: number
  agentName: string
  outputFile: string
  command: string
  started: string | null
  listening: string[]
}

// Lazy: listing runs lsof per workspace, which must not suspend the panel.
const { data: processes, pending: processesPending, refresh: refreshProcesses } = useLazyFetch<BackgroundProcess[]>('/api/processes')
const { mutate, errorDetails: stopError } = useApiMutation()
const stopping = ref<number | null>(null)

async function stopProcess(pid: number) {
  stopping.value = pid
  await mutate(`/api/processes/${pid}/stop`, { method: 'POST' })
  stopping.value = null
  await refreshProcesses()
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
      execution policy. <span class="font-mono">sandbox</span> confines what a
      command can write to the agent's workspace.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <!-- Allowlist -->
        <div class="px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 pt-0.5">allowlist</span>
          <template v-if="editingShellField === 'allowlist'">
            <textarea
              v-model="shellAllowlistEdit"
              rows="3"
              aria-label="Shell allowlist"
              class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden resize-none"
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
        <div class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">defaultTimeoutSeconds</span>
          <template v-if="editingShellField === 'timeout'">
            <input
              v-model="shellTimeoutEdit"
              type="number"
              min="1"
              :max="shellConfig.maxTimeout"
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
        <!-- Sandbox -->
        <div class="px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 flex items-center gap-1.5 pt-1">
            sandbox
            <InfoTip
              label="About sandbox"
              content-class="w-72 font-mono"
            >
              Confines what exec can write to the agent's workspace, and the audio ffmpeg runs to the temp directory, with the host's OS sandbox (sandbox-exec on macOS, bwrap on Linux). Untrusted channels only leaves the operator's own web chat unconfined.
            </InfoTip>
          </span>
          <div class="flex-1 min-w-0 space-y-1">
            <select
              :value="shellConfig.sandbox"
              aria-label="Shell sandbox"
              class="w-full px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              @change="saveShellField('shell.sandbox', ($event.target as HTMLSelectElement).value)"
            >
              <option value="false">
                Off
              </option>
              <option value="untrusted">
                Untrusted channels only
              </option>
              <option value="true">
                Every run
              </option>
            </select>
            <p
              v-if="sandboxData?.available === false"
              data-testid="sandbox-unavailable"
              class="text-xs text-amber-800 dark:text-amber-400"
            >
              This host has no sandbox mechanism ({{ sandboxData.reason }}). With this on, runs are refused rather than run unconfined.
            </p>
          </div>
        </div>
        <!-- Background lifetime -->
        <div class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 flex items-center gap-1.5">
            backgroundLifetimeHours
            <InfoTip
              label="About backgroundLifetimeHours"
              content-class="w-72"
            >
              How long a process a shell command left running in the background may run before JClaw stops it. 0 never stops one.
            </InfoTip>
          </span>
          <template v-if="editingShellField === 'lifetime'">
            <input
              v-model="shellLifetimeEdit"
              type="number"
              min="0"
              aria-label="Background process lifetime in hours"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="saveShellField('shell.backgroundLifetimeHours', shellLifetimeEdit)"
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
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ shellConfig.lifetime === '0' ? 'Never stopped' : `${shellConfig.lifetime}h` }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="startShellEdit('lifetime', shellConfig.lifetime)"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
      </div>
      <ApiErrorAlert
        :error="saveError"
        class="px-4 py-2.5 border-t border-border"
      />
    </div>

    <!-- Running in the background -->
    <div class="flex items-center justify-between gap-3">
      <h3 class="text-sm font-medium text-fg-muted">
        Running in the background
      </h3>
      <button
        class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
        title="Refresh"
        aria-label="Refresh background processes"
        @click="refreshProcesses()"
      >
        <ArrowPathIcon
          class="w-3.5 h-3.5"
          aria-hidden="true"
        />
      </button>
    </div>
    <div
      class="bg-surface-elevated border border-border"
      data-testid="background-processes"
    >
      <p
        v-if="processesPending && !processes"
        class="px-4 py-2.5 text-xs text-fg-muted"
      >
        Loading…
      </p>
      <p
        v-else-if="!processes?.length"
        class="px-4 py-2.5 text-xs text-fg-muted"
      >
        Nothing an agent started is running in the background.
      </p>
      <ul
        v-else
        class="divide-y divide-border"
      >
        <li
          v-for="proc in processes"
          :key="proc.pid"
          class="px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3"
        >
          <div class="flex-1 min-w-0 space-y-0.5">
            <p class="text-sm text-fg-primary font-mono truncate">
              {{ proc.command }}
            </p>
            <p class="text-xs text-fg-muted">
              {{ proc.agentName }} · pid {{ proc.pid }}<template v-if="proc.started">
                · started {{ formatDateTime(proc.started) }}
              </template>
            </p>
            <p
              v-if="proc.listening.length"
              class="text-xs font-mono"
              :class="proc.listening.some(a => a.startsWith('*:')) ? 'text-amber-800 dark:text-amber-400' : 'text-fg-muted'"
            >
              Listening on {{ proc.listening.join(', ') }}<template v-if="proc.listening.some(a => a.startsWith('*:'))">
                (every network interface)
              </template>
            </p>
            <p class="text-xs text-fg-muted font-mono truncate">
              {{ proc.outputFile }}
            </p>
          </div>
          <button
            class="px-2 py-1 text-xs border border-border text-fg-muted hover:text-fg-strong transition-colors flex items-center gap-1 disabled:opacity-50"
            :disabled="stopping === proc.pid"
            :data-testid="`stop-${proc.pid}`"
            @click="stopProcess(proc.pid)"
          >
            <StopIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
            Stop
          </button>
        </li>
      </ul>
      <ApiErrorAlert
        :error="stopError"
        class="px-4 py-2.5 border-t border-border"
      />
    </div>
  </div>
</template>
