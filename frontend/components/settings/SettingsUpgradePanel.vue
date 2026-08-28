<script setup lang="ts">
// Instance upgrade panel. Hands off to `jclaw.sh upgrade` via
// POST /api/system/upgrade, then follows the helper through the download and
// out the other side of the restart it ends with.
//
// This is not the restart panel with a different verb. A restart goes down
// within two seconds, so that panel can treat "backend stopped answering" as
// the start signal. An upgrade downloads and unpacks a ~400 MB release BEFORE
// it stops anything, so the instance stays fully usable for minutes after the
// 202 — during which the only progress signal is the helper's status file.
// Hence two tracking modes: read the status file while we can still reach it,
// then fall back to the down-then-up watcher once the swap actually begins.

interface UpgradePreflight {
  available: boolean
  unavailableReason: string | null
  currentVersion: string
  latestVersion: string | null
  upgradeAvailable: boolean
  installKind: string
  runningTasks: number
  activeSubagentRuns: number
  /** Null on a packaged install, which ships without a repository to report. */
  commit: string | null
}

interface UpgradeStatus {
  phase: string
  pct: number
  message: string
  fromVersion: string
  toVersion: string
  startedAt: string
}

const { confirm } = useConfirm()
const { mutate, error: mutationError } = useApiMutation()

// useLazyFetch, not useFetch: a top-level `await` here suspends the whole
// settings panel behind a request that may be waiting on GitHub.
const { data: preflight, refresh } = useLazyFetch<UpgradePreflight>('/api/system/upgrade')

// Reported by the helper. Present on mount when a previous upgrade ran here —
// including the one that installed the version now serving this page.
const { data: lastStatus } = useLazyFetch<UpgradeStatus | null>('/api/system/upgrade/status')

type Mode = 'idle' | 'working' | 'down' | 'checking'
const mode = ref<Mode>('idle')
const live = ref<UpgradeStatus | null>(null)
const elapsed = ref(0)
const failure = ref<string | null>(null)

let poller: ReturnType<typeof setInterval> | null = null

onBeforeUnmount(stopPolling)

function stopPolling() {
  if (poller) {
    clearInterval(poller)
    poller = null
  }
}

/** Reads the helper's status file. Throws when the backend is down, which is
 *  itself the signal that the swap has started. */
async function fetchStatus(): Promise<UpgradeStatus | null> {
  const res = await $fetch<UpgradeStatus | null>('/api/system/upgrade/status', {
    retry: 0,
    timeout: 4000,
  })
  // 204 while no upgrade has ever run here.
  return res && typeof res === 'object' ? res : null
}

async function checkAgain() {
  mode.value = 'checking'
  failure.value = null
  try {
    await refresh()
  }
  finally {
    mode.value = 'idle'
  }
}

/** Human summary of what the restart at the end of the upgrade interrupts. */
function interruptionSummary(p: UpgradePreflight): string {
  const parts: string[] = []
  if (p.runningTasks > 0) {
    parts.push(`${p.runningTasks} task run${p.runningTasks === 1 ? '' : 's'}`)
  }
  if (p.activeSubagentRuns > 0) {
    parts.push(`${p.activeSubagentRuns} subagent run${p.activeSubagentRuns === 1 ? '' : 's'}`)
  }
  if (parts.length === 0) return 'No task or subagent runs are currently active.'
  return `${parts.join(' and ')} currently running will be interrupted when it restarts.`
}

async function handleUpgrade() {
  failure.value = null

  // Fresh counts and a fresh release check — the panel may have been open for
  // a while, and the confirmation is about what is true now.
  await refresh()
  const p = preflight.value
  if (!p || !p.available || !p.upgradeAvailable) return

  const ok = await confirm({
    title: `Upgrade to ${p.latestVersion}`,
    message: `JClaw ${p.latestVersion} will be downloaded and verified while this instance keeps `
      + 'running, then installed and restarted. Your data, workspace, credentials and installed '
      + `apps are preserved, and the database is backed up first. ${interruptionSummary(p)} `
      + 'If the new version fails to start, it is rolled back automatically.',
    confirmText: 'Upgrade',
    variant: 'danger',
  })
  if (!ok) return

  mode.value = 'working'
  elapsed.value = 0
  live.value = null

  const res = await mutate('/api/system/upgrade', { method: 'POST' })
  if (!res) {
    mode.value = 'idle'
    failure.value = mutationError.value
      ? `${mutationError.value} This instance is unchanged.`
      : 'The upgrade request was rejected. This instance is unchanged.'
    return
  }

  watchUpgrade()
}

/**
 * Follow the helper. While the backend answers, the status file is the source
 * of truth; once it stops answering the swap is under way and we wait for the
 * new version to come up, exactly as the restart panel does.
 */
function watchUpgrade() {
  let consecutiveUp = 0
  // Generous: this covers a ~400 MB download on a slow link plus the restart.
  // The helper's own health gate is what actually decides success or rollback,
  // so overrunning here only means the operator reloads by hand.
  const budget = 3600
  const shutdownBudget = 120

  stopPolling()
  poller = setInterval(async () => {
    elapsed.value += 2

    let status: UpgradeStatus | null = null
    let up = true
    try {
      status = await fetchStatus()
    }
    catch {
      up = false
    }
    if (status) live.value = status

    if (mode.value === 'working') {
      if (!up) {
        mode.value = 'down'
        elapsed.value = 0
        return
      }
      // The helper reports its own pre-downtime failures (download, checksum,
      // disk) and stays up — without this the panel would poll a finished
      // upgrade forever.
      if (status?.phase === 'failed') {
        stopPolling()
        mode.value = 'idle'
        failure.value = status.message || 'The upgrade failed before anything was changed.'
        return
      }
      if (elapsed.value >= budget) {
        stopPolling()
        mode.value = 'idle'
        failure.value = 'The upgrade did not finish within an hour. Check logs/upgrade.log.'
      }
      return
    }

    // down — waiting for the swap to complete and a version to answer again.
    if (up) {
      consecutiveUp += 1
      // Two consecutive successes, so a half-initialised Play answering one
      // request mid-boot doesn't trigger a premature reload.
      if (consecutiveUp >= 2) {
        stopPolling()
        reloadNuxtApp({ persistState: false })
      }
      return
    }
    consecutiveUp = 0

    if (elapsed.value >= shutdownBudget + budget) {
      stopPolling()
      mode.value = 'idle'
      failure.value = 'The instance did not come back. Check logs/upgrade.log.'
    }
  }, 2000)
}

const busy = computed(() => mode.value === 'working' || mode.value === 'down')

const statusLine = computed(() => {
  if (mode.value === 'down') {
    return `Installing and restarting… (${elapsed.value}s)`
  }
  if (mode.value !== 'working') return ''
  const s = live.value
  if (!s) return 'Handing off to the upgrade helper…'
  const pct = s.phase === 'downloading' && s.pct > 0 ? ` ${s.pct}%` : ''
  return `${s.message}${pct}`
})

/** Outcome of the previous upgrade, when this page is the result of one. */
const previousOutcome = computed(() => {
  if (busy.value) return null
  const s = live.value ?? lastStatus.value
  if (!s) return null
  if (s.phase === 'done') return { ok: true, text: `Upgraded ${s.fromVersion} → ${s.toVersion}.` }
  if (s.phase === 'rolled-back') {
    return {
      ok: false,
      text: `The upgrade to ${s.toVersion} failed to start and was rolled back to ${s.fromVersion}. `
        + 'Check logs/upgrade.log.',
    }
  }
  return null
})

const summaryLine = computed(() => {
  const p = preflight.value
  if (!p) return 'Checking for updates…'
  if (!p.latestVersion) {
    return p.available
      ? `Version ${p.currentVersion} — could not reach GitHub to check for updates.`
      : p.unavailableReason ?? ''
  }
  // Version state outranks unavailability: an install that cannot upgrade itself
  // still has nothing to be told to do while it is already on the newest release.
  if (!p.upgradeAvailable) return `Version ${p.currentVersion} — up to date.`
  if (!p.available) return `Version ${p.currentVersion} — ${p.latestVersion} is available. ${p.unavailableReason}`
  return `Version ${p.currentVersion} — ${p.latestVersion} is available.`
})
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Upgrade and restart
    </h2>
    <p class="text-xs text-fg-muted">
      Downloads the newest release and installs it in place via <code>jclaw.sh upgrade</code>, then
      restarts. Your database, workspace, credentials, installed apps and edited configuration are
      kept, and the database is backed up first. The download runs while JClaw keeps serving — you
      can leave this page and come back.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">JClaw {{ preflight?.currentVersion ?? '' }}</span>
          <div class="text-xs text-fg-muted mt-0.5">
            {{ summaryLine }}
          </div>
          <div
            v-if="preflight?.commit"
            class="text-xs text-fg-muted mt-0.5 font-mono"
          >
            Commit {{ preflight.commit }}
          </div>
        </div>
        <div class="shrink-0 flex items-center gap-2">
          <button
            v-if="preflight?.available && !preflight.upgradeAvailable"
            :disabled="busy || mode === 'checking'"
            class="px-3 py-1.5 text-xs font-medium text-fg-strong border border-border
                   hover:bg-surface disabled:opacity-50 disabled:cursor-not-allowed
                   rounded-full transition-colors"
            @click="checkAgain"
          >
            {{ mode === 'checking' ? 'Checking…' : 'Check again' }}
          </button>
          <button
            v-if="preflight?.available && preflight.upgradeAvailable"
            :disabled="busy"
            class="px-3 py-1.5 text-xs font-medium text-white
                   bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                   disabled:cursor-not-allowed rounded-full transition-colors"
            @click="handleUpgrade"
          >
            {{ busy ? 'Upgrading…' : `Upgrade to ${preflight.latestVersion}` }}
          </button>
        </div>
      </div>

      <div
        v-if="statusLine || failure || previousOutcome"
        class="px-4 py-2.5 border-t border-border text-xs"
        role="status"
        aria-live="polite"
      >
        <span
          v-if="failure"
          class="text-red-600 dark:text-red-400"
        >{{ failure }}</span>
        <span
          v-else-if="statusLine"
          class="text-fg-muted"
        >{{ statusLine }}</span>
        <span
          v-else-if="previousOutcome"
          :class="previousOutcome.ok ? 'text-fg-muted' : 'text-red-600 dark:text-red-400'"
        >{{ previousOutcome.text }}</span>
      </div>
    </div>
  </div>
</template>
