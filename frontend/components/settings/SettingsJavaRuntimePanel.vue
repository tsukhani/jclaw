<script setup lang="ts">
// Java runtime panel. On the Java the installer put beside JClaw, POST /api/system/java-runtime
// downloads the newest patch of the same major into jre.staged/ while JClaw keeps running, and
// jclaw.sh installs it at the next start; the restart that does it stays the operator's choice.
// Anywhere else the panel only says whether a newer patch exists.
import type { RestartPreflight } from '~/composables/useRestartAndReconnect'
import { interruptionSummary } from '~/composables/useRestartAndReconnect'

interface JavaRuntimePreflight {
  kind: 'built-in' | 'external' | 'container'
  currentVersion: string
  vendor: string | null
  latestVersion: string | null
  updateAvailable: boolean
  stagedVersion: string | null
  downloading: boolean
  downloadPercent: number | null
  downloadError: string | null
  lastStart: string | null
  lastStartAt: string | null
  rebuildsStartupCache: boolean
}

const ENDPOINT = '/api/system/java-runtime'
const POLL_MS = 1000

const { confirm } = useConfirm()
const { mutate, error: mutationError } = useApiMutation()
const { failure: restartFailure, busy: restarting, statusLine: restartLine, steps: restartSteps, restart } = useRestartAndReconnect()

// useLazyFetch, not useFetch: a top-level `await` here suspends the whole
// settings panel behind a request that may be waiting on Azul.
const { data: runtime, refresh } = useLazyFetch<JavaRuntimePreflight>(ENDPOINT)

const cacheNote = computed(() => runtime.value?.rebuildsStartupCache
  ? 'The first start on it rebuilds the startup cache, so it takes longer than usual. '
  : '')

const failure = ref<string | null>(null)
const checking = ref(false)
const cancelling = ref(false)

// A download runs in the JVM, so the panel only follows it: one GET a second while it lasts.
let poller: ReturnType<typeof setTimeout> | null = null
let unmounted = false

function stopPolling() {
  if (poller) {
    clearTimeout(poller)
    poller = null
  }
}

function followDownload() {
  stopPolling()
  poller = setTimeout(async () => {
    poller = null
    try {
      const r = await $fetch<JavaRuntimePreflight>(ENDPOINT)
      if (unmounted) return
      runtime.value = r
    }
    catch {
      // transient — the next tick retries
    }
    if (!unmounted && runtime.value?.downloading) followDownload()
  }, POLL_MS)
}

watch(() => runtime.value?.downloading, (downloading) => {
  if (downloading && !poller) followDownload()
}, { immediate: true })

onBeforeUnmount(() => {
  unmounted = true
  stopPolling()
})

// refresh() would read the server's hour-long catalog cache; refresh=true asks Azul again.
async function checkAgain() {
  checking.value = true
  failure.value = null
  try {
    runtime.value = await $fetch<JavaRuntimePreflight>(ENDPOINT, { query: { refresh: true } })
  }
  catch (e) {
    failure.value = apiErrorDetails(e).message
  }
  finally {
    checking.value = false
  }
}

async function download() {
  failure.value = null
  const r = runtime.value
  if (!r?.latestVersion) return
  const ok = await confirm({
    title: `Download Java ${r.latestVersion}`,
    message: `Java ${r.latestVersion} will be downloaded from Azul and checked against its SHA-256 `
      + `while JClaw keeps running on Java ${r.currentVersion}. It is installed the next time JClaw `
      + 'starts, and the current Java is kept as jre.previous.',
    confirmText: 'Download',
  })
  if (!ok) return

  const res = await mutate(ENDPOINT, { method: 'POST' })
  if (!res) {
    failure.value = mutationError.value ?? 'The download was refused.'
  }
  await refresh()
}

async function cancelStaged() {
  failure.value = null
  cancelling.value = true
  const res = await mutate(ENDPOINT, { method: 'DELETE' })
  if (!res) {
    failure.value = mutationError.value ?? 'The downloaded Java was not removed.'
  }
  await refresh()
  cancelling.value = false
}

async function restartNow() {
  failure.value = null
  restartFailure.value = null
  const staged = runtime.value?.stagedVersion
  if (!staged) return

  // Fresh counts: they are what the operator confirms against.
  let p: RestartPreflight
  try {
    p = await $fetch<RestartPreflight>('/api/system/restart')
  }
  catch (e) {
    restartFailure.value = apiErrorDetails(e).message
    return
  }
  if (!p.available) {
    restartFailure.value = p.unavailableReason
    return
  }

  const ok = await confirm({
    title: 'Restart JClaw',
    message: `${interruptionSummary(p)} Java ${staged} is installed as JClaw starts again. ${cacheNote.value}`
      + 'This page reconnects automatically once the backend is back.',
    confirmText: 'Restart',
    variant: 'danger',
  })
  if (!ok) return

  await restart(p)
}

const canDownload = computed(() => {
  const r = runtime.value
  return !!r && r.kind === 'built-in' && r.updateAvailable && !r.stagedVersion && !r.downloading
})

const summaryLine = computed(() => {
  const r = runtime.value
  if (!r) return 'Checking for a newer Java…'
  if (r.stagedVersion) return `Java ${r.stagedVersion} will be installed at the next restart. ${cacheNote.value}`.trim()
  if (r.downloading) return `Downloading Java ${r.latestVersion}…`
  if (!r.latestVersion) return 'Couldn\'t check for a newer Java.'
  if (!r.updateAvailable) return 'Up to date.'
  if (r.kind === 'container') return `Java ${r.latestVersion} is available. The runtime comes with the Docker image.`
  if (r.kind === 'external') return `Java ${r.latestVersion} is available from your JDK's vendor.`
  return `Java ${r.latestVersion} is available.`
})

const lastStartAt = computed(() => {
  const at = runtime.value?.lastStartAt
  return at ? new Date(at).toLocaleString() : null
})

const busy = computed(() => restarting.value || cancelling.value)

const errorLine = computed(() =>
  failure.value ?? restartFailure.value ?? (runtime.value?.downloading ? null : runtime.value?.downloadError ?? null))
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Java runtime
    </h2>
    <p class="text-xs text-fg-muted">
      The Java that JClaw runs on. When it is the Java the installer set up, JClaw can download the
      newest patch of the same version while it keeps running, and installs it the next time it starts.
      The previous Java is kept beside it as <code>jre.previous</code>.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Java {{ runtime?.currentVersion ?? '' }}</span>
          <span
            v-if="runtime?.vendor"
            class="ml-2 text-xs text-fg-muted"
          >{{ runtime.vendor }}</span>
          <div
            class="text-xs text-fg-muted mt-0.5"
            data-testid="java-runtime-summary"
          >
            {{ summaryLine }}
          </div>
          <div
            v-if="runtime?.lastStart"
            class="text-xs text-fg-muted mt-0.5"
            data-testid="java-runtime-last-start"
          >
            Last start: {{ runtime.lastStart }}<template v-if="lastStartAt">
              ({{ lastStartAt }})
            </template>
          </div>
        </div>
        <div class="shrink-0 flex items-center gap-2">
          <button
            v-if="runtime && !runtime.latestVersion && !runtime.stagedVersion && !runtime.downloading"
            :disabled="checking"
            class="px-3 py-1.5 text-xs font-medium text-fg-strong border border-border
                   hover:bg-surface disabled:opacity-50 disabled:cursor-not-allowed
                   rounded-full transition-colors"
            @click="checkAgain"
          >
            {{ checking ? 'Checking…' : 'Check again' }}
          </button>
          <button
            v-if="canDownload"
            class="px-3 py-1.5 text-xs font-medium text-white
                   bg-emerald-600 hover:bg-emerald-700 rounded-full transition-colors"
            @click="download"
          >
            Download Java {{ runtime?.latestVersion }}
          </button>
          <template v-if="runtime?.stagedVersion">
            <button
              :disabled="busy"
              class="px-3 py-1.5 text-xs font-medium text-fg-strong border border-border
                     hover:bg-surface disabled:opacity-50 disabled:cursor-not-allowed
                     rounded-full transition-colors"
              @click="cancelStaged"
            >
              Cancel
            </button>
            <button
              :disabled="busy"
              class="px-3 py-1.5 text-xs font-medium text-white
                     bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                     disabled:cursor-not-allowed rounded-full transition-colors"
              @click="restartNow"
            >
              {{ restarting ? 'Restarting…' : 'Restart now' }}
            </button>
          </template>
        </div>
      </div>

      <div
        v-if="runtime?.downloading"
        class="px-4 py-2.5 border-t border-border flex items-center gap-3"
        data-testid="java-runtime-progress"
      >
        <div
          class="flex-1 h-2 bg-muted border border-input overflow-hidden"
          role="progressbar"
          :aria-label="`Downloading Java ${runtime.latestVersion}`"
          :aria-valuenow="runtime.downloadPercent ?? undefined"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div
            class="h-full bg-emerald-600 transition-[width] duration-300"
            :style="{ width: (runtime.downloadPercent ?? 0) + '%' }"
          />
        </div>
        <span class="text-xs font-mono text-fg-muted tabular-nums w-10 text-right">
          {{ runtime.downloadPercent != null ? runtime.downloadPercent + '%' : '' }}
        </span>
      </div>

      <div
        v-if="errorLine || restartLine || restartSteps.length"
        class="px-4 py-2.5 border-t border-border text-xs"
        role="status"
        aria-live="polite"
      >
        <SettingsRestartSteps
          v-if="restartSteps.length"
          :steps="restartSteps"
        />
        <span
          v-if="errorLine"
          :class="['text-red-600 dark:text-red-400', restartSteps.length ? 'block mt-1' : '']"
        >{{ errorLine }}</span>
        <span
          v-else-if="!restartSteps.length"
          class="text-fg-muted"
        >{{ restartLine }}</span>
      </div>
    </div>
  </div>
</template>
