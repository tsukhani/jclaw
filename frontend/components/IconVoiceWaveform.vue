<script setup lang="ts">
// Voice-mode waveform glyph (JCLAW-791) — the audio-bars icon ChatGPT and
// Claude use for voice mode. Stroke uses currentColor and the caller sizes it
// via a class (e.g. w-4 h-4), matching the heroicons in the composer row.
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="2"
    stroke-linecap="round"
    aria-hidden="true"
  >
    <path d="M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4" />
  </svg>
</template>
