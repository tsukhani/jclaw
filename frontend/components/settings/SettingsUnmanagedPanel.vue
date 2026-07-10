<script setup lang="ts">
// Unmanaged config entries (diagnostic) settings panel (JCLAW-680). Renders the
// Config DB rows not owned by any managed UI section — usually stale keys from a
// prior schema. Moved verbatim from pages/settings.vue; owns the managed-prefix
// list, the provider grouping, and the derived `providerEntries.other` list.
import type { ConfigEntry } from '~/types/api'

const { configData } = useSettingsConfig()

// Top-level Config DB prefixes claimed by a UI domain. Any row whose key starts
// with one of these is owned somewhere in the app and should not surface in the
// Unmanaged diagnostic list — regardless of which page actually manages it.
// Keeps Settings free of exact-key knowledge about other pages' config.
const MANAGED_PREFIXES = [
  'app.', // Operator-wide settings — Settings (General). app.timezone = the
  // assistant's wall-clock zone injected into the system prompt.
  'provider.', // LLM providers — Settings
  'dispatcher.', // OkHttp dispatcher caps — Settings (Performance)
  'transcription.', // Transcription provider + local model — Settings (Transcription)
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
  // these directly. Surfacing them in "Unmanaged keys" would imply they're
  // stale or operator-actionable, neither of which is true.
]

function isManagedKey(key: string): boolean {
  return MANAGED_PREFIXES.some(p => key.startsWith(p))
}

// JCLAW-229: image-generation-only providers are NOT chat LLM providers (excluded from the backend
// ProviderRegistry too) — their keys are set in the Image Generation section, not here, so skip them
// when grouping the LLM Providers list.
const IMAGE_ONLY_PROVIDERS = new Set(['bfl', 'replicate'])

// Group config entries by LLM provider; everything else (non-managed) falls through
// to the generic Configuration list.
const providerEntries = computed(() => {
  const entries = configData.value?.entries ?? []
  const providers = new Map<string, ConfigEntry[]>()
  const other: ConfigEntry[] = []

  for (const e of entries) {
    if (e.key.startsWith('provider.')) {
      const parts = e.key.split('.')
      const name = parts[1]!
      if (IMAGE_ONLY_PROVIDERS.has(name)) continue // set in the Image Generation section
      if (!providers.has(name)) providers.set(name, [])
      providers.get(name)!.push(e)
    }
    else if (!isManagedKey(e.key)) {
      other.push(e)
    }
  }
  return { providers, other }
})
</script>

<template>
  <!-- Unmanaged config entries (diagnostic) -->
  <!-- Only rendered when the Config DB contains keys not owned by any managed UI
       section above. Typically shows stale rows from a prior schema or mid-migration
       state — a signal that something needs cleanup, not a place to add new config. -->
  <div
    v-if="providerEntries.other.length"
    class="bg-surface-elevated border border-border"
  >
    <div class="px-4 py-3 border-b border-border">
      <h2 class="text-sm font-medium text-fg-primary">
        Unmanaged keys
      </h2>
      <p class="text-[11px] text-fg-muted mt-0.5">
        Config DB rows not owned by any section above — usually stale keys from a prior
        version. Safe to ignore; they're shown here so migrations don't hide data.
      </p>
    </div>
    <div class="divide-y divide-border">
      <div
        v-for="entry in providerEntries.other"
        :key="entry.key"
        class="px-4 py-2.5 flex items-center gap-3"
      >
        <span class="text-xs font-mono text-fg-muted w-64 shrink-0 truncate">{{ entry.key }}</span>
        <span class="flex-1 text-sm text-fg-muted font-mono truncate">{{ entry.value }}</span>
      </div>
    </div>
  </div>
</template>
