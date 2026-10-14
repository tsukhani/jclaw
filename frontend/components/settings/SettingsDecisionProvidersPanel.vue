<script setup lang="ts">
// Decision Providers settings panel (JCLAW-1302). One card per provider that answers a structured
// question with a probability per choice rather than with text. A card holds what the provider's
// consumers share — its key and its circuit breaker — and links to each consumer, where the settings
// that belong to that consumer alone stay.
import { SpeakerWaveIcon } from '@heroicons/vue/24/outline'
import type { LayaDecisionStatus, OllamaDecisionStatus } from '~/types/api'
import type { LayaCheckpoint } from '~/utils/decision-models'

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

async function toggleModel(name: string, box: HTMLInputElement) {
  const next = box.checked
    ? [...selectedModels.value, name]
    : selectedModels.value.filter(m => m !== name)
  saving.value = true
  const saved = await attempt(async () => {
    if (next.length === 0) await $fetch(`/api/config/${OLLAMA_MODELS}`, { method: 'DELETE' })
    else await $fetch('/api/config', { method: 'POST', body: { key: OLLAMA_MODELS, value: JSON.stringify(next) } })
  })
  await refresh()
  // Vue does not re-patch a `checked` that never changed, so a refused write would leave the box as clicked.
  if (!saved) box.checked = selectedModels.value.includes(name)
  saving.value = false
}

const routerOllamaModel = computed(() =>
  configValue('router.classifier.provider').trim() === OLLAMA_PROVIDER
    ? configValue('router.classifier.model').trim()
    : '')

// Only while ticked, so a router model stored unticked can still be ticked.
function lockedByRouter(name: string) {
  return routerOllamaModel.value === name && selectedModels.value.includes(name)
}

const ollamaBreaker = computed(() => breakersByName.value.get('decision:ollama'))

// JCLAW-1447: the operator's laya-serve. JClaw calls its address and starts nothing.
const LAYA_ADDRESS = 'decision.laya.baseUrl'
const LAYA_API_KEY = 'decision.laya.apiKey'
const LAYA_MODELS = 'decision.laya.models'
const LAYA_PROVIDER = 'laya'
const layaSave = useSaveAttempt()
const layaError = layaSave.saveError
// Lazy, as Ollama's: the read dials the server.
const { data: laya, pending: layaPending, refresh: refreshLaya }
  = useLazyFetch<LayaDecisionStatus>('/api/decision/laya')

const layaAddress = computed(() =>
  laya.value?.baseUrl ?? (configValue(LAYA_ADDRESS).trim().replace(/\/+$/, '') || LAYA_DEFAULT_ADDRESS))
const editingLayaAddress = ref(false)
const layaAddressEdit = ref('')

function startLayaAddressEdit() {
  layaAddressEdit.value = layaAddress.value
  editingLayaAddress.value = true
}

function cancelLayaAddressEdit() {
  editingLayaAddress.value = false
  layaError.value = null
}

async function saveLayaAddress() {
  const value = layaAddressEdit.value.trim().replace(/\/+$/, '')
  saving.value = true
  const saved = await layaSave.attempt(async () => {
    // Saving the default back clears the key instead of storing what absence already means.
    if (!value || value === LAYA_DEFAULT_ADDRESS) {
      await $fetch(`/api/config/${LAYA_ADDRESS}`, { method: 'DELETE' })
    }
    else {
      await $fetch('/api/config', { method: 'POST', body: { key: LAYA_ADDRESS, value } })
    }
  })
  if (saved) editingLayaAddress.value = false
  await refresh()
  saving.value = false
  await refreshLaya()
}

const layaKeyConfigured = computed(() => configValue(LAYA_API_KEY).trim().length > 0)

// A server started with a key tells a caller that sends it more, so the status is read again.
async function saveLayaKey() {
  await updateEntry(LAYA_API_KEY)
  await refreshLaya()
}

const tickedCheckpoints = computed(() => tickedLayaCheckpoints(configValue(LAYA_MODELS)).map(c => c.name))

function checkpointNote(c: LayaCheckpoint) {
  return laya.value?.loaded.includes(c.name) ? `${layaContext(c)}, loaded` : layaContext(c)
}

async function toggleCheckpoint(name: string, box: HTMLInputElement) {
  const next = LAYA_CHECKPOINTS.map(c => c.name)
    .filter(n => n === name ? box.checked : tickedCheckpoints.value.includes(n))
  saving.value = true
  const saved = await layaSave.attempt(async () => {
    if (next.length === 0) await $fetch(`/api/config/${LAYA_MODELS}`, { method: 'DELETE' })
    else await $fetch('/api/config', { method: 'POST', body: { key: LAYA_MODELS, value: JSON.stringify(next) } })
  })
  await refresh()
  // Vue does not re-patch a `checked` that never changed, so a refused write would leave the box as clicked.
  if (!saved) box.checked = tickedCheckpoints.value.includes(name)
  saving.value = false
}

const routerLayaCheckpoint = computed(() =>
  configValue('router.classifier.provider').trim() === LAYA_PROVIDER
    ? configValue('router.classifier.model').trim()
    : '')
const layaBreaker = computed(() => breakersByName.value.get('decision:laya'))

// Each clip's transcript, so what it says reaches a reader who cannot hear it (WCAG 1.2.1).
const JEV_CLIP_LABEL = 'Play JEV saying “My name is Jev”'
const OLLAMA_CLIP_LABEL = 'Play a llama’s call'
const jevClip = ref<HTMLAudioElement>()
const ollamaClip = ref<HTMLAudioElement>()
function playClip(audio: HTMLAudioElement | undefined) {
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
          @click="playClip(jevClip)"
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
        <button
          type="button"
          class="group relative shrink-0 rounded-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring"
          :aria-label="OLLAMA_CLIP_LABEL"
          :title="OLLAMA_CLIP_LABEL"
          data-testid="decision-ollama-play"
          @click="playClip(ollamaClip)"
        >
          <!-- 3lh, as JEV's: the text beside it wraps to three lines in a 1330–1660 px window. -->
          <img
            src="/ollama.webp"
            alt=""
            width="200"
            height="294"
            class="block h-[3lh] w-auto select-none"
            data-testid="decision-ollama-logo"
          >
          <span
            class="absolute inset-0 flex items-center justify-center pointer-events-none opacity-0 group-hover:opacity-100 group-focus-visible:opacity-100 [@media(hover:none)]:opacity-100 motion-safe:transition-opacity"
            aria-hidden="true"
          >
            <!-- JEV's light badge, so the two cards' cues match. -->
            <span class="rounded-full bg-white/75 p-1 text-neutral-900 shadow-sm backdrop-blur-xs">
              <SpeakerWaveIcon class="w-3.5 h-3.5" />
            </span>
          </span>
        </button>
        <!-- eslint-disable-next-line vuejs-accessibility/media-has-caption -- its sound is the button's name and tooltip -->
        <audio
          ref="ollamaClip"
          src="/ollama.mp3"
          preload="none"
        />
        <p data-testid="decision-ollama-privacy">
          Decision models such as tev1 and nimble, running on your own Ollama server. They answer the same
          questions JEV does, but Ollama runs them only on that server and will not hand a decision to a cloud
          model, so no third party ever sees or keeps what they are sent. The Model Router's classifier sends
          the first 4000 characters of each prompt, only to this server.
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
                :disabled="saving || lockedByRouter(m.name)"
                @change="toggleModel(m.name, $event.target as HTMLInputElement)"
              >
              <span class="font-mono">{{ m.name }}</span>
              <span
                v-if="!m.installed"
                class="text-xs text-fg-muted"
              >not installed</span>
            </label>
            <p
              v-if="modelChoices.length > 0"
              class="text-xs text-fg-muted"
              data-testid="decision-ollama-selection"
            >
              Only a ticked model can be the Model Router's classifier, and the one it is using cannot be unticked.
            </p>
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

    <div
      class="bg-surface-elevated border border-border"
      data-testid="decision-provider-laya"
    >
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-2">
        <span class="text-sm font-medium text-fg-strong">Laya</span>
        <span
          v-if="layaPending && !laya"
          class="text-xs text-fg-muted border border-input px-1"
          data-testid="decision-laya-status"
        >checking…</span>
        <span
          v-else-if="laya?.reachable"
          class="text-xs text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          data-testid="decision-laya-status"
        >reachable</span>
        <span
          v-else
          class="text-xs text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
          data-testid="decision-laya-status"
        >not reachable</span>
      </div>
      <div class="px-4 py-2.5 space-y-2 text-xs text-fg-muted leading-relaxed border-b border-border">
        <p data-testid="decision-laya-about">
          An open-weight decision model under the Apache-2.0 license, which answers the same questions JEV does.
          You run its server, <code>laya-serve</code>, yourself; JClaw calls the address below and starts nothing.
          The Model Router's classifier sends the first 4000 characters of each prompt, only to this server; how
          much of them a checkpoint reads is under Context below.
        </p>
        <ul
          class="list-disc pl-4 space-y-1"
          aria-label="Laya's limits"
          data-testid="decision-laya-limits"
        >
          <li
            v-for="limit in LAYA_LIMITS"
            :key="limit.topic"
            :data-testid="`decision-laya-limit-${limit.topic.toLowerCase()}`"
          >
            <span class="font-medium text-fg-strong">{{ limit.topic }}.</span> {{ layaLimitText(limit) }}
          </li>
        </ul>
      </div>
      <div class="divide-y divide-border">
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
          data-testid="decision-laya-address"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">server address</span>
          <template v-if="editingLayaAddress">
            <input
              v-model="layaAddressEdit"
              type="url"
              aria-label="Laya server address"
              :placeholder="LAYA_DEFAULT_ADDRESS"
              class="flex-1 min-w-0 bg-surface border border-input px-2 py-1 text-sm font-mono"
              data-testid="decision-laya-address-input"
              @keydown.enter="saveLayaAddress()"
            >
            <button
              type="button"
              class="text-xs text-fg-strong underline"
              :disabled="saving"
              data-testid="decision-laya-address-save"
              @click="saveLayaAddress()"
            >
              Save
            </button>
            <button
              type="button"
              class="text-xs text-fg-muted underline"
              data-testid="decision-laya-address-cancel"
              @click="cancelLayaAddressEdit()"
            >
              Cancel
            </button>
          </template>
          <template v-else>
            <span class="flex-1 min-w-0 text-sm font-mono text-fg-strong truncate">{{ layaAddress }}</span>
            <button
              type="button"
              class="text-xs text-fg-strong underline"
              data-testid="decision-laya-address-edit"
              @click="startLayaAddressEdit()"
            >
              Change
            </button>
          </template>
        </div>
        <ApiErrorAlert
          :error="layaError"
          class="px-4 py-2.5"
        />
        <div class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">apiKey (optional)</span>
          <SecretField
            v-model="editValue"
            :saved="layaKeyConfigured"
            :editing="editingKey === LAYA_API_KEY"
            label="Laya API key"
            placeholder="Only if laya-serve was started with LAYA_API_KEY"
            removable
            :busy="saving"
            data-testid="decision-laya-key"
            @edit="editingKey = LAYA_API_KEY"
            @save="saveLayaKey()"
            @cancel="editingKey = null"
            @remove="editValue = ''; saveLayaKey()"
          />
        </div>
        <ApiErrorAlert
          v-if="editingKey === LAYA_API_KEY"
          :error="editError"
          class="px-4 py-2.5"
        />
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-start gap-3"
          data-testid="decision-laya-checkpoints"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 py-0.5">checkpoints</span>
          <div class="flex-1 min-w-0 space-y-1">
            <p
              v-if="laya && !laya.reachable"
              class="text-xs text-amber-700 dark:text-amber-400"
              data-testid="decision-laya-error"
            >
              {{ laya.error }}
            </p>
            <p
              v-else-if="laya && !laya.device"
              class="text-xs text-fg-muted"
              data-testid="decision-laya-keyed"
            >
              The server answers /health but says no more. If it is laya-serve started with a key, set or correct
              the key above to see what it has loaded.
            </p>
            <p
              v-else-if="laya"
              class="text-xs text-fg-muted"
              data-testid="decision-laya-device"
            >
              Server device: <span class="font-mono">{{ laya.device }}</span>
            </p>
            <label
              v-for="c in LAYA_CHECKPOINTS"
              :key="c.name"
              class="flex flex-wrap items-center gap-2 text-sm text-fg-strong"
              :for="`decision-laya-checkpoint-${c.name}`"
              :data-testid="`decision-laya-checkpoint-${c.name}`"
            >
              <input
                :id="`decision-laya-checkpoint-${c.name}`"
                type="checkbox"
                :checked="tickedCheckpoints.includes(c.name)"
                :disabled="saving || routerLayaCheckpoint === c.name"
                @change="toggleCheckpoint(c.name, $event.target as HTMLInputElement)"
              >
              <!-- One line: the space between the two is what a screen reader hears between name and context. -->
              <span class="font-mono">{{ c.name }}</span> <span class="text-xs text-fg-muted">{{ checkpointNote(c) }}</span>
            </label>
            <p
              class="text-xs text-fg-muted"
              data-testid="decision-laya-cut"
            >
              Each reads that many tokens per question; a longer prompt is cut, not refused (Laya's model card).
              Only a ticked checkpoint can be the Model Router's classifier, and the one it is using cannot be
              unticked.
            </p>
          </div>
        </div>
        <div
          class="px-4 py-2 flex max-sm:flex-wrap items-start gap-3"
          data-testid="decision-laya-used-by"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0 py-0.5">used by</span>
          <ul class="flex-1 min-w-0 space-y-1">
            <li
              class="flex flex-wrap items-center gap-x-2 gap-y-1"
              data-testid="decision-laya-consumer-model-router"
            >
              <NuxtLink
                to="/settings?section=model-router"
                class="text-sm text-fg-strong underline"
              >Model Router</NuxtLink>
              <span class="text-xs text-fg-muted">prompt classifier{{ routerLayaCheckpoint ? ` (${routerLayaCheckpoint})` : '' }}</span>
              <span
                v-if="routerLayaCheckpoint"
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
          v-if="layaBreaker"
          class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
          data-testid="decision-laya-breaker"
        >
          <span class="text-xs font-mono text-fg-muted w-48 max-sm:w-full shrink-0">circuit breaker</span>
          <BreakerControl
            :breaker="layaBreaker"
            class="flex-1"
            @changed="refreshBreakers()"
          />
        </div>
      </div>
    </div>
  </div>
</template>
