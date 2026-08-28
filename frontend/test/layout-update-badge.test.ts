import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import DefaultLayout from '~/layouts/default.vue'

// Mirrors POLL_BASE_MS in layouts/default.vue.
const BASE_MS = 10_000
const MAINTENANCE_LINK = 'a[href="/settings?section=maintenance"]'

let upgradeCalls = 0
let upgradeAvailable = false
let statusUp = true

registerEndpoint('/api/status', () => {
  if (!statusUp) throw new Error('backend down')
  return { status: 'ok', applicationVersion: '0.18.24' }
})
registerEndpoint('/api/system/upgrade', () => {
  upgradeCalls++
  return {
    available: true,
    unavailableReason: null,
    currentVersion: '0.18.24',
    latestVersion: upgradeAvailable ? '0.19.0' : '0.18.24',
    upgradeAvailable,
    installKind: 'release',
    runningTasks: 0,
    activeSubagentRuns: 0,
    commit: null,
  }
})
registerEndpoint('/api/auth/status', () => ({ passwordSet: true }))
registerEndpoint('/api/onboarding/tour-status', () => ({ completed: true, step: 0 }))
registerEndpoint('/api/config', () => ({}))

/** Flip navigator.onLine and fire the matching window event VueUse listens on. */
function setLinkState(up: boolean) {
  Object.defineProperty(navigator, 'onLine', { value: up, configurable: true })
  window.dispatchEvent(new Event(up ? 'online' : 'offline'))
}

let layout: Awaited<ReturnType<typeof mountSuspended>> | null = null

beforeEach(() => {
  clearNuxtData()
  upgradeCalls = 0
  upgradeAvailable = false
  statusUp = true
  setLinkState(true)
})

afterEach(() => {
  layout?.unmount()
  layout = null
  vi.useRealTimers()
})

describe('default layout — version dot signals an available update', () => {
  it('stays green and unlinked while the instance is current', async () => {
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    await flushPromises()

    expect(layout!.find('.bg-ok').exists()).toBe(true)
    expect(layout!.find('.bg-warning').exists()).toBe(false)
    expect(layout!.find(MAINTENANCE_LINK).exists()).toBe(false)
  })

  it('turns amber and links to Maintenance when a newer release exists', async () => {
    upgradeAvailable = true
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    // Second flush: the first only dispatches the upgrade probe. The dot turns
    // on the response, which is what makes this a check and not a guess.
    await flushPromises()

    const link = layout!.find(MAINTENANCE_LINK)
    expect(link.exists()).toBe(true)
    expect(link.find('.bg-warning').exists()).toBe(true)
    expect(layout!.find('.bg-ok').exists()).toBe(false)
    // The dot is the whole affordance, so it has to carry its own name for
    // anyone not looking at the colour.
    expect(link.attributes('aria-label')).toContain('Update available')
    expect(link.attributes('aria-label')).toContain('v0.19.0')
  })

  it('shows offline rather than amber when the backend stops answering', async () => {
    upgradeAvailable = true
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    await flushPromises()
    expect(layout!.find(MAINTENANCE_LINK).exists()).toBe(true)

    setLinkState(false)
    await flushPromises()

    // An update the operator cannot reach is not the useful thing to report.
    expect(layout!.find('.bg-danger').exists()).toBe(true)
    expect(layout!.find(MAINTENANCE_LINK).exists()).toBe(false)
  })

  it('checks for an update far less often than it polls status', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    expect(upgradeCalls).toBe(1)

    // Three status polls. The upgrade check rides that chain but is throttled
    // to the server's one-hour cache, so it must not fire again with them.
    await vi.advanceTimersByTimeAsync(BASE_MS * 3)
    await flushPromises()
    expect(upgradeCalls).toBe(1)
  })
})
