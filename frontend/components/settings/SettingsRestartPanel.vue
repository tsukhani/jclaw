<script setup lang="ts">
// Instance restart panel. Hands off to `jclaw.sh restart` via
// POST /api/system/restart, then watches the backend go down and come back.
//
// The preflight is deliberately re-fetched on click rather than trusted from
// mount: the in-flight counts it reports are what the operator confirms
// against, and a panel left open for ten minutes would be confirming against
// a stale picture of what the restart is about to interrupt.

interface RestartPreflight {
  available: boolean
  unavailableReason: string | null
  mode: string
  backendOnly: boolean
  rebuildExpected: boolean
  runningTasks: number
  activeSubagentRuns: number
}

const { confirm } = useConfirm()
const { mutate, error: mutationError } = useApiMutation()

// useLazyFetch, not useFetch: a top-level `await` here suspends the whole
// settings panel behind this request on a cold boot.
const { data: preflight, refresh } = useLazyFetch<RestartPreflight>('/api/system/restart')

type Phase = 'idle' | 'requesting' | 'stopping' | 'starting'
const phase = ref<Phase>('idle')
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

/** True when /api/status answers at all. Any throw — connection refused mid-restart
 *  included — is a "down", which is exactly the signal the phases key off. */
async function backendUp(): Promise<boolean> {
  try {
    await $fetch('/api/status', { retry: 0, timeout: 4000 })
    return true
  }
  catch {
    return false
  }
}

/** Human summary of what this restart interrupts, for the confirm dialog. */
function interruptionSummary(p: RestartPreflight): string {
  const parts: string[] = []
  if (p.runningTasks > 0) {
    parts.push(`${p.runningTasks} task run${p.runningTasks === 1 ? '' : 's'}`)
  }
  if (p.activeSubagentRuns > 0) {
    parts.push(`${p.activeSubagentRuns} subagent run${p.activeSubagentRuns === 1 ? '' : 's'}`)
  }
  if (parts.length === 0) return 'No task or subagent runs are currently active.'
  return `${parts.join(' and ')} currently running will be interrupted.`
}

function durationHint(p: RestartPreflight): string {
  if (p.rebuildExpected) {
    // Names the driver rather than asserting a duration, because the slow case
    // has never been measured. Two timed restarts on a developer clone: 48s
    // with both steps skipped, 58s with a full SPA rebuild — so the SPA is
    // worth ~10s, not minutes. Both logged compileJava UP-TO-DATE, leaving a
    // cold Java recompile the one step that could plausibly take much longer
    // and the one step still unmeasured. Don't put a number here until it is.
    return 'This is a source checkout — the restart may recompile Java sources and '
      + 'rebuild the SPA, and skips both when nothing changed. Usually well under a '
      + 'minute; a cold Java recompile takes longer.'
  }
  return p.backendOnly
    ? 'The backend will be unavailable for roughly 30–60 seconds. The dev server stays up.'
    : 'JClaw will be unavailable for roughly 30–60 seconds.'
}

async function handleRestart() {
  failure.value = null

  // Fresh counts — see the note at the top of this file.
  await refresh()
  const p = preflight.value
  if (!p || !p.available) return

  const ok = await confirm({
    title: 'Restart JClaw',
    message: `${interruptionSummary(p)} ${durationHint(p)} `
      + 'This page reconnects automatically once the backend is back.',
    confirmText: 'Restart',
    variant: 'danger',
  })
  if (!ok) return

  phase.value = 'requesting'
  const res = await mutate('/api/system/restart', { method: 'POST' })
  if (!res) {
    phase.value = 'idle'
    // The backend names the reason it refused; the generic line only stands in
    // when the request never got an answer at all.
    failure.value = mutationError.value
      ? `${mutationError.value} The instance is still running.`
      : 'The restart request was rejected. The instance is still running.'
    return
  }

  watchForReturn(p)
}

/**
 * Two-phase reconnect. The backend deliberately stays up for a couple of
 * seconds so the 202 can reach us, so "poll until /api/status answers" would
 * match the dying JVM and reload before the restart began. Wait for it to go
 * down first, then for it to come back.
 */
function watchForReturn(p: RestartPreflight) {
  phase.value = 'stopping'
  elapsed.value = 0

  // A source-checkout restart recompiles and may rebuild the SPA; the flat
  // budget that fits a dist restart would time out well before it finishes.
  const startupBudget = p.rebuildExpected ? 900 : 180
  const shutdownBudget = 90
  let consecutiveUp = 0

  stopPolling()
  poller = setInterval(async () => {
    elapsed.value += 1
    const up = await backendUp()

    if (phase.value === 'stopping') {
      if (!up) {
        phase.value = 'starting'
        elapsed.value = 0
      }
      else if (elapsed.value >= shutdownBudget) {
        stopPolling()
        phase.value = 'idle'
        failure.value = `The backend was still responding ${shutdownBudget}s after the restart `
          + 'was accepted. Check logs/restart.log.'
      }
      return
    }

    // starting — require two consecutive successes so a half-initialised Play
    // answering one request mid-boot doesn't trigger a premature reload.
    if (up) {
      consecutiveUp += 1
      if (consecutiveUp >= 2) {
        stopPolling()
        reloadNuxtApp({ persistState: false })
      }
      return
    }
    consecutiveUp = 0

    if (elapsed.value >= startupBudget) {
      stopPolling()
      phase.value = 'idle'
      failure.value = `The backend did not come back within ${startupBudget}s. `
        + 'Check logs/restart.log.'
    }
  }, 1000)
}

const busy = computed(() => phase.value !== 'idle')

const statusLine = computed(() => {
  switch (phase.value) {
    case 'requesting': return 'Handing off to the restart helper…'
    case 'stopping': return `Waiting for the backend to stop… (${elapsed.value}s)`
    case 'starting': return `Waiting for the backend to come back… (${elapsed.value}s)`
    default: return ''
  }
})
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Restart
    </h2>
    <p class="text-xs text-fg-muted">
      Stops this instance and starts it again via <code>jclaw.sh restart</code>. In-flight chat
      streams, running task runs and active subagent runs are interrupted. This page reconnects
      on its own once the backend answers again.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Restart JClaw</span>
          <div class="text-xs text-fg-muted mt-0.5">
            <template v-if="preflight && !preflight.available">
              {{ preflight.unavailableReason }}
            </template>
            <template v-else-if="preflight">
              Mode: {{ preflight.mode }}<template v-if="preflight.backendOnly">
                — backend only; the Nuxt dev server keeps running
              </template>
              <template v-if="preflight.rebuildExpected">
                — source checkout, so this may recompile first
              </template>
            </template>
            <template v-else>
              Checking restart availability…
            </template>
          </div>
        </div>
        <button
          :disabled="busy || !preflight?.available"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-white
                 bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                 disabled:cursor-not-allowed rounded-full transition-colors"
          @click="handleRestart"
        >
          {{ busy ? 'Restarting…' : 'Restart' }}
        </button>
      </div>

      <div
        v-if="statusLine || failure"
        class="px-4 py-2.5 border-t border-border text-xs"
        role="status"
        aria-live="polite"
      >
        <span
          v-if="failure"
          class="text-red-600 dark:text-red-400"
        >{{ failure }}</span>
        <span
          v-else
          class="text-fg-muted"
        >{{ statusLine }}</span>
      </div>
    </div>
  </div>
</template>
