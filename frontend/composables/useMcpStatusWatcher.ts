import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import type { McpServerSummary } from '~/types/api'

/** Cadence while a row is mid-handshake — fast enough to read as live. */
const FAST_POLL_MS = 600
/** Idle heartbeat: the backend watchdog reconnects unprompted, so a burst armed
 *  only at mount would miss every transition that starts after load. */
const SLOW_POLL_MS = 10_000

export interface UseMcpStatusWatcher {
  /** Collapse the current wait so a just-mutated row updates at once. */
  kick: () => void
}

/**
 * Poll while any server is CONNECTING, idle otherwise, for as long as the caller is
 * mounted. Unbounded by design: a docker-backed STDIO server takes ~90s to spawn,
 * handshake and sync its allowlist, so any tidy-looking ceiling gives up mid-connect.
 */
export function useMcpStatusWatcher(
  servers: Ref<McpServerSummary[] | null | undefined>,
  refresh: () => Promise<unknown>,
): UseMcpStatusWatcher {
  let watching = false
  /** Resolver for the in-flight sleep, so {@link kick} can end it early. */
  let wake: (() => void) | null = null

  function sleep(ms: number) {
    return new Promise<void>((resolve) => {
      const timer = setTimeout(done, ms)
      wake = done
      function done() {
        clearTimeout(timer)
        wake = null
        resolve()
      }
    })
  }

  function kick() {
    wake?.()
  }

  async function loop() {
    while (watching) {
      const connecting = servers.value?.some(row => row.status === 'CONNECTING') ?? false
      await sleep(connecting ? FAST_POLL_MS : SLOW_POLL_MS)
      if (!watching) return
      // This loop is the page's only source of updates: one escaping rejection
      // would end the watch and freeze every badge until a manual reload.
      try {
        await refresh()
      }
      catch { /* transient; the next tick retries */ }
    }
  }

  onMounted(() => {
    watching = true
    loop()
  })

  onBeforeUnmount(() => {
    watching = false
    kick()
  })

  return { kick }
}
