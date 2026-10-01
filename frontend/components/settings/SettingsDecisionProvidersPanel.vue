<script setup lang="ts">
// Decision Providers settings panel (JCLAW-1302). One card per provider that answers a structured
// question with a probability per choice rather than with text. A card holds what the provider's
// consumers share — its key and its circuit breaker — and links to each consumer, where the settings
// that belong to that consumer alone stay.
import { SpeakerWaveIcon } from '@heroicons/vue/24/outline'
import type { OllamaDecisionStatus } from '~/types/api'

const { configData, saving, refresh, editingKey, editValue, editError, updateEntry } = useSettingsConfig()

const API_KEY = 'decision.jev.apiKey'

function configValue(key: string): string {
  return configData.value?.entries?.find(e => e.key === key)?.value ?? ''
}

const keyConfigured = computed(() => configValue(API_KEY).trim().length > 0)

// In use means the consumer has chosen JEV; without a key it still falls back to its default.
const consumers = computed(() => [
  { id: 'browser', label: 'Browser', detail: 'Jev engine', inUse: configValue('browser.engine') === 'jev' },
  {
    id: 'model-router', label: 'Model Router', detail: 'prompt classifier',
    // The router strips the provider and needs both halves of the pair.
    inUse: configValue('router.classifier.provider').trim() === 'jev' && configValue('router.classifier.model').trim() !== '',
  },
])

// Minted on JEV's first call, so the card shows no breaker until then.
const { byName: breakersByName, refresh: refreshBreakers } = useBreakers()
const breaker = computed(() => breakersByName.value.get('decision:jev'))

// JCLAW-1336: the operator's Ollama server, whose System One models answer the same questions as JEV.
const OLLAMA_ADDRESS = 'decision.ollama.baseUrl'
const OLLAMA_MODELS = 'decision.ollama.models'
const OLLAMA_PROVIDER = 'ollama-decision'
const { saveError: ollamaError, attempt } = useSaveAttempt()
// Lazy: the read dials the server, and must not hold the panel open while it does.
const { data: ollama, pending: ollamaPending, refresh: refreshOllama }
  = useLazyFetch<OllamaDecisionStatus>('/api/decision/ollama')

// Mirrors OllamaDecision.baseUrl's fallback, so saving it back clears the key instead of storing it.
const inheritedAddress = computed(() => {
  const local = configValue('provider.ollama-local.baseUrl').trim().replace(/\/+$/, '')
  return local ? local.replace(/\/v1$/, '') : 'http://localhost:11434'
})
const editingAddress = ref(false)
const addressEdit = ref('')

function startAddressEdit() {
  addressEdit.value = ollama.value?.baseUrl ?? inheritedAddress.value
  editingAddress.value = true
}

async function saveAddress() {
  const value = addressEdit.value.trim().replace(/\/+$/, '')
  saving.value = true
  const saved = await attempt(async () => {
    if (!value || value === inheritedAddress.value) {
      await $fetch(`/api/config/${OLLAMA_ADDRESS}`, { method: 'DELETE' })
    }
    else {
      await $fetch('/api/config', { method: 'POST', body: { key: OLLAMA_ADDRESS, value } })
    }
  })
  if (saved) editingAddress.value = false
  await refresh()
  saving.value = false
  await refreshOllama()
}

const selectedModels = computed(() => parseDecisionModels(configValue(OLLAMA_MODELS)))
// A selected model the server no longer lists stays visible, so it can be cleared.
const modelChoices = computed(() => {
  const installed = ollama.value?.models ?? []
  return [
    ...installed.map(name => ({ name, installed: true })),
    ...selectedModels.value.filter(m => !installed.includes(m)).map(name => ({ name, installed: false })),
  ]
})

async function toggleModel(name: string, selected: boolean) {
  const next = selected
    ? [...selectedModels.value, name]
    : selectedModels.value.filter(m => m !== name)
  saving.value = true
  await attempt(async () => {
    if (next.length === 0) await $fetch(`/api/config/${OLLAMA_MODELS}`, { method: 'DELETE' })
    else await $fetch('/api/config', { method: 'POST', body: { key: OLLAMA_MODELS, value: JSON.stringify(next) } })
  })
  await refresh()
  saving.value = false
}

const routerOllamaModel = computed(() =>
  configValue('router.classifier.provider').trim() === OLLAMA_PROVIDER
    ? configValue('router.classifier.model').trim()
    : '')
const ollamaBreaker = computed(() => breakersByName.value.get('decision:ollama'))

// The clip's transcript, so its words reach a reader who cannot hear it (WCAG 1.2.1).
const JEV_CLIP_LABEL = 'Play JEV saying “My name is Jev”'
const jevClip = ref<HTMLAudioElement>()
function playJevClip() {
  const audio = jevClip.value
  if (!audio) return
  audio.currentTime = 0
  void audio.play()
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Decision Providers
    </h2>
    <p class="text-xs text-fg-muted">
      Models that answer a question by choosing among given options, with a probability for each,
      rather than by writing text. JClaw features call them directly; they never answer a chat. Each
      card holds what its features share, such as the API key or server and the circuit breaker; a feature's own
      settings stay on that feature's page.
    </p>

    <div
      class="bg-surface-elevated border border-border"
      data-testid="decision-provider-jev"
    >
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-2">
        <span class="text-sm font-medium text-fg-strong">JEV (TypeSafe AI)</span>
        <span
          v-if="keyConfigured"
          class="text-xs text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          data-testid="decision-jev-status"
        >configured</span>
        <span
          v-else
          class="text-xs text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
          data-testid="decision-jev-status"
        >needs API key</span>
      </div>
      <div class="px-4 py-2.5 flex items-start gap-3 text-xs text-fg-muted leading-relaxed border-b border-border">
        <button
          type="button"
          class="group relative shrink-0 rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
          :aria-label="JEV_CLIP_LABEL"
          :title="JEV_CLIP_LABEL"
          data-testid="decision-jev-play"
          @click="playJevClip()"
        >
          <!-- 3lh: the text beside it wraps to three lines in a 1330–1660 px window. -->
          <img
            src="/jev.webp"
            alt=""
            width="143"
            height="176"
            class="h-[3lh] w-auto select-none"
            data-testid="decision-jev-portrait"
          >
          <!-- Always shown without hover, so a touch screen still gets the cue. -->
          <span
            class="absolute inset-0 flex items-center justify-center pointer-events-none opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100 [@media(hover:none)]:opacity-100 motion-safe:transition-opacity"
            aria-hidden="true"
          >
            <!-- Light, because a dark badge vanishes against JEV's black shirt. -->
            <span class="rounded-full bg-white/75 p-1 text-neutral-900 shadow-sm backdrop-blur-xs">
              <SpeakerWaveIcon class="w-3.5 h-3.5" />
            </span>
          </span>
        </button>
        <!-- eslint-disable-next-line vuejs-accessibility/media-has-caption -- its words are the button's name and tooltip -->
        <audio
          ref="jevClip"
          src="/jev.mp3"
          preload="none"
        />
        <p data-testid="decision-jev-retention">
          TypeSafe AI's decision model. TypeSafe AI may record or retain what it is sent: the Jev browser
          engine sends each step's page content (its address, title, visible text, element labels and form
          values, but not hidden password fields) with the goal and the text typed earlier in the run, and
          the Model Router's classifier sends the first 4000 characters of each prompt.
        </p>
      </div>
      <div class="divide-y divide-border">
        <div class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">apiKey</span>
          <SecretField
            v-model="editValue"
            :saved="keyConfigured"
            :editing="editingKey === API_KEY"
            label="TypeSafe API key"
            placeholder="Your TypeSafe API key"
            :busy="saving"
            data-testid="decision-jev-key"
            @edit="editingKey = API_KEY"
            @save="updateEntry(API_KEY)"
            @cancel="editingKey = null"
          />
        </div>
        <ApiErrorAlert
          v-if="editingKey === API_KEY"
          :error="editError"
          class="px-4 py-2.5"
        />
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-start gap-3"
          data-testid="decision-jev-used-by"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 py-0.5">used by</span>
          <ul class="flex-1 min-w-0 space-y-1">
            <li
              v-for="c in consumers"
              :key="c.id"
              class="flex flex-wrap items-center gap-x-2 gap-y-1"
              :data-testid="`decision-jev-consumer-${c.id}`"
            >
              <NuxtLink
                :to="`/settings?section=${c.id}`"
                class="text-sm text-fg-strong underline"
              >{{ c.label }}</NuxtLink>
              <span class="text-xs text-fg-muted">{{ c.detail }}</span>
              <span
                v-if="c.inUse"
                class="text-xs text-green-700 dark:text-green-400 border border-green-400/30 px-1"
              >in use</span>
              <span
                v-else
                class="text-xs text-fg-muted border border-input px-1"
              >not in use</span>
            </li>
          </ul>
        </div>
        <div
          v-if="breaker"
          class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
          data-testid="decision-jev-breaker"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">circuit breaker</span>
          <BreakerControl
            :breaker="breaker"
            class="flex-1"
            @changed="refreshBreakers()"
          />
        </div>
      </div>
    </div>

    <div
      class="bg-surface-elevated border border-border"
      data-testid="decision-provider-ollama"
    >
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-2">
        <span class="text-sm font-medium text-fg-strong">Ollama</span>
        <span
          v-if="ollamaPending && !ollama"
          class="text-xs text-fg-muted border border-input px-1"
          data-testid="decision-ollama-status"
        >checking…</span>
        <span
          v-else-if="ollama?.reachable"
          class="text-xs text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          data-testid="decision-ollama-status"
        >reachable</span>
        <span
          v-else
          class="text-xs text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
          data-testid="decision-ollama-status"
        >not reachable</span>
      </div>
      <div class="px-4 py-2.5 flex items-start gap-3 text-xs text-fg-muted leading-relaxed border-b border-border">
        <!-- 2lh, as JEV's 3lh: the text beside it wraps to two lines in a 1330–1660 px window. -->
        <IconOllama
          class="h-[2lh] w-auto shrink-0 text-fg-strong"
          data-testid="decision-ollama-logo"
        />
        <p data-testid="decision-ollama-privacy">
          Decision models such as tev1 and nimble, running on your own Ollama server. The Model Router's
          classifier sends the first 4000 characters of each prompt, only to this server.
        </p>
      </div>
      <div class="divide-y divide-border">
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
          data-testid="decision-ollama-address"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">server address</span>
          <template v-if="editingAddress">
            <input
              v-model="addressEdit"
              type="url"
              aria-label="Ollama server address"
              :placeholder="inheritedAddress"
              class="flex-1 min-w-0 bg-surface border border-input px-2 py-1 text-sm font-mono"
              data-testid="decision-ollama-address-input"
              @keydown.enter="saveAddress()"
            >
            <button
              type="button"
              class="text-xs text-fg-strong underline"
              :disabled="saving"
              data-testid="decision-ollama-address-save"
              @click="saveAddress()"
            >
              Save
            </button>
            <button
              type="button"
              class="text-xs text-fg-muted underline"
              @click="editingAddress = false"
            >
              Cancel
            </button>
          </template>
          <template v-else>
            <span class="flex-1 min-w-0 text-sm font-mono text-fg-strong truncate">{{ ollama?.baseUrl ?? inheritedAddress }}</span>
            <span
              v-if="ollama && !ollama.customized && configValue('provider.ollama-local.baseUrl').trim()"
              class="text-xs text-fg-muted"
            >from Ollama Local</span>
            <button
              type="button"
              class="text-xs text-fg-strong underline"
              data-testid="decision-ollama-address-edit"
              @click="startAddressEdit()"
            >
              Change
            </button>
          </template>
        </div>
        <ApiErrorAlert
          :error="ollamaError"
          class="px-4 py-2.5"
        />
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-start gap-3"
          data-testid="decision-ollama-models"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 py-0.5">decision models</span>
          <div class="flex-1 min-w-0 space-y-1">
            <p
              v-if="ollama && !ollama.reachable"
              class="text-xs text-amber-700 dark:text-amber-400"
              data-testid="decision-ollama-error"
            >
              {{ ollama.error }}
            </p>
            <p
              v-else-if="ollama && modelChoices.length === 0"
              class="text-xs text-fg-muted"
              data-testid="decision-ollama-no-models"
            >
              No decision models installed; pull one with <code>ollama pull tev1</code>.
            </p>
            <label
              v-for="m in modelChoices"
              :key="m.name"
              class="flex items-center gap-2 text-sm text-fg-strong"
              :for="`decision-ollama-model-${m.name}`"
              :data-testid="`decision-ollama-model-${m.name}`"
            >
              <input
                :id="`decision-ollama-model-${m.name}`"
                type="checkbox"
                :checked="selectedModels.includes(m.name)"
                :disabled="saving"
                @change="toggleModel(m.name, ($event.target as HTMLInputElement).checked)"
              >
              <span class="font-mono">{{ m.name }}</span>
              <span
                v-if="!m.installed"
                class="text-xs text-fg-muted"
              >not installed</span>
            </label>
          </div>
        </div>
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-start gap-3"
          data-testid="decision-ollama-used-by"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 py-0.5">used by</span>
          <ul class="flex-1 min-w-0 space-y-1">
            <li
              class="flex flex-wrap items-center gap-x-2 gap-y-1"
              data-testid="decision-ollama-consumer-model-router"
            >
              <NuxtLink
                to="/settings?section=model-router"
                class="text-sm text-fg-strong underline"
              >Model Router</NuxtLink>
              <span class="text-xs text-fg-muted">prompt classifier{{ routerOllamaModel ? ` (${routerOllamaModel})` : '' }}</span>
              <span
                v-if="routerOllamaModel"
                class="text-xs text-green-700 dark:text-green-400 border border-green-400/30 px-1"
              >in use</span>
              <span
                v-else
                class="text-xs text-fg-muted border border-input px-1"
              >not in use</span>
            </li>
          </ul>
        </div>
        <div
          v-if="ollamaBreaker"
          class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
          data-testid="decision-ollama-breaker"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">circuit breaker</span>
          <BreakerControl
            :breaker="ollamaBreaker"
            class="flex-1"
            @changed="refreshBreakers()"
          />
        </div>
      </div>
    </div>
  </div>
</template>
