<script setup lang="ts">
// Unmanaged-config warning banner. Config DB rows not owned by any managed
// Settings section shouldn't exist in normal operation — they're usually stale
// keys from a prior schema. Rather than a permanent "Unmanaged Config" nav
// section (which implies they're an expected, ongoing thing), this surfaces
// them at the top of the Settings page ONLY when present, as a cleanup signal.
// Renders nothing when the config is clean.
import { ChevronDownIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import type { ConfigEntry } from '~/types/api'

const { configData } = useSettingsConfig()

// Top-level Config DB prefixes claimed by a UI domain. Any row whose key starts
// with one of these is owned somewhere in the app and is NOT unmanaged —
// regardless of which page actually manages it. Keeps Settings free of
// exact-key knowledge about other pages' config.
const MANAGED_PREFIXES = [
  'app.', // Operator-wide settings — Settings (General). app.timezone = the
  // assistant's wall-clock zone injected into the system prompt.
  'provider.', // LLM providers — Settings
  'dispatcher.', // OkHttp dispatcher caps — Settings (Performance)
  'transcription.', // Transcription provider + local model — Settings (Transcription)
  'tts.', // TTS engine + per-engine model/port (tts.engine, tts.local.port, tts.<engine>.model) — Settings (Speech). JCLAW-789/793.
  'caption.', // Image captioning cloud + local model (caption.cloud.*) — Settings (Image Captioning)
  'video.', // Video interpretation frame-sample density (video.sampleFrames) — Settings (Video Interpretation)
  'imagegen.', // Image generation provider selection (imagegen.provider) — Settings (Image Generation)
  'videogen.', // Video generation provider + job timeout (videogen.provider) — Settings (Video Generation)
  'ocr.', // OCR backends — Settings (Tesseract today; GLM-OCR planned)
  'search.', // Search providers — Settings
  'scanner.', // Malware scanners — Settings
  'chat.', // Chat settings — Settings
  'shell.', // Shell execution defaults — Settings (allowlist + timeout)
  'playwright.', // JCLAW-172: namespace retired but kept in the prefix list
  // so leftover playwright.enabled / playwright.headless rows on upgraded
  // installs don't surface as "Unmanaged" diagnostic noise.
  'skillsPromotion.', // Skills promotion sanitization — Settings
  'agent.', // Per-agent config (shell privileges, queue mode, etc.) — Agents page
  'ollama.', // Ollama provider-specific settings — Settings
  'upload.', // Per-kind attachment size caps (JCLAW-131) — Settings
  'auth.', // Admin password hash — Settings (Password section, not rendered as a row)
  'onboarding.', // First-login guided tour flag — written by ApiOnboardingController, no UI surface
  'pricing.', // LiteLLM nightly price-refresh toggle (JCLAW-28 follow-up) — Settings (LLM Providers section)
  'subagent.', // JCLAW-266: subagent recursion caps — Settings (Subagents section)
  'tasks.', // JCLAW-259: task retention TTL — Settings (Tasks section)
  'tailscale.', // Funnel enable/port (tailscale.funnel.*) — managed on the Channels page
  'jtokkit.', // Token-count safety multipliers — `jtokkit.safetyMultiplier.unmatched`
  // is the operator-tunable global in the Advanced subsection of Chat, and the
  // per-(provider, model) `jtokkit.safetyMultiplier.<provider>.<modelId>`
  // entries are written autonomously by TokenizerCalibrationJob every 30 min
  // based on observed provider-vs-jtokkit deltas — operators don't manage
  // these directly. Surfacing them here would imply they're stale or
  // operator-actionable, neither of which is true.
]

function isManagedKey(key: string): boolean {
  return MANAGED_PREFIXES.some(p => key.startsWith(p))
}

const unmanaged = computed<ConfigEntry[]>(() =>
  (configData.value?.entries ?? []).filter(e => !isManagedKey(e.key)),
)
const expanded = ref(false)
</script>

<template>
  <div
    v-if="unmanaged.length"
    class="mb-6 border border-amber-400/40 bg-amber-50/60 dark:bg-amber-900/15 rounded-lg overflow-hidden"
  >
    <button
      type="button"
      class="w-full flex items-start gap-2 px-4 py-3 text-left"
      :aria-expanded="expanded"
      data-testid="unmanaged-banner-toggle"
      @click="expanded = !expanded"
    >
      <ExclamationTriangleIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-amber-600 dark:text-amber-400"
        aria-hidden="true"
      />
      <span class="min-w-0 flex-1">
        <span class="text-sm font-medium text-amber-800 dark:text-amber-300">
          {{ unmanaged.length }} unmanaged config {{ unmanaged.length === 1 ? 'key' : 'keys' }}
        </span>
        <span class="block text-[11px] text-amber-700/80 dark:text-amber-400/70">
          Config DB rows not owned by any Settings section — usually stale keys from a prior
          version. They shouldn't exist; review and remove them.
        </span>
      </span>
      <ChevronDownIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-amber-600/70 dark:text-amber-400/70 transition-transform"
        :class="expanded ? 'rotate-180' : ''"
        aria-hidden="true"
      />
    </button>
    <div
      v-if="expanded"
      class="border-t border-amber-400/30 divide-y divide-amber-400/20"
    >
      <div
        v-for="entry in unmanaged"
        :key="entry.key"
        class="px-4 py-2 flex items-center gap-3"
      >
        <span class="text-xs font-mono text-fg-muted w-64 shrink-0 truncate">{{ entry.key }}</span>
        <span class="flex-1 text-sm text-fg-muted font-mono truncate">{{ entry.value }}</span>
      </div>
    </div>
  </div>
</template>
