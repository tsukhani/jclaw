import { driver, type Driver } from 'driver.js'

const STORAGE_KEY = 'jclaw.tour.state'

interface TourState {
  step: number
  active: boolean
}

interface TourStep {
  path: string
  selector: string
  /** Action label — the "Step N of M — " prefix is added at render time. */
  title: string
  description: string
  /**
   * When set, install a MutationObserver on document.body; as soon as this
   * selector appears in the DOM, auto-advance. Use for steps whose completion
   * criterion is "the next step's anchor has mounted" — e.g., clicking a card
   * that reveals an editor.
   */
  advanceOnAppearOf?: string
  /**
   * Hide the Next button for this step. Use together with advanceOnAppearOf
   * when manual progression would land on a step whose anchor doesn't yet
   * exist, so we force the user through the DOM-changing action.
   */
  hideNextButton?: boolean
  /**
   * Override the Next/Done button label for this step. Default is "Next" for
   * intermediate steps and "Finish" for the final step. Use to signal optional
   * continuation (e.g. "Continue (optional) →") on a step that's the last
   * *required* step but has follow-on optional steps.
   */
  nextBtnText?: string
  /**
   * Show an explicit "Finish" button (rendered in driver.js's Previous slot)
   * that ends the tour. Use on optional-junction steps where the user may
   * reasonably choose to stop rather than continue — the X close button is
   * too subtle to carry that decision.
   */
  showFinishButton?: boolean
}

const steps: TourStep[] = [
  {
    path: '/settings',
    selector: '[data-tour="llm-providers"]',
    title: 'Add an API key',
    description: 'Enter an API key for <strong>Ollama Cloud</strong> or <strong>OpenRouter</strong> (or both). Chat needs at least one provider configured before the Main Agent has anything to route to.',
  },
  {
    path: '/agents',
    selector: '[data-tour="main-agent"]',
    title: 'Open the Main Agent',
    description: 'Click the Main Agent row to open its editor. The tour will advance automatically.',
    advanceOnAppearOf: '[data-tour="agent-edit-form"]',
    hideNextButton: true,
  },
  {
    path: '/agents',
    selector: '[data-tour="agent-edit-form"]',
    title: 'Choose provider and model',
    description: 'Set <strong>Default Provider</strong> and <strong>Default Model</strong> to the ones you just configured, then click the <strong>Save</strong> icon.',
  },
  {
    path: '/chat',
    selector: '[data-tour="chat-composer"]',
    title: 'Start chatting',
    description: 'Type a message here and press Enter. You are wired up — the core setup is done. The next two steps are optional.',
  },
  {
    path: '/skills',
    selector: '[data-tour="global-skills"]',
    title: 'Add skills (optional)',
    description: 'The <strong>Global Skills</strong> registry holds reusable capabilities you can attach to any agent — drag a skill onto an agent card above to assign it.',
  },
  {
    path: '/channels',
    selector: '[data-tour="channel-list"]',
    title: 'Connect a channel (optional)',
    description: 'Wire your agent to a messaging channel — <strong>Telegram</strong>, <strong>Slack</strong>, or <strong>Discord</strong>. Once configured, your agent will respond to messages automatically.',
  },
]

let driverInstance: Driver | null = null
let advanceObserver: MutationObserver | null = null

function loadState(): TourState {
  if (import.meta.server) return { step: 0, active: false }
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { step: 0, active: false }
    const parsed = JSON.parse(raw) as Partial<TourState>
    return {
      step: typeof parsed.step === 'number' && parsed.step >= 0 && parsed.step < steps.length ? parsed.step : 0,
      active: !!parsed.active,
    }
  }
  catch {
    return { step: 0, active: false }
  }
}

function saveState(state: TourState) {
  if (import.meta.server) return
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  }
  catch {
    // localStorage full or disabled — tour becomes non-resumable on reload
  }
}

/**
 * Poll the DOM for an element matching the selector. driver.js fails noisily
 * if the element isn't rendered yet; router.push() resolves before the new
 * page's elements mount, so we bridge with a short retry loop.
 */
function waitForElement(selector: string, timeoutMs = 2000): Promise<Element | null> {
  return new Promise((resolve) => {
    const start = Date.now()
    const tick = () => {
      const el = document.querySelector(selector)
      if (el) return resolve(el)
      if (Date.now() - start >= timeoutMs) return resolve(null)
      setTimeout(tick, 50)
    }
    tick()
  })
}

export function useGuidedTour() {
  const router = useRouter()
  const route = useRoute()

  const state = useState<TourState>('jclaw-guided-tour', () => ({ step: 0, active: false }))

  function destroy() {
    if (driverInstance) {
      driverInstance.destroy()
      driverInstance = null
    }
    if (advanceObserver) {
      advanceObserver.disconnect()
      advanceObserver = null
    }
  }

  function installAdvanceObserver(selector: string) {
    // If the target is somehow already present (shouldn't happen in our flow
    // but defensive), advance on next tick rather than synchronously — avoids
    // destroying the popover we just created.
    if (document.querySelector(selector)) {
      queueMicrotask(() => advance())
      return
    }
    advanceObserver = new MutationObserver(() => {
      if (document.querySelector(selector)) advance()
    })
    advanceObserver.observe(document.body, { childList: true, subtree: true })
  }

  async function showStepForCurrentPage() {
    // Always clear any existing popover first — stops a step-1 popover from
    // dangling on /settings after the user navigates to /skills mid-tour.
    destroy()
    if (!state.value.active || import.meta.server) return
    const step = steps[state.value.step]
    if (!step || route.path !== step.path) return

    const el = await waitForElement(step.selector)
    if (!el) {
      // Element never rendered — abandon this step rather than hang
      return
    }

    const isLast = state.value.step === steps.length - 1
    const buttons: ('next' | 'close')[] = ['close']
    if (!step.hideNextButton) buttons.push('next')
    // Single-step popovers render doneBtnText (not nextBtnText) by default, so
    // set both to the same computed value.
    const buttonText = step.nextBtnText ?? (isLast ? 'Finish' : 'Next')
    driverInstance = driver({
      popoverClass: 'jclaw-tour',
      overlayOpacity: 0.55,
      allowClose: true,
      showButtons: buttons,
      nextBtnText: buttonText,
      doneBtnText: buttonText,
      onCloseClick: () => {
        end()
      },
      onNextClick: () => {
        if (isLast) complete()
        else advance()
      },
      // Custom Finish button: driver.js disables the Previous slot on single-
      // step popovers (which ours always are), so we inject a real <button>
      // instead of hijacking that slot. Tag the popover with a modifier class
      // so CSS can equalize button widths when both are present.
      onPopoverRender: (popover) => {
        if (!step.showFinishButton || !popover.footerButtons) return
        popover.wrapper.classList.add('jclaw-tour-paired')
        const finishBtn = document.createElement('button')
        finishBtn.type = 'button'
        finishBtn.textContent = 'Finish'
        finishBtn.className = 'driver-popover-prev-btn'
        finishBtn.addEventListener('click', (e) => {
          e.preventDefault()
          e.stopPropagation()
          complete()
        })
        if (popover.nextButton) {
          popover.footerButtons.insertBefore(finishBtn, popover.nextButton)
        }
        else {
          popover.footerButtons.appendChild(finishBtn)
        }
      },
      steps: [{
        element: step.selector,
        popover: {
          title: `Step ${state.value.step + 1} of ${steps.length} — ${step.title}`,
          description: step.description,
          side: 'right',
          align: 'start',
          showButtons: buttons,
          nextBtnText: buttonText,
          doneBtnText: buttonText,
        },
      }],
    })
    driverInstance.drive()

    if (step.advanceOnAppearOf) installAdvanceObserver(step.advanceOnAppearOf)
  }

  function start() {
    const first = steps[0]
    if (!first) return
    state.value = { step: 0, active: true }
    saveState(state.value)
    if (route.path === first.path) showStepForCurrentPage()
    else router.push(first.path)
  }

  function advance() {
    destroy()
    const next = state.value.step + 1
    const nextStep = steps[next]
    if (!nextStep) {
      // No next step — complete() handles the final-step writeback. Skip the
      // record here to avoid POSTing an out-of-range step value (which the
      // backend would reject with 400) right before complete() POSTs the
      // correct steps.length value.
      complete()
      return
    }
    // Record reaching the step we're about to land on (1-based for the API).
    // Fire-and-forget: don't block UI on the network round-trip; the backend
    // clamps to Math.max so duplicates / out-of-order writes are safe.
    void recordStepReached(next + 1)
    state.value = { step: next, active: true }
    saveState(state.value)
    if (route.path === nextStep.path) showStepForCurrentPage()
    else router.push(nextStep.path)
  }

  function end() {
    destroy()
    state.value = { step: 0, active: false }
    saveState(state.value)
  }

  function complete() {
    destroy()
    // Reaching the final step = recording total step count
    void recordStepReached(steps.length)
    state.value = { step: 0, active: false }
    saveState(state.value)
  }

  return {
    start,
    end,
    isActive: computed(() => state.value.active),
    state,
    showStepForCurrentPage,
  }
}

/**
 * Install the route + mount hooks that drive the tour from wherever this is
 * called. Intended to be called once from the root layout. Keeping the hook
 * wiring in a separate function lets pages consume `useGuidedTour()` for its
 * state without each one registering duplicate watchers.
 */
export function installGuidedTourHooks() {
  if (import.meta.server) return
  const tour = useGuidedTour()
  const route = useRoute()

  // Hydrate from localStorage on first mount
  onMounted(() => {
    const persisted = loadState()
    tour.state.value = persisted
    if (persisted.active) tour.showStepForCurrentPage()
  })

  watch(() => route.path, () => {
    if (tour.isActive.value) tour.showStepForCurrentPage()
  })
}

// ──────────────────────────── API helpers ────────────────────────────────
//
// The threshold state lives server-side in the Config DB — see
// ApiOnboardingController. The in-progress cursor (jclaw.tour.state above)
// stays in localStorage; only the "have they progressed past the auto-show
// threshold?" rule needs cross-session persistence.

export interface TourStatus {
  maxStepReached: number
  totalSteps: number
  shouldAutoShow: boolean
}

let inFlightRecord: Promise<unknown> | null = null

export async function loadTourStatus(): Promise<TourStatus> {
  try {
    return await $fetch<TourStatus>('/api/onboarding/tour-status')
  }
  catch (err) {
    // Fail closed — never pop a dialog over a broken state. Worst case,
    // the user opens the tour from the sidebar manually. Log to console so
    // an operator triaging "dialog didn't appear on first login" can see it.
    console.warn('[guidedTour] failed to load tour status; suppressing intro dialog', err)
    return { maxStepReached: 0, totalSteps: steps.length, shouldAutoShow: false }
  }
}

export async function recordStepReached(step: number): Promise<void> {
  // Serialize concurrent writes — if a POST is in flight, wait for it before
  // issuing this caller's own POST. Click-spam on Next produces N sequential
  // requests, not N parallel ones (or one coalesced one — see ADR below).
  // Why not true single-flight? B/C piggybacking on A's promise would skip
  // their own step values entirely, and only the backend's Math.max clamp
  // would prevent regression. The user's max step would be undercounted in
  // a rapid-click burst. Sequential is correct AND simple given the tour
  // is at most 5 Next clicks; we accept the small extra round-trips.
  if (inFlightRecord) {
    try {
      await inFlightRecord
    }
    catch {
      // ignore — we're issuing our own
    }
  }
  inFlightRecord = $fetch('/api/onboarding/tour-progress', {
    method: 'POST',
    body: { step },
  }).finally(() => {
    inFlightRecord = null
  })
  try {
    await inFlightRecord
  }
  catch {
    // Worst case: user retakes the tour next login. Don't block UI.
  }
}

/**
 * Test-only: reset the module-level in-flight slot. Vitest isolates per file
 * but not per test; if a prior test leaks a still-pending promise into the
 * slot, the next test's `if (inFlightRecord)` branch fires unexpectedly.
 * Production callers should never invoke this.
 */
export function __resetInFlightRecord() {
  inFlightRecord = null
}

export async function resetTourThreshold(): Promise<void> {
  await $fetch('/api/onboarding/tour-reset', { method: 'POST' })
  if (!import.meta.server) {
    try {
      localStorage.removeItem(STORAGE_KEY)
    }
    catch {
      // ignore
    }
  }
}
