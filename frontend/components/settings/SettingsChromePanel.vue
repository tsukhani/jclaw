<script setup lang="ts">
// Chrome extension settings: pairing, what each agent may open, and the two overrides only the
// main agent has. Every value is an ordinary /api/config row under agent.<name>.chromeBridge., which
// is operator-only to write, so an agent cannot widen its own reach through jclaw_api.
import type { Agent } from '~/types/api'

const { configValue, saving, refresh } = useSettingsConfig()
const { saveError, attempt } = useSaveAttempt()
const { mutate, errorDetails: pairError } = useApiMutation()

const SITES = 'allowedSites'
const ALLOW_ALL = 'allowAllSites'
const SUBMIT_FREE = 'submitWithoutApproval'
const keyFor = (agent: Agent, setting: string) => `agent.${agent.name}.chromeBridge.${setting}`

const agents = ref<Agent[]>([])
const paired = ref(false)
const token = ref<string | null>(null)
const drafts = ref<Record<string, string | undefined>>({})

onMounted(async () => {
  agents.value = await $fetch<Agent[]>('/api/agents')
  paired.value = (await $fetch<{ paired: boolean }>('/api/chrome-bridge')).paired
})

const mainAgent = computed(() => agents.value.find(a => a.isMain))

// The stored list is space or comma separated; the editor shows one site per line.
function storedSites(agent: Agent): string {
  return configValue(keyFor(agent, SITES), '').split(/[\s,]+/).filter(Boolean).join('\n')
}
function draftFor(agent: Agent): string {
  return drafts.value[agent.name] ?? storedSites(agent)
}
function dirty(agent: Agent): boolean {
  return draftFor(agent) !== storedSites(agent)
}

async function saveSites(agent: Agent) {
  const sites = draftFor(agent).split(/[\s,]+/).filter(Boolean).join(' ')
  saving.value = true
  const ok = await attempt(() => sites
    ? $fetch('/api/config', { method: 'POST', body: { key: keyFor(agent, SITES), value: sites } })
    : $fetch(`/api/config/${keyFor(agent, SITES)}`, { method: 'DELETE' }))
  if (ok) drafts.value = { ...drafts.value, [agent.name]: undefined }
  await refresh()
  saving.value = false
}

const isOn = (agent: Agent, setting: string) => configValue(keyFor(agent, setting), 'false') === 'true'

async function setOverride(agent: Agent, setting: string, on: boolean) {
  saving.value = true
  await attempt(() => $fetch('/api/config', { method: 'POST', body: { key: keyFor(agent, setting), value: String(on) } }))
  await refresh()
  saving.value = false
}

async function pair() {
  const r = await mutate<{ token: string }>('/api/chrome-bridge/pairing', { method: 'POST' })
  if (r) {
    token.value = r.token
    paired.value = true
  }
}

async function unpair() {
  if (await mutate<{ paired: boolean }>('/api/chrome-bridge/pairing', { method: 'DELETE' })) {
    token.value = null
    paired.value = false
  }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Chrome
    </h2>
    <p class="text-xs text-fg-muted">
      Lets an agent work in your own Chrome, signed in as you, but only inside a tab group named
      JClaw. A page it opens there sees your saved logins, so each agent gets its own list of sites
      it may open.
    </p>

    <section
      class="space-y-2"
      aria-labelledby="chrome-pairing-heading"
    >
      <h3
        id="chrome-pairing-heading"
        class="text-xs font-medium text-fg-muted"
      >
        Extension
      </h3>
      <div class="bg-surface-elevated border border-border px-4 py-2.5 flex items-center gap-3">
        <span
          class="flex-1 text-sm text-fg-primary"
          data-testid="chrome-pair-status"
        >{{ paired ? 'Paired' : 'Not paired' }}</span>
        <button
          type="button"
          class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors"
          data-testid="chrome-pair-button"
          @click="pair"
        >
          {{ paired ? 'Pair again' : 'Pair extension' }}
        </button>
        <button
          v-if="paired"
          type="button"
          class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors"
          data-testid="chrome-unpair-button"
          @click="unpair"
        >
          Unpair
        </button>
      </div>
      <div
        v-if="token"
        class="border border-amber-400/40 bg-amber-50/50 dark:bg-amber-900/10 px-3 py-2 space-y-1"
      >
        <p class="text-xs text-amber-800 dark:text-amber-300">
          Paste this into the JClaw extension. It is shown once; pairing again replaces it.
        </p>
        <code
          class="block text-xs font-mono break-all text-fg-strong"
          data-testid="chrome-token"
        >{{ token }}</code>
      </div>
      <ApiErrorAlert :error="pairError" />
    </section>

    <section
      v-if="mainAgent"
      class="space-y-2"
      aria-labelledby="chrome-main-heading"
    >
      <h3
        id="chrome-main-heading"
        class="text-xs font-medium text-fg-muted"
      >
        Main agent only
      </h3>
      <div class="bg-surface-elevated border border-border divide-y divide-border">
        <label
          for="chrome-allow-all"
          class="px-4 py-2.5 flex items-start gap-3 cursor-pointer"
        >
          <input
            id="chrome-allow-all"
            type="checkbox"
            class="mt-0.5 accent-emerald-600"
            data-testid="chrome-allow-all"
            :checked="isOn(mainAgent, ALLOW_ALL)"
            :disabled="saving"
            @change="setOverride(mainAgent, ALLOW_ALL, ($event.target as HTMLInputElement).checked)"
          >
          <span class="text-sm text-fg-primary">
            Allow any site
            <span class="block text-xs text-fg-muted">
              Skips the list below. Loopback and private-network addresses, including this
              instance, stay blocked.
            </span>
          </span>
        </label>
        <label
          for="chrome-submit-free"
          class="px-4 py-2.5 flex items-start gap-3 cursor-pointer"
        >
          <input
            id="chrome-submit-free"
            type="checkbox"
            class="mt-0.5 accent-emerald-600"
            data-testid="chrome-submit-free"
            :checked="isOn(mainAgent, SUBMIT_FREE)"
            :disabled="saving"
            @change="setOverride(mainAgent, SUBMIT_FREE, ($event.target as HTMLInputElement).checked)"
          >
          <span class="text-sm text-fg-primary">
            Submit forms without asking
            <span class="block text-xs text-fg-muted">
              Every other agent asks you before it submits a form.
            </span>
          </span>
        </label>
      </div>
    </section>

    <section
      class="space-y-2"
      aria-labelledby="chrome-sites-heading"
    >
      <h3
        id="chrome-sites-heading"
        class="text-xs font-medium text-fg-muted"
      >
        Sites each agent may open
      </h3>
      <p class="text-xs text-fg-muted">
        One per line: <span class="font-mono">example.com</span> for that site,
        <span class="font-mono">*.example.com</span> for its subdomains. An empty list opens nothing.
      </p>
      <div class="bg-surface-elevated border border-border divide-y divide-border">
        <div
          v-for="agent in agents"
          :key="agent.id"
          class="px-4 py-2.5 space-y-2"
        >
          <label
            :for="`chrome-sites-${agent.name}`"
            class="block space-y-2"
          >
            <span class="text-sm text-fg-primary">{{ agent.name }}</span>
            <textarea
              :id="`chrome-sites-${agent.name}`"
              :value="draftFor(agent)"
              rows="3"
              class="w-full px-2 py-1 text-xs font-mono bg-surface border border-input text-fg-strong"
              :data-testid="`chrome-sites-${agent.name}`"
              @input="drafts[agent.name] = ($event.target as HTMLTextAreaElement).value"
            />
          </label>
          <button
            type="button"
            class="px-3 py-1 text-xs font-medium border border-input bg-muted hover:bg-surface-elevated text-fg-strong transition-colors disabled:opacity-50"
            :data-testid="`chrome-sites-save-${agent.name}`"
            :disabled="saving || !dirty(agent)"
            @click="saveSites(agent)"
          >
            Save
          </button>
        </div>
      </div>
      <ApiErrorAlert :error="saveError" />
    </section>
  </div>
</template>
