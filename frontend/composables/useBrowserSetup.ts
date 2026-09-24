import { onUnmounted, ref } from 'vue'
import type { DriverSource } from '~/utils/browser-setup'

/**
 * The browser tool's first-use setup — the Playwright driver's Node.js, then Chromium — as
 * GET /api/browser/setup reports it. `active` spans the whole setup, not each download.
 */
export interface BrowserSetupStatus {
  active: boolean
  step: string | null
  percent: number | null
  error: string | null
  driverSource: DriverSource
  platform: string | null
  nodeVersion: string
  chromiumInstalled: boolean
}

const POLL_MS = 1000

/**
 * Polls the setup status while a setup runs. `start(graceMs)` keeps polling through an inactive
 * status for `graceMs`: the chat hears "Using tool: browser" just before the call begins, so its
 * first poll can land before the backend has marked the setup in flight. Once a setup has been
 * seen, or the grace has passed, the first inactive status stops the poll.
 */
export function useBrowserSetupPolling(intervalMs = POLL_MS) {
  const status = ref<BrowserSetupStatus | null>(null)
  let timer: ReturnType<typeof setTimeout> | null = null
  let running = false
  let seenActive = false
  let graceUntil = 0

  async function poll() {
    try {
      const s = await $fetch<BrowserSetupStatus>('/api/browser/setup')
      // A response that lands after stop() must not resurrect a finished bar.
      if (!running) return
      status.value = s
      if (s.active) seenActive = true
      else if (seenActive || Date.now() >= graceUntil) {
        stop()
        return
      }
    }
    catch {
      // transient — the next tick retries
    }
    if (running) timer = setTimeout(() => void poll(), intervalMs)
  }

  function start(graceMs = 0) {
    if (running) return
    running = true
    seenActive = false
    graceUntil = Date.now() + graceMs
    void poll()
  }

  function stop() {
    running = false
    if (timer != null) {
      clearTimeout(timer)
      timer = null
    }
  }

  onUnmounted(stop)

  return { status, start, stop }
}
