<script setup lang="ts">
// The phases of a restart as useRestartAndReconnect narrates them, for the Restart and Java runtime panels.
import type { RestartStep } from '~/composables/useRestartAndReconnect'

defineProps<{ steps: RestartStep[] }>()
</script>

<template>
  <ol
    class="space-y-0.5"
    data-testid="restart-steps"
  >
    <li
      v-for="step in steps"
      :key="step.id"
      :data-state="step.state"
      :class="[
        'flex items-baseline justify-between gap-3',
        step.state === 'failed' ? 'text-red-600 dark:text-red-400'
        : step.state === 'running' ? 'text-fg-strong' : 'text-fg-muted',
      ]"
    >
      <span>{{ step.label }}{{ step.state === 'running' ? '…' : '' }}</span>
      <!-- A running count would be read out every second; a finished time is read once. -->
      <span
        v-if="step.seconds !== null"
        class="shrink-0 font-mono tabular-nums"
        :aria-hidden="step.state === 'running' ? 'true' : undefined"
      >{{ step.seconds }}s</span>
    </li>
  </ol>
</template>
