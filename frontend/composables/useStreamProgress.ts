import { computed, onUnmounted, ref, watch, type ComputedRef, type Ref } from 'vue'

/**
 * Live progress affordance for an in-flight chat turn: a two-phase label
 * ("Prefilling…" → "Generating…") plus a continuously-ticking elapsed timer,
 * shown from send until the stream ends.
 *
 * The phases are derived purely from client-observable stream state — no
 * backend signal is needed:
 *   - **Prefilling** — the stream is open but no token has arrived yet. On a
 *     local model this is the (often long) prompt-prefill / model-load window
 *     where the GPU is busy but nothing is on screen. It is exactly the gap
 *     the pre-first-byte placeholder used to cover.
 *   - **Generating** — the first reasoning or content delta has landed and the
 *     model is producing output. {@code producing} latches true on that first
 *     token and holds for the rest of the turn (content/reasoning only grow
 *     within a turn), so the label never flickers back to "Prefilling…".
 *
 * The timer runs continuously across both phases so a slow turn no longer looks
 * hung, then resets when {@code streaming} flips false. It counts from
 * {@code startedAt}, the server's start time from the turn's {@code init}, so a
 * view that joins a turn late (a reload, another tab) shows how long it has run.
 * A skew between the browser's clock and the server's shifts the count; it
 * never reads below 0:00.
 *
 * The "Prefilling…" label is gated on {@code local}: it only makes sense for a
 * self-hosted model, whose prompt-prefill / model-load window is long and
 * visible. On a cloud provider the pre-first-token gap is a sub-second
 * network/queue hop, so the label reads "Generating…" from the first frame
 * (the timer still runs).
 *
 * @param streaming  page-level stream flag (true for the whole turn)
 * @param producing  true once the model has emitted its first token
 *                   (typically {@code !!streamContent || !!streamReasoning})
 * @param local      true when the active provider is self-hosted — gates the
 *                   "Prefilling…" label
 * @param startedAt  epoch ms the turn started; null counts from when streaming began
 * @param tickMs     timer refresh cadence; 1s matches the m:ss display
 */
export interface UseStreamProgress {
  /** Whether the indicator should render — mirrors {@code streaming}. */
  active: ComputedRef<boolean>
  /** Current phase. */
  phase: ComputedRef<'prefill' | 'generating'>
  /** Human label for the phase, e.g. "Prefilling…" / "Generating…". */
  label: ComputedRef<string>
  /** Elapsed time since the turn started, formatted as {@code m:ss}. */
  elapsed: ComputedRef<string>
  /** Raw elapsed milliseconds (exposed for callers/tests that want the number). */
  elapsedMs: Ref<number>
}

/** Format a millisecond span as {@code m:ss} (7 200 → "0:07", 75 000 → "1:15"). */
function formatElapsed(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
}

export function useStreamProgress(
  streaming: Ref<boolean>,
  producing: Ref<boolean>,
  local: Ref<boolean>,
  startedAt: Ref<number | null> = ref(null),
  tickMs = 1000,
): UseStreamProgress {
  const elapsedMs = ref(0)
  let anchor = 0
  let timer: ReturnType<typeof setInterval> | null = null

  function clearTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  // Start the ticker when a turn begins, tear it down when it ends. Anchoring
  // to a captured start timestamp (rather than incrementing) keeps the elapsed
  // value correct even if a tick is delayed or coalesced.
  watch([streaming, startedAt], ([on, start]) => {
    clearTimer()
    if (on) {
      anchor = start ?? Date.now()
      elapsedMs.value = Math.max(0, Date.now() - anchor)
      timer = setInterval(() => {
        elapsedMs.value = Date.now() - anchor
      }, tickMs)
    }
    else {
      elapsedMs.value = 0
    }
  }, { immediate: true })

  onUnmounted(clearTimer)

  const active = computed(() => streaming.value)
  const phase = computed<'prefill' | 'generating'>(() => (producing.value ? 'generating' : 'prefill'))
  // "Prefilling…" only for a local (self-hosted) model; cloud shows "Generating…"
  // from the first frame since its pre-first-token gap is a network/queue hop.
  const label = computed(() => (phase.value === 'prefill' && local.value ? 'Prefilling…' : 'Generating…'))
  const elapsed = computed(() => formatElapsed(elapsedMs.value))

  return { active, phase, label, elapsed, elapsedMs }
}
