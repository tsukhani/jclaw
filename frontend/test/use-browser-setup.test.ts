import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, watch } from 'vue'
import { useBrowserSetupPolling, type BrowserSetupStatus } from '~/composables/useBrowserSetup'

function status(o: Partial<BrowserSetupStatus> = {}): BrowserSetupStatus {
  return {
    active: false, step: null, percent: null, error: null,
    driverSource: 'missing', platform: 'mac-arm64', nodeVersion: '24.21.0', chromiumInstalled: false, ...o,
  }
}

// The server's answers, one per poll; the last repeats.
let script: BrowserSetupStatus[]
let polls: number

// Real timers at a short interval: the canned response resolves on real I/O, which fake timers outrun.
const INTERVAL = 20

beforeEach(() => {
  polls = 0
  registerEndpoint('/api/browser/setup', () => script[Math.min(polls++, script.length - 1)])
})

async function mountPoller() {
  let api!: ReturnType<typeof useBrowserSetupPolling>
  await mountSuspended(defineComponent({
    setup() {
      api = useBrowserSetupPolling(INTERVAL)
      return () => h('div')
    },
  }))
  return api
}

async function settle(ms: number) {
  await new Promise(r => setTimeout(r, ms))
  await flushPromises()
}

describe('useBrowserSetupPolling', () => {
  it('without a grace, one idle answer ends the poll', async () => {
    script = [status()]
    const api = await mountPoller()
    api.start()
    await settle(INTERVAL * 10)
    expect(polls).toBe(1)
    expect(api.status.value?.driverSource).toBe('missing')
  })

  it('polls through an idle answer inside the grace, follows the setup, and stops when it ends', async () => {
    // The chat's first poll can beat the backend marking the setup in flight.
    script = [
      status(),
      status({ active: true, step: 'Downloading the browser driver (Node.js 24.21.0)', percent: 40 }),
      status({ active: true, step: 'Downloading Chrome for Testing 153.0.8010.12', percent: 10 }),
      status({ driverSource: 'downloaded', chromiumInstalled: true }),
    ]
    const api = await mountPoller()
    const seen: Array<string | null> = []
    const stopWatch = watch(() => api.status.value?.step ?? null, step => seen.push(step))
    api.start(5000)
    await vi.waitFor(() => expect(api.status.value?.chromiumInstalled).toBe(true), { timeout: 3000 })
    stopWatch()
    expect(seen).toContain('Downloading the browser driver (Node.js 24.21.0)')
    expect(seen).toContain('Downloading Chrome for Testing 153.0.8010.12')
    expect(api.status.value?.active).toBe(false)
    const after = polls
    await settle(INTERVAL * 10)
    expect(polls).toBe(after)
  })

  it('gives up once the grace passes with no setup started', async () => {
    script = [status({ driverSource: 'bundled', chromiumInstalled: true })]
    const api = await mountPoller()
    api.start(INTERVAL * 5)
    await settle(INTERVAL * 20)
    // Kept polling through the grace, then stopped: roughly grace/interval answers, not twenty.
    expect(polls).toBeGreaterThanOrEqual(2)
    expect(polls).toBeLessThanOrEqual(8)
  })
})
