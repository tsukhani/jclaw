<script setup lang="ts">
// Voice Mode (JCLAW-795): server-side turn detection and speech pacing for real-time voice chat.
import SettingsConfigField from './SettingsConfigField.vue'
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Voice Mode
    </h2>
    <p class="text-xs text-fg-muted">
      How a real-time voice conversation decides you have finished speaking, and how the
      reply is paced into speech. Turn detection and live-transcript settings apply from
      the next voice session; the run-on limit applies from the next reply.
    </p>

    <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2">
      Turn Detection
    </h3>
    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <SettingsConfigField
        config-key="voice.endpoint.speechStartMs"
        label="speechStartMs"
        kind="number"
        fallback="180"
        :min="1"
        tip="Milliseconds of continuous speech needed before sound counts as the start of an utterance. Higher ignores coughs and background noise."
      />
      <SettingsConfigField
        config-key="voice.endpoint.baseSilenceMs"
        label="baseSilenceMs"
        kind="number"
        fallback="500"
        :min="1"
        tip="Silence, in milliseconds, that ends a turn which sounds complete. Must not exceed maxSilenceMs."
      />
      <SettingsConfigField
        config-key="voice.endpoint.maxSilenceMs"
        label="maxSilenceMs"
        kind="number"
        fallback="1500"
        :min="1"
        tip="Longest silence, in milliseconds, waited out when you pause mid-sentence before the turn ends anyway. Must be at least baseSilenceMs."
      />
      <SettingsConfigField
        config-key="voice.endpoint.minUtteranceMs"
        label="minUtteranceMs"
        kind="number"
        fallback="200"
        :min="0"
        tip="Shortest utterance kept, in milliseconds; briefer blips are dropped."
      />
      <SettingsConfigField
        config-key="voice.endpoint.semanticHold"
        label="semanticHold"
        kind="boolean"
        fallback="true"
        tip="When the live transcript ends mid-clause, wait up to maxSilenceMs instead of ending the turn after baseSilenceMs. Off, every baseSilenceMs pause ends the turn."
      />
    </div>

    <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2">
      Transcripts &amp; Speech
    </h3>
    <div class="bg-surface-elevated border border-border divide-y divide-border">
      <SettingsConfigField
        config-key="voice.partials.enabled"
        label="partialsEnabled"
        kind="boolean"
        fallback="true"
        tip="Show a live transcript while you speak. Not used with models that hear audio directly."
      />
      <SettingsConfigField
        config-key="voice.partials.intervalMs"
        label="partialsIntervalMs"
        kind="number"
        fallback="1200"
        :min="0"
        tip="Minimum milliseconds between live-transcript updates."
      />
      <SettingsConfigField
        config-key="voice.tts.maxRunOnChars"
        label="maxRunOnChars"
        kind="number"
        fallback="220"
        :min="1"
        tip="A reply with no sentence break is cut into speech after this many characters, so audio starts without waiting for the sentence to end. Minimum 1."
      />
    </div>
  </div>
</template>
