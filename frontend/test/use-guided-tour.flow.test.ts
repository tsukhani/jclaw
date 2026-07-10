import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import {
  __resetInFlightRecord,
  useGuidedTour,
} from '~/composables/useGuidedTour'

// Mock Nuxt's auto-imported useRoute so we can pin a path that matches the
// steps[] table inside useGuidedTour. The composable consults route.path to
// decide whether to drive a popover or queue a router.push. useRouter is left
// real because Nuxt's internal navigation-repaint plugin calls beforeResolve()
// on it; stubbing breaks the runtime.
const { routePath, routeQuery } = vi.hoisted(() => ({
  routePath: { value: '/' },
  routeQuery: { value: {} as Record<string, string> },
}))

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: composable returns RouteLocationNormalized; we only stub the fields useGuidedTour reads.
mockNuxtImport('useRoute', () => () => ({ path: routePath.value, query: routeQuery.value, fullPath: routePath.value } as any))

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
    // recordStepReached is fire-and-forget; vi.waitFor polls the assertion
    // until the registerEndpoint mock's multi-microtask resolution chain
    // settles. flushPromises drains only one microtask round which is not
    // enough for $fetch through the Nitro mock.
    await vi.waitFor(() => expect(posted).toEqual({ step: 1 }))
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
    await vi.waitFor(() => expect(postCount).toBeGreaterThan(0))
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

/**
 * Driver.js is mocked at module load time so the popover-rendering paths
 * inside showStepForCurrentPage can run without a real DOM walkthrough.
 * The mock lives in vi.hoisted because vi.mock factories run before the
 * file body executes; the in-test inspector spies need a reference that
 * survives the hoist.
 */
const { driverFactory, driveSpy, destroySpy } = vi.hoisted(() => {
  const driveSpy = vi.fn()
  const destroySpy = vi.fn()
  const driverFactory = vi.fn((_opts?: unknown) => ({
    drive: driveSpy,
    destroy: destroySpy,
    moveNext: vi.fn(),
    movePrevious: vi.fn(),
    refresh: vi.fn(),
    getActiveIndex: vi.fn(),
    isActivated: vi.fn(() => true),
    hasNextStep: vi.fn(),
    hasPreviousStep: vi.fn(),
    getActiveStep: vi.fn(),
    getActiveElement: vi.fn(),
    moveTo: vi.fn(),
    setSteps: vi.fn(),
    getConfig: vi.fn(),
    setConfig: vi.fn(),
  }))
  return { driverFactory, driveSpy, destroySpy }
})

vi.mock('driver.js', () => ({
  driver: driverFactory,
}))

describe('useGuidedTour — showStepForCurrentPage drives the popover', () => {
  beforeEach(() => {
    driverFactory.mockClear()
    driveSpy.mockClear()
    destroySpy.mockClear()
    routePath.value = '/'
  })

  afterEach(() => {
    document.body.replaceChildren()
    routePath.value = '/'
  })

  it('exits early without creating a driver when the tour is not active', async () => {
    const tour = useGuidedTour()
    tour.state.value = { step: 0, active: false }
    await tour.showStepForCurrentPage()
    // The !state.value.active early-return guards driver creation entirely.
    expect(driverFactory).not.toHaveBeenCalled()
  })

  it('exits cleanly when state is active but no step exists at that index', async () => {
    const tour = useGuidedTour()
    // Step 1000 doesn't exist in the steps array; the function early-returns
    // on !step. This exercises the `if (!step || route.path !== step.path)`
    // gate.
    tour.state.value = { step: 1000, active: true }
    routePath.value = '/settings'
    await tour.showStepForCurrentPage()
    expect(driverFactory).not.toHaveBeenCalled()
  })

  it('exits cleanly when the active step path does not match the current route', async () => {
    const tour = useGuidedTour()
    tour.state.value = { step: 0, active: true }
    // Step 0 wants /settings; we're on /unrelated → early return.
    routePath.value = '/unrelated'
    await tour.showStepForCurrentPage()
    expect(driverFactory).not.toHaveBeenCalled()
  })

  it('drives driver.js when active, route matches, and the anchor is present', async () => {
    // Step 0 anchors on [data-tour="llm-providers"] at /settings.
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'llm-providers')
    document.body.appendChild(div)
    routePath.value = '/settings'

    const tour = useGuidedTour()
    tour.state.value = { step: 0, active: true }
    await tour.showStepForCurrentPage()

    expect(driverFactory).toHaveBeenCalledTimes(1)
    expect(driveSpy).toHaveBeenCalledTimes(1)
    // Inspect the config passed to driver.js: the first arg carries the
    // step's title/description and the show-buttons list.
    const cfg = driverFactory.mock.calls[0]![0] as {
      steps: Array<{ popover?: { title?: string } }>
      nextBtnText: string
      allowClose: boolean
    }
    expect(cfg.allowClose).toBe(false)
    expect(cfg.steps[0]!.popover!.title).toBe('Add an API key')
    // Step 0 is not last → label reads "Next →".
    expect(cfg.nextBtnText).toBe('Next →')
  })

  it('uses "Finish" label on the final step (last index = steps.length - 1)', async () => {
    // Step 5 = /channels, [data-tour="channel-list"]. Inject anchor.
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'channel-list')
    document.body.appendChild(div)
    routePath.value = '/channels'

    const tour = useGuidedTour()
    tour.state.value = { step: 5, active: true }
    await tour.showStepForCurrentPage()

    expect(driverFactory).toHaveBeenCalled()
    const cfg = driverFactory.mock.calls[0]![0] as {
      nextBtnText: string
      doneBtnText: string
    }
    expect(cfg.nextBtnText).toBe('Finish')
    expect(cfg.doneBtnText).toBe('Finish')
  })

  it('does not call driver when the anchor never appears (waitForElement times out)', async () => {
    // Route matches step 0, but the [data-tour=llm-providers] anchor is
    // absent. waitForElement polls until the 2s timeout, then returns null,
    // and the function bails before constructing a driver.
    routePath.value = '/settings'

    vi.useFakeTimers()
    const tour = useGuidedTour()
    tour.state.value = { step: 0, active: true }
    const p = tour.showStepForCurrentPage()
    await vi.advanceTimersByTimeAsync(2100)
    await p
    vi.useRealTimers()

    expect(driverFactory).not.toHaveBeenCalled()
  })
})

describe('useGuidedTour — Settings step opens the Providers section (JCLAW-680)', () => {
  // The Settings page now renders one section at a time; step 0's anchor
  // ([data-tour="llm-providers"]) only mounts when the Providers section is
  // active. The tour deep-links via ?section=providers rather than landing on
  // the default section, where the anchor wouldn't exist.
  beforeEach(() => {
    driverFactory.mockClear()
    driveSpy.mockClear()
    destroySpy.mockClear()
    routePath.value = '/'
    routeQuery.value = {}
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: () => ({ maxStepReached: 1 }),
    })
  })

  afterEach(() => {
    document.body.replaceChildren()
    routeQuery.value = {}
  })

  it('defers driving (navigates first) when starting off the Providers section', () => {
    // On /settings but WITHOUT ?section=providers — the anchor isn't mounted,
    // so goToStepRoute must navigate rather than drive the popover in place.
    routePath.value = '/settings'
    routeQuery.value = {}
    const tour = useGuidedTour()
    tour.confirmStart()
    // The tour started, but driving is deferred until the section-navigation
    // lands and the route watch re-drives on the new fullPath.
    expect(tour.isActive.value).toBe(true)
    expect(tour.state.value.step).toBe(0)
    expect(driverFactory).not.toHaveBeenCalled()
  })

  it('drives directly when already on the Providers section (anchor present)', async () => {
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'llm-providers')
    document.body.appendChild(div)
    routePath.value = '/settings'
    routeQuery.value = { section: 'providers' }

    const tour = useGuidedTour()
    tour.confirmStart()
    await flushPromises()

    // Query matches → goToStepRoute drives in place instead of navigating.
    expect(driverFactory).toHaveBeenCalledTimes(1)
  })
})

describe('useGuidedTour — driver.js onNextClick wires to advance/complete', () => {
  beforeEach(() => {
    driverFactory.mockClear()
    driveSpy.mockClear()
    destroySpy.mockClear()
    routePath.value = '/'
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: () => ({ maxStepReached: 1 }),
    })
  })

  afterEach(() => {
    document.body.replaceChildren()
  })

  it('onNextClick on an intermediate step advances and updates state', async () => {
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'llm-providers')
    document.body.appendChild(div)
    routePath.value = '/settings'

    const tour = useGuidedTour()
    tour.state.value = { step: 0, active: true }
    await tour.showStepForCurrentPage()

    // Pull onNextClick out of the driver.js config and invoke it.
    const cfg = driverFactory.mock.calls[0]![0] as { onNextClick: () => void }
    cfg.onNextClick()
    await flushPromises()

    // Step counter incremented from 0 → 1; still active.
    expect(tour.state.value.step).toBe(1)
    expect(tour.state.value.active).toBe(true)
  })

  it('onNextClick on the final step completes (deactivates) and posts steps.length', async () => {
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'channel-list')
    document.body.appendChild(div)
    routePath.value = '/channels'

    let postedStep: number | null = null
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        const body = await readBody(event) as { step?: number }
        postedStep = body.step ?? null
        return { maxStepReached: postedStep ?? 0 }
      },
    })

    const tour = useGuidedTour()
    tour.state.value = { step: 5, active: true }
    await tour.showStepForCurrentPage()

    const cfg = driverFactory.mock.calls[0]![0] as { onNextClick: () => void }
    cfg.onNextClick()
    await vi.waitFor(() => expect(postedStep).toBe(6))
    expect(tour.state.value).toEqual({ step: 0, active: false })
  })
})

describe('useGuidedTour — popover footer injection (onPopoverRender)', () => {
  beforeEach(() => {
    driverFactory.mockClear()
    driveSpy.mockClear()
    destroySpy.mockClear()
    routePath.value = '/'
  })

  afterEach(() => {
    document.body.replaceChildren()
  })

  it('injects a progress counter and a Previous button on step > 0', async () => {
    // Use step 2 (/agents, anchor data-tour="agent-edit-form").
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'agent-edit-form')
    document.body.appendChild(div)
    routePath.value = '/agents'

    const tour = useGuidedTour()
    tour.state.value = { step: 2, active: true }
    await tour.showStepForCurrentPage()

    const cfg = driverFactory.mock.calls[0]![0] as {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: PopoverDOM is a driver.js opaque DOM-bag; we only synthesize the slots the test exercises.
      onPopoverRender: (popover: any) => void
    }
    // Synthesize the popover DOM driver.js would pass.
    const footerButtons = document.createElement('div')
    const nextButton = document.createElement('button')
    nextButton.textContent = 'Next →'
    footerButtons.appendChild(nextButton)
    const wrapper = document.createElement('div')

    cfg.onPopoverRender({ footerButtons, nextButton, wrapper })

    // Progress counter sits first; Previous button sits before next button.
    const progress = footerButtons.querySelector('.jclaw-tour-progress')
    expect(progress?.textContent).toBe('3 of 6')
    const prev = footerButtons.querySelector('.jclaw-tour-back-btn')
    expect(prev).not.toBeNull()
  })

  it('hides the Next button when the step opts in via hideNextButton', async () => {
    // Step 1 is the only one with hideNextButton: true.
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'main-agent')
    document.body.appendChild(div)
    routePath.value = '/agents'

    const tour = useGuidedTour()
    tour.state.value = { step: 1, active: true }
    await tour.showStepForCurrentPage()

    const cfg = driverFactory.mock.calls[0]![0] as {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: synthesised PopoverDOM bag — narrow shape.
      onPopoverRender: (popover: any) => void
    }
    const footerButtons = document.createElement('div')
    const nextButton = document.createElement('button')
    footerButtons.appendChild(nextButton)
    const wrapper = document.createElement('div')

    cfg.onPopoverRender({ footerButtons, nextButton, wrapper })

    expect(nextButton.style.display).toBe('none')
  })

  it('clicking the injected Previous button rewinds to the prior step', async () => {
    // Step 2 → step 1 on Previous click.
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'agent-edit-form')
    document.body.appendChild(div)
    routePath.value = '/agents'

    const tour = useGuidedTour()
    tour.state.value = { step: 2, active: true }
    await tour.showStepForCurrentPage()

    const cfg = driverFactory.mock.calls[0]![0] as {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: synthesised PopoverDOM bag — narrow shape.
      onPopoverRender: (popover: any) => void
    }
    const footerButtons = document.createElement('div')
    const nextButton = document.createElement('button')
    footerButtons.appendChild(nextButton)
    const wrapper = document.createElement('div')

    cfg.onPopoverRender({ footerButtons, nextButton, wrapper })

    const prevBtn = footerButtons.querySelector<HTMLButtonElement>('.jclaw-tour-back-btn')!
    prevBtn.click()
    await flushPromises()

    expect(tour.state.value.step).toBe(1)
  })

  it('renders without footer buttons gracefully (early return path)', async () => {
    const div = document.createElement('div')
    div.setAttribute('data-tour', 'llm-providers')
    document.body.appendChild(div)
    routePath.value = '/settings'

    const tour = useGuidedTour()
    tour.state.value = { step: 0, active: true }
    await tour.showStepForCurrentPage()

    const cfg = driverFactory.mock.calls[0]![0] as {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: synthesised PopoverDOM bag — narrow shape.
      onPopoverRender: (popover: any) => void
    }
    // No footerButtons → early return; must not throw.
    expect(() => cfg.onPopoverRender({})).not.toThrow()
  })
})

describe('useGuidedTour — end and reset paths', () => {
  beforeEach(() => {
    driverFactory.mockClear()
    driveSpy.mockClear()
    destroySpy.mockClear()
    registerEndpoint('/api/onboarding/tour-progress', {
      method: 'POST',
      handler: () => ({ maxStepReached: 1 }),
    })
  })

  it('end() clears state to step 0 / inactive even when called from a mid-tour step', () => {
    const tour = useGuidedTour()
    tour.state.value = { step: 3, active: true }
    tour.end()
    expect(tour.state.value).toEqual({ step: 0, active: false })
  })

  it('end() is safe to call when no driver instance exists (idempotent destroy)', () => {
    const tour = useGuidedTour()
    expect(() => tour.end()).not.toThrow()
  })
})

describe('installGuidedTourHooks', () => {
  it('is a no-op call surface that installs without throwing', async () => {
    const { installGuidedTourHooks } = await import('~/composables/useGuidedTour')
    expect(() => installGuidedTourHooks()).not.toThrow()
  })
})
