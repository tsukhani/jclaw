<script setup lang="ts">
// Voice-mode waveform glyph (JCLAW-791) — the audio-bars icon ChatGPT and Claude
// use for voice mode. Static by default (the composer entry button); pass
// `animated` for a live, staggered equalizer pulse (the voice overlay's state
// indicator). Bars are centered on the midline and pulse via scaleY, so an odd
// base shape still animates cleanly. currentColor + caller-sized.
defineProps<{ animated?: boolean }>()
</script>

<template>
  <svg
    viewBox="0 0 24 24"
    fill="currentColor"
    aria-hidden="true"
    :class="{ 'vw-on': animated }"
  >
    <rect
      class="vw-bar"
      x="2.9"
      y="8"
      width="2.2"
      height="8"
      rx="1.1"
    />
    <rect
      class="vw-bar"
      x="6.9"
      y="4"
      width="2.2"
      height="16"
      rx="1.1"
    />
    <rect
      class="vw-bar"
      x="10.9"
      y="9"
      width="2.2"
      height="6"
      rx="1.1"
    />
    <rect
      class="vw-bar"
      x="14.9"
      y="5"
      width="2.2"
      height="14"
      rx="1.1"
    />
    <rect
      class="vw-bar"
      x="18.9"
      y="7"
      width="2.2"
      height="10"
      rx="1.1"
    />
  </svg>
</template>

<style scoped>
.vw-bar {
  /* Scale about each bar's own centre (the midline) rather than the SVG origin. */
  transform-box: fill-box;
  transform-origin: center;
}

.vw-on .vw-bar {
  animation: vw-pulse 0.9s ease-in-out infinite;
}

.vw-on .vw-bar:nth-child(1) { animation-delay: 0s; }
.vw-on .vw-bar:nth-child(2) { animation-delay: 0.2s; }
.vw-on .vw-bar:nth-child(3) { animation-delay: 0.4s; }
.vw-on .vw-bar:nth-child(4) { animation-delay: 0.1s; }
.vw-on .vw-bar:nth-child(5) { animation-delay: 0.3s; }

@keyframes vw-pulse {
  0%, 100% { transform: scaleY(0.45); }
  50% { transform: scaleY(1); }
}

@media (prefers-reduced-motion: reduce) {
  .vw-on .vw-bar {
    animation: none;
  }
}
</style>
