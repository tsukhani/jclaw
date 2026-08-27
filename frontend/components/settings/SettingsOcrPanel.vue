<script setup lang="ts">
// OCR settings panel (JCLAW-680). Backends that extract text from
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

// JCLAW-1108: "Install for me" for a missing Tesseract. Fetched with $fetch inside
// onMounted rather than a top-level await useFetch — this panel already has one, and
// a second would extend the suspense that stalls Settings on a cold boot.
interface InstallPlan { manager: string | null, command: string, runnable: boolean, reason: string | null }
interface InstallState { status: string, manager: string | null, command: string | null, output: string | null, hint: string | null }

const installPlan = ref<InstallPlan | null>(null)
const installState = ref<InstallState | null>(null)
const installing = ref(false)
let pollHandle: ReturnType<typeof setTimeout> | null = null

const tesseractMissing = computed(() =>
  (ocrStatus.value?.providers ?? []).some(p => p.name === 'tesseract' && !p.available))

onMounted(async () => {
  if (!tesseractMissing.value) return
  try {
    installPlan.value = await $fetch<InstallPlan>('/api/ocr/tesseract/install-plan')
  }
  catch { /* non-fatal: the static install hint below still tells the operator what to do */ }
})

onBeforeUnmount(() => {
  if (pollHandle) clearTimeout(pollHandle)
})

async function pollInstall() {
  try {
    installState.value = await $fetch<InstallState>('/api/ocr/tesseract/install-state')
  }
  catch { /* keep the last state; the next tick retries */ }
  if (installState.value?.status === 'RUNNING') {
    pollHandle = setTimeout(pollInstall, 2000)
  }
  else {
    installing.value = false
    // A success only changes anything after a restart, but re-probe anyway so the
    // row stops claiming the binary is missing once the process does pick it up.
    if (installState.value?.status === 'SUCCEEDED') refreshOcrStatus()
  }
}

async function startInstall() {
  installing.value = true
  try {
    installState.value = await $fetch<InstallState>('/api/ocr/tesseract/install', { method: 'POST' })
    if (installState.value?.status === 'RUNNING') pollHandle = setTimeout(pollInstall, 1500)
    else installing.value = false
  }
  catch (e) {
    installing.value = false
    const data = (e as { data?: { error?: string } }).data
    installState.value = {
      status: 'FAILED', manager: null, command: installPlan.value?.command ?? null, output: null,
      hint: data?.error ?? (e instanceof Error ? e.message : 'Could not start the install'),
    }
  }
}

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
  <!-- OCR -->
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
            class="text-[10px] text-green-700 dark:text-green-400 border border-green-400/30 px-1"
          >active</span>
          <span
            v-else-if="backend.available && !backend.enabled"
            class="text-[10px] text-fg-muted border border-input px-1"
          >disabled</span>
          <span
            v-else
            class="text-[10px] text-amber-700 dark:text-amber-400 border border-amber-400/40 px-1"
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
          class="block mt-1 text-amber-700 dark:text-amber-400"
        >{{ backend.installHint }}</span>

        <!-- JCLAW-1108: offer to run the install. Shown only when the binary is
             missing, and only for tesseract — the plan endpoint is engine-specific. -->
        <div
          v-if="!backend.available && backend.name === 'tesseract' && installPlan"
          class="mt-2 space-y-1.5"
        >
          <p class="font-mono text-[11px] text-fg-primary break-all">
            {{ installPlan.command }}
          </p>
          <button
            v-if="installPlan.runnable"
            type="button"
            :disabled="installing"
            class="inline-flex items-center gap-1 px-2 py-1 text-xs bg-muted border border-emerald-600 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-600/10 disabled:opacity-50 cursor-pointer"
            @click="startInstall"
          >
            {{ installing ? 'Installing…' : 'Install for me' }}
          </button>
          <!-- Not runnable: no button, because pressing it could only fail. The
               command above is the deliverable — on Linux we will not invoke sudo. -->
          <p
            v-if="installPlan.reason"
            class="text-[11px] text-fg-muted leading-snug"
          >
            {{ installPlan.reason }}
          </p>

          <p
            v-if="installState && installState.status !== 'RUNNING' && installState.hint"
            :class="installState.status === 'SUCCEEDED'
              ? 'text-[11px] text-emerald-700 dark:text-emerald-400 leading-snug'
              : 'text-[11px] text-red-700 dark:text-red-400 leading-snug'"
          >
            {{ installState.hint }}
          </p>
          <details
            v-if="installState?.output"
            class="text-[11px] text-fg-muted"
          >
            <summary class="cursor-pointer select-none hover:text-fg-strong transition-colors">
              Installer output
            </summary>
            <pre class="mt-1 overflow-x-auto whitespace-pre-wrap border-l border-input pl-2.5 font-mono text-[10px]">{{ installState.output }}</pre>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>
