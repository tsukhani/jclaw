<script setup lang="ts">
// Decision Providers settings panel (JCLAW-1302). One card per provider that answers a structured
// question with a probability per choice rather than with text. A card holds what the provider's
// consumers share — its key and its circuit breaker — and links to each consumer, where the settings
// that belong to that consumer alone stay.
import { CheckIcon, PencilIcon, SpeakerWaveIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const { configData, saving, editingKey, editValue, editError, updateEntry } = useSettingsConfig()

const API_KEY = 'decision.jev.apiKey'

function configValue(key: string): string {
  return configData.value?.entries?.find(e => e.key === key)?.value ?? ''
}

// The stored key comes back masked, so the editor starts blank rather than saving the mask back.
const keyConfigured = computed(() => configValue(API_KEY).trim().length > 0)
function startEditKey() {
  editingKey.value = API_KEY
  editValue.value = ''
}
// Saving the editor untouched would store a blank key over the real one, so it cancels instead.
function saveKey() {
  if (!editValue.value.trim()) editingKey.value = null
  else updateEntry(API_KEY)
}

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
      card holds what its features share, the API key and the circuit breaker; a feature's own
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
          class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          data-testid="decision-jev-status"
        >configured</span>
        <span
          v-else
          class="text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/30 px-1"
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
              data-testid="decision-jev-key"
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
                class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
              >in use</span>
              <span
                v-else
                class="text-[10px] text-fg-muted border border-input px-1"
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
  </div>
</template>
