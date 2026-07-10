<script setup lang="ts">
// JCLAW-680: the shared /api/config store, page-wide inline config-row editor,
// and /api/providers billing projection live in a composable that provides a
// reactive context to the extracted panels. The page wires up that context and
// awaits the two async handles to preserve the original single-Suspense load;
// every section now lives in its own Settings*Panel component, each of which
// injects the shared context and owns its section state.
const { asyncConfig, asyncProviders } = useProvideSettingsConfig()
await asyncConfig
await asyncProviders
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Settings
    </h1>

    <SettingsGeneralPanel />

    <SettingsLoggingPanel />

    <SettingsProvidersPanel />

    <SettingsSearchPanel />

    <SettingsOcrPanel />

    <SettingsTranscriptionPanel />

    <SettingsImageCaptionPanel />

    <SettingsImageGenPanel />

    <SettingsVideoInterpPanel />

    <SettingsVideoGenPanel />

    <SettingsChatPanel />

    <SettingsSubagentsPanel />

    <SettingsTasksPanel />

    <SettingsPerformancePanel />

    <SettingsUploadsPanel />

    <SettingsSkillsPanel />

    <SettingsShellPanel />

    <SettingsMalwarePanel />

    <SettingsPasswordPanel />

    <SettingsUnmanagedPanel />
  </div>
</template>
