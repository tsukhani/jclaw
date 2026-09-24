<script setup lang="ts">
// The browser tool's first-use download, shown while the streaming turn's browser call waits on
// it. It sits beside the stream-progress line rather than inside the reply's bubble, because a
// tool-first turn keeps that bubble hidden until its first content (shouldDisplayMessage).
import { GlobeAltIcon } from '@heroicons/vue/24/outline'
import type { BrowserSetupStatus } from '~/composables/useBrowserSetup'

defineProps<{ setup: BrowserSetupStatus }>()
</script>

<template>
  <div
    class="flex items-center gap-2.5 bg-surface-elevated border border-border rounded-xl px-3 py-2 text-xs text-fg-strong"
    data-testid="browser-setup-progress"
  >
    <GlobeAltIcon
      class="w-4 h-4 shrink-0 text-sky-500"
      aria-hidden="true"
    />
    <div class="flex flex-col gap-1 min-w-0 flex-1">
      <span class="font-medium">
        Setting up the browser: {{ setup.step ?? 'preparing' }}…<template v-if="setup.percent != null">
          {{ setup.percent }}%</template>
      </span>
      <div
        class="h-1 w-full rounded-full bg-border overflow-hidden"
        role="progressbar"
        aria-label="Browser setup progress"
        :aria-valuenow="setup.percent ?? undefined"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <div
          class="h-full bg-sky-500 transition-[width] duration-500"
          :style="{ width: (setup.percent ?? 0) + '%' }"
        />
      </div>
      <span class="text-fg-muted">A one-time download; later browser calls start straight away.</span>
    </div>
  </div>
</template>
