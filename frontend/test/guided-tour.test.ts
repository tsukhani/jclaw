import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { readBody } from 'h3'
import {
  __resetInFlightRecord,
  loadTourStatus,
  recordStepReached,
  resetTourThreshold,
} from '~/composables/useGuidedTour'

describe('useGuidedTour API helpers', () => {
  beforeEach(() => {
    localStorage.clear()
    __resetInFlightRecord()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loadTourStatus returns the API payload', async () => {
    registerEndpoint('/api/onboarding/tour-status', {
      method: 'GET',
      handler: () => ({ maxStepReached: 2, totalSteps: 6, shouldAutoShow: true }),
    })
    const status = await loadTourStatus()
    expect(status).toEqual({ maxStepReached: 2, totalSteps: 6, shouldAutoShow: true })
  })

  it('loadTourStatus fails closed when the API errors', async () => {
    registerEndpoint('/api/onboarding/tour-status', {
      method: 'GET',
      handler: () => { throw createError({ statusCode: 500 }) },
    })
    const status = await loadTourStatus()
    expect(status.shouldAutoShow).toBe(false)
  })

  it('recordStepReached posts the step to the API', async () => {
    let received: unknown = null
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: async (event) => {
        received = await readBody(event)
        return { maxStepReached: 3 }
      },
    })
    await recordStepReached(3)
    expect(received).toEqual({ step: 3 })
  })

  it('recordStepReached serializes concurrent calls (one in-flight at a time)', async () => {
    let calls = 0
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: () => {
        calls += 1
        return { maxStepReached: 4 }
      },
    })
    await Promise.all([
      recordStepReached(2),
      recordStepReached(3),
      recordStepReached(4),
    ])
    // Three concurrent calls produce three sequential POSTs — see the
    // "Why not true single-flight?" rationale in useGuidedTour.ts.
    expect(calls).toBe(3)
  })

  it('resetTourThreshold calls the API and clears the resume cursor', async () => {
    let resetCalled = false
    registerEndpoint('/api/onboarding/tour-reset', {
      method: 'POST',
      handler: () => {
        resetCalled = true
        return { maxStepReached: 0 }
      },
    })
    localStorage.setItem('jclaw.tour.state', JSON.stringify({ step: 2, active: true }))
    await resetTourThreshold()
    expect(resetCalled).toBe(true)
    expect(localStorage.getItem('jclaw.tour.state')).toBeNull()
  })
})
