import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import DefaultLayout from '~/layouts/default.vue'

const OFFLINE_BANNER = 'The JClaw server isn\'t responding'
// Mirrors POLL_BASE_MS in layouts/default.vue.
const BASE_MS = 10_000

let statusCalls = 0
let statusUp = true

registerEndpoint('/api/status', () => {
  statusCalls++
  if (!statusUp) throw new Error('backend down')
  return { status: 'ok', applicationVersion: '0.17.47' }
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
  statusCalls = 0
  statusUp = true
  setLinkState(true)
})

// A mounted layout keeps its online/offline listeners and its poll timer for as
// long as it lives, so leaving one behind makes the next test's link events fire
// two probes.
afterEach(() => {
  layout?.unmount()
  layout = null
  vi.useRealTimers()
})

describe('default layout — navigator.onLine fast path for the API dot', () => {
  it('marks the API offline on link loss without waiting for the next poll', async () => {
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    expect(layout!.text()).not.toContain(OFFLINE_BANNER)

    setLinkState(false)
    await flushPromises()

    // No timer advanced here — if this passes only after the poll fires, the
    // fast path is not wired and the dot stays green for the whole interval.
    expect(layout!.text()).toContain(OFFLINE_BANNER)
    // The test origin is plain http, where the per-host connection cap applies.
    expect(layout!.text()).toContain('If several JClaw tabs are open')
  })

  it('re-probes the backend on link recovery rather than assuming it is up', async () => {
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    const afterMount = statusCalls

    setLinkState(false)
    await flushPromises()
    // A dropped link must not trigger a request that is guaranteed to fail.
    expect(statusCalls).toBe(afterMount)

    setLinkState(true)
    await flushPromises()
    // Recovery only re-probes: navigator.onLine === true is not proof the
    // backend answers, so the banner clears on the probe's result, not the event.
    expect(statusCalls).toBe(afterMount + 1)

    // Second flush: the first only gets the probe dispatched. The banner is
    // still up at this point, which is the behaviour we want — it clears on
    // the response, not on the event.
    await flushPromises()
    expect(layout!.text()).not.toContain(OFFLINE_BANNER)
  })
})

describe('default layout — API status poll backoff', () => {
  it('doubles the gap on consecutive failures and resets on the first success', async () => {
    statusUp = false
    vi.useFakeTimers({ shouldAdvanceTime: true })

    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    expect(statusCalls).toBe(1)

    // First failure retries at the base delay — one dropped probe is a blip.
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(statusCalls).toBe(2)

    // Second failure has doubled the gap, so one base tick is not enough.
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(statusCalls).toBe(2)
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(statusCalls).toBe(3)

    // Third failure doubles again to 4x base.
    statusUp = true
    await vi.advanceTimersByTimeAsync(BASE_MS * 3)
    expect(statusCalls).toBe(3)
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(statusCalls).toBe(4)
    await flushPromises()
    expect(layout!.text()).not.toContain(OFFLINE_BANNER)

    // That success resets the backoff: back to base cadence, not 8x.
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(statusCalls).toBe(5)
  })

  it('stops polling once the layout unmounts', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    layout = await mountSuspended(DefaultLayout)
    await flushPromises()
    const afterMount = statusCalls

    layout.unmount()
    layout = null

    await vi.advanceTimersByTimeAsync(BASE_MS * 4)
    expect(statusCalls).toBe(afterMount)
  })
})
