/**
 * The restart-and-reconnect sequence the Restart and Java runtime panels share: hand off to
 * `jclaw.sh restart` via POST /api/system/restart, then watch the backend go down and come back.
 */

export interface RestartPreflight {
  available: boolean
  unavailableReason: string | null
  mode: string
  backendOnly: boolean
  rebuildExpected: boolean
  runningTasks: number
  activeSubagentRuns: number
  aotCacheEnabled: boolean
  aotCacheStatus: string | null
  aotCacheStatusAt: string | null
}

/** Human summary of what this restart interrupts, for the confirm dialog. */
export function interruptionSummary(p: RestartPreflight): string {
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

type Phase = 'idle' | 'requesting' | 'stopping' | 'starting'

export function useRestartAndReconnect() {
  const { mutate, error: mutationError } = useApiMutation()

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

  /** POST the restart, then follow it; the caller has already confirmed against `p`. */
  async function restart(p: Pick<RestartPreflight, 'rebuildExpected'>) {
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
  function watchForReturn(p: Pick<RestartPreflight, 'rebuildExpected'>) {
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

  return { phase, elapsed, failure, busy, statusLine, restart }
}
