<script setup lang="ts">
// Software Factory setup (JCLAW-1393): prerequisites, write-only credentials and the installer job.
// The API answers only whether each credential is set, so no field here can show a stored value.
interface Prerequisite {
  id: string
  label: string
  state: 'ok' | 'missing' | 'unknown'
  fix: string
}

interface FactorySetupView {
  installed: boolean
  prerequisites: Prerequisite[]
  hasModelCredential: boolean
  hasJira: boolean
  hasGithub: boolean
  installJobId: string | null
  message: string | null
}

interface InstallJob {
  id: string
  state: 'running' | 'succeeded' | 'failed'
  elapsedMillis: number
  exitCode: number | null
  timedOut: boolean
  truncated: boolean
  output: string
}

type ModelField = 'claudeOauthToken' | 'anthropicApiKey'

const INPUT = 'w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong focus:outline-hidden'
const BUTTON = 'px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50'
const STATE_BADGE: Record<Prerequisite['state'], string> = {
  ok: 'text-green-700 dark:text-green-400 border-green-400/30',
  missing: 'text-rose-700 dark:text-rose-400 border-rose-400/30',
  unknown: 'text-amber-800 dark:text-amber-400 border-amber-400/30',
}

const { data: setup, refresh } = useLazyFetch<FactorySetupView>('/api/factory/setup')

const needsSetup = computed(() => {
  const s = setup.value
  if (!s) return false
  return !s.installed || s.prerequisites.some(p => p.state !== 'ok') || !s.hasModelCredential || !s.hasJira
})
const opened = ref(false)
const expanded = computed(() => needsSetup.value || opened.value)

// --- credentials ---
const modelField = ref<ModelField>('claudeOauthToken')
const modelValue = ref('')
const jiraUrl = ref('')
const jiraToken = ref('')
const githubToken = ref('')
const saved = ref('')
const { mutate: postCredentials, loading: savingCredentials, errorDetails: credentialsError } = useApiMutation()

const anyFilled = computed(() =>
  [modelValue.value, jiraUrl.value, jiraToken.value, githubToken.value].some(v => v.trim() !== ''))

async function saveCredentials() {
  const body: Record<string, string> = {}
  if (modelValue.value.trim()) body[modelField.value] = modelValue.value
  if (jiraUrl.value.trim()) body.jiraUrl = jiraUrl.value
  if (jiraToken.value.trim()) body.jiraPersonalToken = jiraToken.value
  if (githubToken.value.trim()) body.githubToken = githubToken.value
  if (Object.keys(body).length === 0) return
  saved.value = ''
  const res = await postCredentials<FactorySetupView>('/api/factory/setup/credentials', { method: 'POST', body })
  if (res === null) return
  modelValue.value = ''
  jiraUrl.value = ''
  jiraToken.value = ''
  githubToken.value = ''
  saved.value = res.message ?? 'Credentials saved.'
  await refresh()
}

// --- installer ---
const job = ref<InstallJob | null>(null)
const { mutate: postInstall, errorDetails: installError } = useApiMutation()
const running = computed(() => job.value?.state === 'running')
let pollTimer: ReturnType<typeof setInterval> | null = null

async function poll() {
  const id = job.value?.id
  if (!id) return
  try {
    job.value = await $fetch<InstallJob>(`/api/factory/setup/install/${encodeURIComponent(id)}`)
  }
  catch (e) {
    // Jobs live in memory: a 404 means JClaw restarted or evicted it, so stop polling. Anything else is transient.
    if (apiErrorDetails(e).status === 404) job.value = null
  }
}

async function runInstaller() {
  const res = await postInstall<InstallJob>('/api/factory/setup/install', { method: 'POST' })
  if (res !== null) job.value = res
}

watch(running, (isRunning, wasRunning) => {
  if (isRunning && !pollTimer) pollTimer = setInterval(poll, 1500)
  if (!isRunning && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  if (wasRunning && !isRunning) refresh()
})

// Reopening Settings mid-install picks the job's output back up.
watch(() => setup.value?.installJobId, async (id) => {
  if (!id || job.value) return
  try {
    job.value = await $fetch<InstallJob>(`/api/factory/setup/install/${encodeURIComponent(id)}`)
  }
  catch { /* the job is gone: nothing to resume */ }
}, { immediate: true })

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <section
    class="space-y-3"
    data-testid="factory-setup"
  >
    <div class="flex items-center justify-between gap-3">
      <h3 class="text-sm font-medium text-fg-strong">
        Setup
      </h3>
      <button
        v-if="setup && !needsSetup"
        type="button"
        :class="BUTTON"
        :aria-expanded="expanded"
        data-testid="factory-setup-toggle"
        @click="opened = !opened"
      >
        {{ expanded ? 'Hide setup' : 'Setup' }}
      </button>
    </div>

    <div
      v-if="setup && expanded"
      class="space-y-4"
      data-testid="factory-setup-body"
    >
      <div class="bg-surface-elevated border border-border">
        <ul class="divide-y divide-border">
          <li
            v-for="p in setup.prerequisites"
            :key="p.id"
            class="px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3"
            :data-testid="`factory-prereq-${p.id}`"
          >
            <span class="text-sm text-fg-strong w-56 max-sm:w-full shrink-0">{{ p.label }}</span>
            <span
              class="text-xs border px-1"
              :class="STATE_BADGE[p.state]"
            >{{ p.state }}</span>
            <span
              v-if="p.fix"
              class="text-xs text-fg-muted"
            >{{ p.fix }}</span>
          </li>
        </ul>
      </div>

      <form
        class="bg-surface-elevated border border-border px-4 py-3 space-y-3"
        data-testid="factory-credentials"
        @submit.prevent="saveCredentials"
      >
        <p class="text-xs text-fg-muted">
          Stored in the factory home, readable only by you. A saved value is never shown; enter a new one to replace it.
        </p>
        <div class="grid gap-3 sm:grid-cols-[12rem_1fr] items-center">
          <select
            v-model="modelField"
            aria-label="Model credential kind"
            :class="INPUT"
            data-testid="factory-model-kind"
          >
            <option value="claudeOauthToken">
              Claude OAuth token
            </option>
            <option value="anthropicApiKey">
              Anthropic API key
            </option>
          </select>
          <SecretField
            v-model="modelValue"
            form
            :saved="setup.hasModelCredential"
            label="Model credential"
            input-id="factory-model-credential"
            :input-class="INPUT"
            data-testid="factory-model-credential"
          />

          <span class="text-xs text-fg-muted">Jira URL</span>
          <input
            id="factory-jira-url"
            v-model="jiraUrl"
            type="url"
            aria-label="Jira URL"
            autocomplete="off"
            spellcheck="false"
            :placeholder="setup.hasJira ? 'set — enter a new URL to replace it' : 'https://jira.example.com'"
            :class="INPUT"
            data-testid="factory-jira-url"
          >

          <span class="text-xs text-fg-muted">Jira personal token</span>
          <SecretField
            v-model="jiraToken"
            form
            :saved="setup.hasJira"
            label="Jira personal token"
            input-id="factory-jira-token"
            :input-class="INPUT"
            data-testid="factory-jira-token"
          />

          <span class="text-xs text-fg-muted">GitHub token (optional)</span>
          <SecretField
            v-model="githubToken"
            form
            :saved="setup.hasGithub"
            label="GitHub token"
            input-id="factory-github-token"
            :input-class="INPUT"
            data-testid="factory-github-token"
          />
        </div>
        <ApiErrorAlert :error="credentialsError" />
        <div class="flex items-center gap-3">
          <button
            type="submit"
            :class="BUTTON"
            :disabled="!anyFilled || savingCredentials"
            data-testid="factory-credentials-save"
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

      <div class="bg-surface-elevated border border-border px-4 py-3 space-y-3">
        <p
          class="text-xs text-fg-muted"
          data-testid="factory-install-note"
        >
          The installer restarts the factory: a story it is building is interrupted, keeps its branch and resumes on the next round.
        </p>
        <div class="flex items-center gap-3">
          <button
            type="button"
            :class="BUTTON"
            :disabled="running"
            data-testid="factory-install-run"
            @click="runInstaller"
          >
            Run installer
          </button>
          <span
            v-if="job"
            class="text-xs text-fg-muted"
            data-testid="factory-install-state"
          >{{ job.state }} · {{ Math.round(job.elapsedMillis / 1000) }}s<template v-if="job.exitCode !== null && job.state === 'failed'"> · exit code {{ job.exitCode }}</template><template v-if="job.timedOut"> · timed out</template></span>
        </div>
        <ApiErrorAlert :error="installError" />
        <pre
          v-if="job"
          class="max-h-80 overflow-auto text-xs font-mono bg-muted p-2 whitespace-pre-wrap break-words"
          data-testid="factory-install-output"
        ><template v-if="job.truncated">…earlier output truncated…
</template>{{ job.output }}</pre>
      </div>
    </div>
  </section>
</template>
