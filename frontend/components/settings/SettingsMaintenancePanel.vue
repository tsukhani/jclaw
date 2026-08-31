<script setup lang="ts">
// Maintenance section (JCLAW-1057): the operator actions that change this
// instance, ordered by how often one is wanted — upgrading is the reason to open
// this page, restarting is the follow-up, and a password reset is rare enough
// that it sits last rather than under the cursor on arrival.
//
// Composition only. Each panel keeps its own heading, preflight fetch and
// confirmation, and all three root at `mb-6 space-y-4`, so they separate
// themselves and this adds no spacing of its own.
//
// Runtime and log figures deliberately live elsewhere: JVM state sits with the
// dispatcher tuning it informs (Performance) and log disk usage with the levels
// that produce it (Logging).
import SettingsUpgradePanel from './SettingsUpgradePanel.vue'
import SettingsRestartPanel from './SettingsRestartPanel.vue'
import SettingsPasswordPanel from './SettingsPasswordPanel.vue'
</script>

<template>
  <div>
    <SettingsUpgradePanel />
    <SettingsRestartPanel />
    <SettingsPasswordPanel />
  </div>
</template>
