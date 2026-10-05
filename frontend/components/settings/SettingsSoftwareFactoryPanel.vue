<script setup lang="ts">
// The Software Factory panel (JCLAW-1393); Setup is its first section.
import FactorySetupSection from './FactorySetupSection.vue'
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Software Factory
    </h2>
    <p class="text-xs text-fg-muted">
      The optional AFK factory builds Jira stories labelled <span class="font-mono">afk</span> unattended in
      sandboxes on this Mac and hands each back as a local <span class="font-mono">agent/&lt;KEY&gt;</span> branch.
    </p>
    <FactorySetupSection />
  </div>
</template>
