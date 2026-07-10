<script setup lang="ts">
// OCR Backends settings panel (JCLAW-680). Backends that extract text from
// images and scanned PDFs via the documents tool. The toggle is bound to a
// Config DB row but the render gates on the runtime probe — a host without the
// binary cannot flip the toggle on. Moved verbatim from pages/settings.vue;
// owns its own /api/ocr/status probe fetch (moved from the page).
import type { OcrStatusResponse } from '~/types/api'

const { saving, refresh } = useSettingsConfig()

// JCLAW-177 follow-up: probe state + Config DB toggle for the OCR section.
// Fetched separately from /api/config so the section can render the toggle
// as uninteractive when the binary isn't on PATH (probe.available=false),
// regardless of what the stored ocr.tesseract.enabled row says.
const { data: ocrStatus, refresh: refreshOcrStatus }
  = await useFetch<OcrStatusResponse>('/api/ocr/status')

// --- OCR backends ---
// Tesseract today; the response contract is array-shaped so JCLAW-179
// (GLM-OCR via ollama-local) can append a second entry without churn.
// The toggle is bound to a Config DB row but the *render* gates on the
// runtime probe — a host without the binary cannot flip the toggle on,
// matching the spec ("disabled and not selectable to be toggled").

async function toggleOcrBackend(backend: { name: string, configKey: string, available: boolean, enabled: boolean }) {
  if (!backend.available) return // probe says unavailable — toggle is inert
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: backend.configKey, value: backend.enabled ? 'false' : 'true' },
    })
    refreshOcrStatus()
    refresh()
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- OCR Backends -->
  <div
    class="mb-6 space-y-4"
    data-tour="ocr-backends"
  >
    <h2 class="text-sm font-medium text-fg-muted">
      OCR
    </h2>
    <p class="text-xs text-fg-muted">
      Backends that extract text from images and scanned PDFs via the <span class="text-fg-muted">documents</span> tool.
      A backend can be toggled only when its system dependency is detected on the host. Install the missing dependency and restart the JVM to enable.
    </p>
    <div
      v-for="backend in (ocrStatus?.providers ?? [])"
      :key="backend.name"
      :class="[
        'bg-surface-elevated border border-border',
        backend.available ? '' : 'opacity-60',
      ]"
    >
      <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
        <div class="flex items-center gap-2">
          <span class="text-sm font-medium text-fg-strong">{{ backend.displayName }}</span>
          <span
            v-if="backend.available && backend.enabled"
            class="text-[10px] text-green-400 border border-green-400/30 px-1"
          >active</span>
          <span
            v-else-if="backend.available && !backend.enabled"
            class="text-[10px] text-fg-muted border border-input px-1"
          >disabled</span>
          <span
            v-else
            class="text-[10px] text-amber-400 border border-amber-400/40 px-1"
            :title="backend.reason ?? 'binary not detected on PATH'"
          >not detected</span>
          <span
            v-if="backend.available && backend.version"
            class="text-[10px] text-fg-muted font-mono ml-1"
          >{{ backend.version }}</span>
        </div>
        <button
          :aria-label="`${backend.available && backend.enabled ? 'Disable' : 'Enable'} ${backend.displayName}`"
          :title="backend.available
            ? (backend.enabled ? 'Disable this backend' : 'Enable this backend')
            : 'Backend dependency is not installed — toggle is disabled'"
          :disabled="!backend.available"
          :class="[
            'relative w-9 h-5 rounded-full transition-colors',
            backend.available
              ? (backend.enabled ? 'bg-emerald-600 hover:bg-emerald-500 cursor-pointer' : 'bg-muted hover:bg-muted cursor-pointer')
              : 'bg-muted cursor-not-allowed',
          ]"
          @click="toggleOcrBackend(backend)"
        >
          <span
            :class="(backend.available && backend.enabled) ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
      </div>
      <div class="px-4 py-2.5 text-xs text-fg-muted leading-relaxed">
        {{ backend.description }}
        <span
          v-if="!backend.available"
          class="block mt-1 text-amber-400"
        >{{ backend.installHint }}</span>
      </div>
    </div>
  </div>
</template>
