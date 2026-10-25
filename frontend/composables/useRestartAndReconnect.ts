/**
 * The restart-and-reconnect sequence the Restart and Java runtime panels share: hand off to
 * `jclaw.sh restart` via POST /api/system/restart, then watch the backend go down and come back,
 * narrating jclaw.sh's phases from GET /api/system/restart/progress while it does.
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

/** One line of the narration; `seconds` is null on a line with no duration to show. */
export interface RestartStep {
  id: string
  label: string
  state: 'done' | 'running' | 'failed'
  seconds: number | null
}

/** GET /api/system/restart/progress, from JClaw or from the narrator that stands in while it is down. */
interface RestartProgress {
  source: 'narrator' | 'jclaw'
  state: 'running' | 'done' | 'failed' | 'none'
  failure: string | null
  steps: Array<{ id: string, label: string, state: RestartStep['state'], seconds: number }>
  services: { channels: string[], mcpConnected: number, mcpConfigured: number, settled: boolean } | null
}

type Phase = 'idle' | 'requesting' | 'stopping' | 'starting'

// jclaw.sh writes the same label as it launches the JVM, after the narrator has stepped aside.
const STARTING_LABEL = 'Starting JClaw (connecting channels)'
// The narrator binds within a second or two of the old JVM letting go; past this the panel counts instead.
const NARRATOR_GRACE = 5
// McpConnectionManager gives every server 20 s to connect at boot.
const SERVICES_BUDGET = 30

const CHANNEL_NAMES: Record<string, string> = { telegram: 'Telegram', slack: 'Slack', whatsapp: 'WhatsApp' }

function channelName(type: string): string {
  return CHANNEL_NAMES[type] ?? type.charAt(0).toUpperCase() + type.slice(1)
}

export function useRestartAndReconnect() {
  const { mutate, error: mutationError } = useApiMutation()

  const phase = ref<Phase>('idle')
  const elapsed = ref(0)
  const failure = ref<string | null>(null)
  const steps = ref<RestartStep[]>([])

  let poller: ReturnType<typeof setInterval> | null = null

  onBeforeUnmount(stopPolling)

  function stopPolling() {
    if (poller) {
      clearInterval(poller)
      poller = null
    }
  }

  /** True when /api/status answers at all. Any throw — connection refused mid-restart
   *  included — is a "down", which is exactly the signal the phases key off. The narrator
   *  answers it 503, so it reads as down too. */
  async function backendUp(): Promise<boolean> {
    try {
      await $fetch('/api/status', { retry: 0, timeout: 4000 })
      return true
    }
    catch {
      return false
    }
  }

  async function fetchProgress(): Promise<RestartProgress | null> {
    try {
      return await $fetch<RestartProgress>('/api/system/restart/progress', { retry: 0, timeout: 4000 })
    }
    catch {
      return null
    }
  }

  /** POST the restart, then follow it; the caller has already confirmed against `p`. */
  async function restart(p: Pick<RestartPreflight, 'rebuildExpected'>) {
    steps.value = []
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
    // This restart's steps reached the page, from the old JVM or the narrator.
    let narrated = false
    let ticking = false
    // Times, not tick counts: a tick waits on two fetches of up to 4 s each, and the ticks it skips meanwhile are lost.
    let phaseStartedAt = Date.now()
    let servicesSince: number | null = null
    let shown: RestartStep[] = []
    let shownAt = Date.now()
    const secondsSince = (at: number) => Math.floor((Date.now() - at) / 1000)

    function serviceSteps(services: RestartProgress['services'], waited: number): RestartStep[] {
      if (!services) return []
      const out: RestartStep[] = []
      if (services.channels.length > 0) {
        out.push({ id: 'channels', label: `Channels running: ${services.channels.map(channelName).join(', ')}`, state: 'done', seconds: null })
      }
      if (services.mcpConfigured > 0) {
        out.push({
          id: 'mcp',
          label: `MCP servers: ${services.mcpConnected} of ${services.mcpConfigured} connected`,
          state: services.settled ? 'done' : 'running',
          seconds: services.settled ? null : waited,
        })
      }
      return out
    }

    function setSteps(next: RestartStep[]) {
      shown = next
      shownAt = Date.now()
      steps.value = next
    }

    function show(progress: RestartProgress, waited = 0) {
      const phases = progress.state === 'none' ? [] : progress.steps.map(s => ({ ...s }))
      setSteps([...phases, ...serviceSteps(progress.services, waited)])
    }

    /** Between answers, a running step keeps counting from its last answer. */
    function count() {
      const since = secondsSince(shownAt)
      steps.value = shown.map(s => s.state === 'running' && s.seconds !== null ? { ...s, seconds: s.seconds + since } : s)
    }

    /**
     * The narrator steps aside just before the JVM launches, so a silence after this restart's steps is that
     * launch. In a quick restart the page may never reach the narrator in its few seconds at all.
     */
    function launching() {
      if (shown.some(s => s.id === 'start')) {
        count()
        return
      }
      setSteps([
        ...steps.value.map(s => s.state === 'running' ? { ...s, state: 'done' as const } : s),
        { id: 'start', label: STARTING_LABEL, state: 'running', seconds: 0 },
      ])
    }

    function giveUp(message: string) {
      stopPolling()
      phase.value = 'idle'
      failure.value = message
    }

    async function tick() {
      const [up, progress] = await Promise.all([backendUp(), fetchProgress()])
      elapsed.value = secondsSince(phaseStartedAt)
      if (progress?.source === 'narrator') narrated = true

      if (phase.value === 'stopping') {
        if (up) {
          // The JVM about to stop still has the previous restart's file, which has finished:
          // only a running one is this restart. Its services are the ones going away.
          if (progress?.state === 'running') {
            show({ ...progress, services: null })
            narrated = true
          }
          else count()
          if (elapsed.value >= shutdownBudget) {
            giveUp(`The backend was still responding ${shutdownBudget}s after the restart `
              + 'was accepted. Check logs/restart.log.')
          }
          return
        }
        phase.value = 'starting'
        phaseStartedAt = Date.now()
        elapsed.value = 0
      }

      if (progress?.state === 'failed' && !up) {
        show(progress)
        giveUp(`The restart failed: ${progress.failure ?? 'jclaw.sh stopped'}. See logs/restart.log.`)
        return
      }

      // Two consecutive answers, so a half-initialised Play answering one request
      // mid-boot doesn't trigger a premature reload. Then its services get until they
      // settle, or the budget runs out, before the reload.
      consecutiveUp = up ? consecutiveUp + 1 : 0
      const back = consecutiveUp >= 2
      const services = progress?.source === 'jclaw' ? progress.services : null
      const unsettled = back && !!services && !services.settled
      if (unsettled && servicesSince === null) servicesSince = Date.now()
      const waited = servicesSince === null ? 0 : secondsSince(servicesSince)
      const waiting = unsettled && waited < SERVICES_BUDGET

      if (progress) {
        show(progress, waited)
      }
      else if (narrated) {
        launching()
      }
      else if (elapsed.value >= NARRATOR_GRACE) {
        // Nothing narrates this restart: count, as before narration.
        setSteps([])
      }
      else {
        count()
      }

      if (back && !waiting) {
        stopPolling()
        reloadNuxtApp({ persistState: false })
        return
      }
      if (!up && elapsed.value >= startupBudget) {
        giveUp(`The backend did not come back within ${startupBudget}s. `
          + 'Check logs/restart.log.')
      }
    }

    stopPolling()
    poller = setInterval(async () => {
      // A slow answer must not let two ticks interleave their phase changes.
      if (ticking) return
      ticking = true
      try {
        await tick()
      }
      finally {
        ticking = false
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

  return { phase, elapsed, failure, busy, statusLine, steps, restart }
}
