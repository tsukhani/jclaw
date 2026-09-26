<script setup lang="ts">
// Browser settings panel (JCLAW-1274). Picks the engine behind the browser tool for every
// agent: Playwright, where the agent's own model writes selectors and scripts, or TypeSafe's
// Jev, which takes a URL and a goal and chooses each step itself. The engine is an ordinary
// /api/config row and is not seeded, so an absent engine is Playwright. The TypeSafe key lives in
// Decision Providers, because the Model Router's JEV classifier uses it too (JCLAW-1302).
import type { BrowserSetupStatus } from '~/composables/useBrowserSetup'
import { DRIVER_LABELS, browserSetupNeeded, chromiumLabel } from '~/utils/browser-setup'

const { configData, saving, refresh } = useSettingsConfig()

const ENGINE_KEY = 'browser.engine'

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

const keyConfigured = computed(() => {
  const v = configData.value?.entries?.find(e => e.key === 'decision.jev.apiKey')?.value
  return !!v && v.trim().length > 0
})

// The browser tool's driver (Node.js) and Chromium. A bundle install downloads them on the first
// browser call; downloading here spares that call the wait. Polls only while a setup runs.
const { status: setup, start: pollSetup } = useBrowserSetupPolling()
onMounted(() => pollSetup())
const { mutate: mutateSetup, errorDetails: setupStartError } = useApiMutation()

const setupNeeded = computed(() => !!setup.value && browserSetupNeeded(setup.value))
async function downloadNow() {
  const s = await mutateSetup<BrowserSetupStatus>('/api/browser/setup', { method: 'POST' })
  if (s) {
    setup.value = s
    pollSetup()
  }
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

    <div
      v-if="chosenEngine === 'jev'"
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

    <p
      v-if="chosenEngine === 'jev' && !keyConfigured"
      class="text-xs text-fg-muted"
      data-testid="browser-jev-key-hint"
    >
      Jev needs a TypeSafe API key, set in <NuxtLink
        to="/settings?section=decision-providers"
        class="text-fg-strong underline"
      >Settings → Decision Providers</NuxtLink>. Until one is set, agents keep the Playwright actions.
    </p>

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
          >{{ setup ? chromiumLabel(setup.chromiumInstalled) : '—' }}</span>
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
