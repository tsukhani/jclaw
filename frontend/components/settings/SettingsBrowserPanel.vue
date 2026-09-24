<script setup lang="ts">
// Browser settings panel (JCLAW-1274). Picks the engine behind the browser tool for every
// agent: Playwright, where the agent's own model writes selectors and scripts, or TypeSafe's
// Jev, which takes a URL and a goal and chooses each step itself. Both keys are ordinary
// /api/config rows and neither is seeded, so an absent engine is Playwright.
import { CheckIcon, PencilIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { BrowserSetupStatus } from '~/composables/useBrowserSetup'

const { configData, saving, refresh, editingKey, editValue, editError, updateEntry } = useSettingsConfig()

const ENGINE_KEY = 'browser.engine'
const API_KEY = 'browser.jev.apiKey'

const engine = computed(() =>
  configData.value?.entries?.find(e => e.key === ENGINE_KEY)?.value || 'playwright',
)
// The radios' own selection, so a failed save can put it back; a one-way :checked never re-renders.
const chosenEngine = ref(engine.value)
watch(engine, (v) => {
  chosenEngine.value = v
})
const { saveError, attempt } = useSaveAttempt()

async function setEngine(value: string) {
  saving.value = true
  if (!await attempt(() => $fetch('/api/config', { method: 'POST', body: { key: ENGINE_KEY, value } }))) {
    chosenEngine.value = engine.value
  }
  refresh()
  saving.value = false
}

// The stored key comes back masked, so the editor starts blank rather than saving the mask back.
const keyConfigured = computed(() => {
  const v = configData.value?.entries?.find(e => e.key === API_KEY)?.value
  return !!v && v.trim().length > 0
})
function startEditKey() {
  editingKey.value = API_KEY
  editValue.value = ''
}
// The browser tool's driver (Node.js) and Chromium. A bundle install downloads them on the first
// browser call; downloading here spares that call the wait. Polls only while a setup runs.
const { status: setup, start: pollSetup } = useBrowserSetupPolling()
onMounted(() => pollSetup())
const { mutate: mutateSetup, errorDetails: setupStartError } = useApiMutation()

const DRIVER_LABELS: Record<BrowserSetupStatus['driverSource'], string> = {
  bundled: 'Included with this install',
  preinstalled: 'Provided by the environment',
  downloaded: 'Downloaded',
  missing: 'Not downloaded yet',
  unsupported: 'Not available on this platform',
}
const setupNeeded = computed(() => {
  const s = setup.value
  if (!s || s.driverSource === 'unsupported') return false
  return s.driverSource === 'missing' || !s.chromiumInstalled
})
async function downloadNow() {
  const s = await mutateSetup<BrowserSetupStatus>('/api/browser/setup', { method: 'POST' })
  if (s) {
    setup.value = s
    pollSetup()
  }
}

// Saving the editor untouched would store a blank key over the real one, so it cancels instead.
function saveKey() {
  if (!editValue.value.trim()) editingKey.value = null
  else updateEntry(API_KEY)
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Browser
    </h2>
    <p class="text-xs text-fg-muted">
      Choose what drives the <span class="font-mono">browser</span> tool for every agent. With
      Playwright, the agent's own model writes the selectors and scripts, one tool call per step.
      With Jev, the agent gives a URL and a goal, and TypeSafe AI's Jev model chooses each click,
      entry and selection itself; the agent's model still writes any text to be typed.
    </p>

    <fieldset class="min-w-0 bg-surface-elevated border border-border divide-y divide-border">
      <legend class="sr-only">
        Browser engine
      </legend>
      <label
        for="browser-engine-playwright"
        class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
      >
        <input
          id="browser-engine-playwright"
          v-model="chosenEngine"
          type="radio"
          name="browser-engine"
          value="playwright"
          :disabled="saving"
          class="accent-emerald-600"
          @change="setEngine('playwright')"
        >
        <span class="flex-1 text-sm text-fg-primary">Playwright</span>
        <span class="text-xs text-fg-muted">default</span>
      </label>
      <label
        for="browser-engine-jev"
        class="px-4 py-2.5 flex items-center gap-3 cursor-pointer"
      >
        <input
          id="browser-engine-jev"
          v-model="chosenEngine"
          type="radio"
          name="browser-engine"
          value="jev"
          :disabled="saving"
          class="accent-emerald-600"
          @change="setEngine('jev')"
        >
        <span class="flex-1 text-sm text-fg-primary">Jev (TypeSafe AI)</span>
      </label>
    </fieldset>
    <ApiErrorAlert :error="saveError" />

    <template v-if="chosenEngine === 'jev'">
      <div
        class="border border-amber-400/40 bg-amber-50/50 dark:bg-amber-900/10 px-3 py-2"
        data-testid="browser-jev-warning"
      >
        <p class="text-xs text-amber-800 dark:text-amber-300">
          On every step, Jev sends TypeSafe AI the goal, the page's address and title, its visible
          content (text, element labels and form values, but not hidden password fields) and the text
          typed earlier in the run. TypeSafe AI may record or retain them. Choose Playwright for pages
          whose content must not leave this instance.
        </p>
      </div>

      <div class="bg-surface-elevated border border-border">
        <div class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">TypeSafe API key</span>
          <template v-if="editingKey === API_KEY">
            <input
              v-model="editValue"
              type="password"
              autocomplete="new-password"
              aria-label="TypeSafe API key"
              placeholder="Your TypeSafe API key"
              class="flex-1 min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              :disabled="saving"
              @click="saveKey()"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingKey = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span
              class="flex-1 text-sm text-fg-primary font-mono truncate"
              data-testid="browser-jev-key"
            >{{ keyConfigured ? '••••••••' : '(not set)' }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              :title="keyConfigured ? 'Change key' : 'Set key'"
              aria-label="Edit TypeSafe API key"
              @click="startEditKey()"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <p
          v-if="!keyConfigured"
          class="px-4 pb-2.5 text-xs text-fg-muted"
        >
          Until a key is set, agents keep the Playwright actions.
        </p>
        <ApiErrorAlert
          v-if="editingKey === API_KEY"
          :error="editError"
          class="px-4 pb-2.5"
        />
      </div>
    </template>

    <section
      class="space-y-2"
      aria-labelledby="browser-components-heading"
    >
      <h3
        id="browser-components-heading"
        class="text-xs font-medium text-fg-muted"
      >
        Browser components
      </h3>
      <p class="text-xs text-fg-muted">
        The browser tool runs on a driver and Chromium installed on this machine. Any that are missing
        download the first time an agent uses the browser, delaying that reply by a few minutes.
        Download them now to skip the wait.
      </p>
      <div class="bg-surface-elevated border border-border divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="flex-1 text-sm text-fg-primary">
            Driver <span class="text-xs text-fg-muted">Node.js {{ setup?.nodeVersion ?? '' }}</span>
          </span>
          <span
            class="text-xs text-fg-muted"
            data-testid="browser-driver-state"
          >{{ setup ? DRIVER_LABELS[setup.driverSource] : '—' }}</span>
        </div>
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="flex-1 text-sm text-fg-primary">Chromium</span>
          <span
            class="text-xs text-fg-muted"
            data-testid="browser-chromium-state"
          >{{ setup ? (setup.chromiumInstalled ? 'Installed' : 'Not downloaded yet') : '—' }}</span>
        </div>
        <div
          v-if="setup?.active"
          class="px-4 py-2.5 flex items-center gap-3"
          data-testid="browser-setup-progress"
        >
          <span class="flex-1 text-xs text-fg-strong">{{ setup.step ?? 'Preparing' }}…</span>
          <div
            class="w-32 h-2 bg-muted border border-input overflow-hidden"
            role="progressbar"
            aria-label="Browser setup progress"
            :aria-valuenow="setup.percent ?? undefined"
            aria-valuemin="0"
            aria-valuemax="100"
          >
            <div
              class="h-full bg-emerald-600 transition-[width] duration-300"
              :style="{ width: (setup.percent ?? 0) + '%' }"
            />
          </div>
          <span class="text-xs font-mono text-fg-muted tabular-nums w-10 text-right">
            {{ setup.percent != null ? setup.percent + '%' : '' }}
          </span>
        </div>
        <div
          v-else-if="setupNeeded"
          class="px-4 py-2.5 flex items-center justify-end"
        >
          <button
            type="button"
            class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors"
            data-testid="browser-setup-download"
            @click="downloadNow"
          >
            Download now
          </button>
        </div>
        <p
          v-if="setup?.error && !setup.active"
          class="px-4 py-2.5 text-xs text-red-700 dark:text-red-400"
          role="alert"
          data-testid="browser-setup-error"
        >
          {{ setup.error }}
        </p>
      </div>
      <ApiErrorAlert :error="setupStartError" />
    </section>
  </div>
</template>
