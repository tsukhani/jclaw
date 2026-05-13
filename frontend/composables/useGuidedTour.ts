import { driver, type Driver } from 'driver.js'

interface TourState {
  step: number
  active: boolean
}

interface TourStep {
  path: string
  selector: string
  /** Step heading shown in the popover header. */
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
   * Override the Next/Done button label for this step. Default is "Next →"
   * for intermediate steps and "Finish" for the final step.
   */
  nextBtnText?: string
  /**
   * Show an explicit "Finish" button (rendered in driver.js's Previous slot)
   * that ends the tour. Use on optional-junction steps where the user may
   * reasonably choose to stop rather than continue.
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
    description: 'The <strong>Global Skills</strong> registry holds reusable capabilities you can attach to any agent — drag a skill onto an agent card in the Agents panel to assign it.',
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

  // In-memory only. Reloading the page abandons the tour by design —
  // the user opts back in via the "Guided Tour" sidebar entry.
  const state = useState<TourState>('jclaw-guided-tour', () => ({ step: 0, active: false }))

  // Intro dialog open flag. Shared via useState so the sidebar button
  // (default layout) and the first-login auto-show (also default layout,
  // on mount) can both toggle it, and so components can v-bind to it.
  const introOpen = useState<boolean>('jclaw-guided-tour-intro', () => false)

  // NOSONAR(typescript:S7721) — kept inside the composable so all sibling
  // helpers (advance, back, complete, end) share a single closure and the
  // public API surfaces as a unit; hoisting would require re-exporting the
  // module-level state piecemeal.
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
    const isFirst = state.value.step === 0
    // showButtons intentionally omits 'close': the X in the popover header
    // has been removed. The only way out of the walkthrough mid-flight is
    // to reload the page (which clears the in-memory state and returns the
    // user to the Guided Tour sidebar entry for a fresh start) or to reach
    // the final step and press Finish.
    const buttons: ('next')[] = ['next']
    // Single-step popovers render doneBtnText (not nextBtnText) by default, so
    // set both to the same computed value. Arrow suffix on "Next" signals the
    // forward direction; "Finish" stays bare since it's terminal, not a step.
    const buttonText = step.nextBtnText ?? (isLast ? 'Finish' : 'Next →')
    driverInstance = driver({
      popoverClass: 'jclaw-tour',
      overlayOpacity: 0.55,
      allowClose: false,
      showButtons: buttons,
      nextBtnText: buttonText,
      doneBtnText: buttonText,
      onNextClick: () => {
        if (isLast) complete()
        else advance()
      },
      // Footer customizations: driver.js gives us an empty-ish footer with a
      // Next button. We inject (in left-to-right order):
      //   1. A progress counter "N of M" in the leading slot.
      //   2. A Previous button on steps beyond the first, unless the step
      //      already reserves the secondary slot for Finish (optional-junction
      //      steps treat early-exit as the meaningful backward action).
      //   3. A Finish button when the step opts in via showFinishButton.
      // All injection paths guard on popover.footerButtons existing — driver.js
      // sometimes skips footer rendering entirely on hidden-Next steps.
      onPopoverRender: (popover) => {
        if (!popover.footerButtons) return

        if (step.hideNextButton && popover.nextButton) {
          popover.nextButton.style.display = 'none'
        }

        const progress = document.createElement('span')
        progress.className = 'jclaw-tour-progress'
        progress.textContent = `${state.value.step + 1} of ${steps.length}`
        popover.footerButtons.insertBefore(progress, popover.footerButtons.firstChild)

        if (!isFirst && !step.showFinishButton) {
          const prevBtn = document.createElement('button')
          prevBtn.type = 'button'
          prevBtn.textContent = '← Previous'
          prevBtn.className = 'jclaw-tour-back-btn'
          prevBtn.addEventListener('click', (e) => {
            e.preventDefault()
            e.stopPropagation()
            back()
          })
          if (popover.nextButton) {
            popover.footerButtons.insertBefore(prevBtn, popover.nextButton)
          }
          else {
            popover.footerButtons.appendChild(prevBtn)
          }
        }

        if (step.showFinishButton) {
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
        }
      },
      steps: [{
        element: step.selector,
        popover: {
          title: step.title,
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

  /** Open the intro dialog. Always opens regardless of backend flag — the
   *  flag governs auto-show on first login only. Manual invocation from the
   *  sidebar should always succeed. */
  function showIntro() {
    introOpen.value = true
  }

  /** User clicked Start on the intro dialog → close dialog, kick off the
   *  walkthrough, and mark the tour as seen so it doesn't auto-show again. */
  function confirmStart() {
    introOpen.value = false
    // Flip the backend "seen" flag so the next login doesn't auto-show.
    // Fire-and-forget: if the POST fails, the worst case is the user sees
    // the intro once more; the backend clamps with Math.max so it's
    // idempotent.
    void recordStepReached(1)
    const first = steps[0]
    if (!first) return
    state.value = { step: 0, active: true }
    if (route.path === first.path) showStepForCurrentPage()
    else router.push(first.path)
  }

  /** User dismissed the intro dialog without starting → close dialog and
   *  mark the tour as seen. They can re-open via the sidebar at any time. */
  function dismissIntro() {
    introOpen.value = false
    void recordStepReached(1)
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
    if (route.path === nextStep.path) showStepForCurrentPage()
    else router.push(nextStep.path)
  }

  function back() {
    destroy()
    const prev = state.value.step - 1
    const prevStep = steps[prev]
    if (!prevStep) return
    // No recordStepReached on backward navigation: maxStepReached is a
    // high-water mark and the backend already clamps with Math.max, so
    // moving backward through the tour must not lower it.
    state.value = { step: prev, active: true }
    if (route.path === prevStep.path) showStepForCurrentPage()
    else router.push(prevStep.path)
  }

  function end() {
    destroy()
    state.value = { step: 0, active: false }
  }

  function complete() {
    destroy()
    // Reaching the final step = recording total step count
    void recordStepReached(steps.length)
    state.value = { step: 0, active: false }
  }

  return {
    showIntro,
    confirmStart,
    dismissIntro,
    introOpen,
    end,
    isActive: computed(() => state.value.active),
    state,
    showStepForCurrentPage,
  }
}

/**
 * Install the route watch that keeps the popover in sync with the current
 * page. Intended to be called once from the root layout. Keeping this
 * separate from useGuidedTour() lets pages consume the composable for its
 * state without each one registering duplicate watchers.
 */
export function installGuidedTourHooks() {
  if (import.meta.server) return
  const tour = useGuidedTour()
  const route = useRoute()

  watch(() => route.path, () => {
    if (tour.isActive.value) tour.showStepForCurrentPage()
  })
}

// ──────────────────────────── API helpers ────────────────────────────────
//
// The "has the user ever seen/resolved the intro dialog?" flag lives
// server-side in the Config DB — see ApiOnboardingController. maxStepReached
// doubles as the flag: 0 means never seen, anything ≥ 1 means seen.

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
  // Best-effort serialization — if a POST is in flight, wait for it before
  // issuing this caller's own POST. Three rapid calls produce one sequential
  // POST followed by up to two parallel ones (after the first POST resolves,
  // the .finally clears the slot and the queued awaiters race to assign
  // their own $fetch). Correctness is guaranteed by the backend's Math.max
  // clamp regardless of arrival order; this code only tries to avoid the
  // worst case of N fully-parallel writes.
  // Why not true single-flight (B/C piggyback on A's promise)? They would
  // skip their own step values entirely, leaving the user's max step
  // undercounted in a rapid-click burst (only the backend Math.max would
  // catch it, and only if a later call ever lands). Best-effort here is
  // correct AND simple given the tour is at most 5 Next clicks.
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
