import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import {
  __resetInFlightRecord,
  useGuidedTour,
} from '~/composables/useGuidedTour'

/**
 * JCLAW-314 — useGuidedTour state-machine coverage.
 *
 * The composable already has API-helper coverage in guided-tour.test.ts;
 * this suite focuses on the state-machine surface returned by useGuidedTour():
 *
 *   - showIntro / dismissIntro toggle the intro dialog flag.
 *   - confirmStart activates the tour and lands the user on step 0.
 *   - end clears state without writing a completed marker.
 *   - introOpen and isActive expose the right reactive values.
 *
 * The driver.js popover itself requires a live DOM with [data-tour=...]
 * anchors, which the test environment doesn't have. We exercise the
 * composable's *state* transitions (the externally observable contract),
 * not driver.js's internal popover rendering.
 */

beforeEach(() => {
  __resetInFlightRecord()
  // useState is module-scoped within Nuxt's runtime context; reset both
  // flags to a clean baseline so prior tests don't bleed in.
  useState<{ step: number, active: boolean }>('jclaw-guided-tour').value = { step: 0, active: false }
  useState<boolean>('jclaw-guided-tour-intro').value = false
  // Stub the progress endpoint so confirmStart's fire-and-forget POST
  // doesn't dangle a rejected promise into later cases.
  registerEndpoint('/api/onboarding/tour-progress', {
    method: 'POST',
    handler: () => ({ maxStepReached: 1 }),
  })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useGuidedTour — intro dialog flag', () => {
  it('showIntro sets introOpen to true', () => {
    const tour = useGuidedTour()
    expect(tour.introOpen.value).toBe(false)
    tour.showIntro()
    expect(tour.introOpen.value).toBe(true)
  })

  it('dismissIntro clears the intro flag without activating the tour', () => {
    const tour = useGuidedTour()
    tour.showIntro()
    expect(tour.introOpen.value).toBe(true)
    tour.dismissIntro()
    expect(tour.introOpen.value).toBe(false)
    // dismiss is a "saw the intro, opted out" signal — tour stays idle.
    expect(tour.isActive.value).toBe(false)
    expect(tour.state.value.step).toBe(0)
  })
})

describe('useGuidedTour — confirmStart activates step 0', () => {
  it('flips active=true and lands on step 0 when the user clicks Start', () => {
    const tour = useGuidedTour()
    expect(tour.isActive.value).toBe(false)

    tour.confirmStart()

    expect(tour.isActive.value).toBe(true)
    expect(tour.state.value.step).toBe(0)
    expect(tour.state.value.active).toBe(true)
    // confirmStart also closes the intro dialog.
    expect(tour.introOpen.value).toBe(false)
  })

  it('fires the tour-progress POST when confirmStart runs (records step 1 reached)', async () => {
    let posted: unknown = null
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        posted = await readBody(event)
        return { maxStepReached: 1 }
      },
    })
    const tour = useGuidedTour()
    tour.confirmStart()
    // recordStepReached is fire-and-forget; wait a tick for the $fetch to land.
    await flushPromises()
    expect(posted).toEqual({ step: 1 })
  })
})

describe('useGuidedTour — end clears state without completing', () => {
  it('end zeroes the step counter and deactivates without writing a completed marker', async () => {
    let postCount = 0
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: () => {
        postCount++
        return { maxStepReached: 1 }
      },
    })
    const tour = useGuidedTour()
    tour.confirmStart()
    await flushPromises()
    const postsAfterStart = postCount

    tour.end()
    await flushPromises()

    // end() must not POST — it's the "skip all" path, not the "completed" one.
    expect(postCount).toBe(postsAfterStart)
    expect(tour.isActive.value).toBe(false)
    expect(tour.state.value).toEqual({ step: 0, active: false })
  })
})

describe('useGuidedTour — state mutations are observable', () => {
  it('writing to state.value drives isActive correctly', () => {
    const tour = useGuidedTour()
    expect(tour.isActive.value).toBe(false)

    tour.state.value = { step: 2, active: true }
    expect(tour.isActive.value).toBe(true)
    expect(tour.state.value.step).toBe(2)

    tour.state.value = { step: 2, active: false }
    expect(tour.isActive.value).toBe(false)
  })
})
