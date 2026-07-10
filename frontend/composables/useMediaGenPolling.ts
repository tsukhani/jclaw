import { onUnmounted, ref, triggerRef, watch, type Ref, type ShallowRef } from 'vue'
import { isVideoJobPending, type VideoJobStatus } from '~/utils/video-job'
import type { Message } from '~/types/api'

/**
 * Media-generation progress polling (JCLAW-690 stage 4c; behaviour extracted
 * verbatim from pages/chat.vue).
 *
 * Two independent pollers that both straddle the stream and conversation
 * lifecycles, so they live together in one composable:
 *  - video (JCLAW-234): a generated-video attachment starts as a zero-byte
 *    placeholder linked to a VideoGenerationJob; poll job status until it fills
 *    or fails. Starts from a generate_video tool-call and resumes on
 *    conversation (re)load for any still-pending placeholder.
 *  - image (JCLAW-228/683): image gen is synchronous, so poll the sidecar's
 *    live step-percent while a generate_image tool call runs, scoped to the
 *    invoking turn via imageGenTurnKey.
 *
 * Reads `messages` (the shallowRef, so it can triggerRef after a status merge)
 * and `streaming` as arguments; owns the timers, status refs, and unmount
 * cleanup. The caller sets imageGenTurnKey and calls the start* controls from
 * its stream/conversation handlers.
 */
export interface UseMediaGenPolling {
  videoJobStatus: Ref<Record<number, VideoJobStatus>>
  imageGenPercent: Ref<number | null>
  imageGenTurnKey: Ref<string | null>
  startVideoPolling: () => void
  stopVideoPolling: () => void
  startImageProgressPolling: () => void
}

export function useMediaGenPolling(
  messages: ShallowRef<Message[]>,
  streaming: Ref<boolean>,
): UseMediaGenPolling {
  // JCLAW-234: async video-generation progress. A generated-video attachment starts as a zero-byte
  // placeholder linked to a VideoGenerationJob; we poll the job status (~2s — the SV-4 decision: polling,
  // not SSE, because the job outlives the per-turn chat stream) until it fills or fails, then the card
  // swaps to an inline player or an error. Mirrors settings.vue's imagegen-download poll: start on a
  // trigger, stop when nothing's pending, clean up on unmount.
  const videoJobStatus = ref<Record<number, VideoJobStatus>>({})
  const VIDEO_POLL_INTERVAL_MS = 2000
  let videoPollTimer: ReturnType<typeof setInterval> | null = null

  function pendingVideoJobIds(): number[] {
    const ids: number[] = []
    for (const m of messages.value) {
      for (const a of (m.attachments ?? [])) {
        const jobId = a.generationJobId
        if (jobId != null && isVideoJobPending(a, videoJobStatus.value[jobId]) && !ids.includes(jobId)) {
          ids.push(jobId)
        }
      }
    }
    return ids
  }

  async function pollVideoJobs() {
    const ids = pendingVideoJobIds()
    if (!ids.length) {
      stopVideoPolling()
      return
    }
    try {
      const data = await $fetch<VideoJobStatus[]>(`/api/videogen/jobs?ids=${ids.join(',')}`)
      for (const s of data ?? []) videoJobStatus.value[s.id] = s
      triggerRef(messages)
    }
    catch {
      // transient — the next tick retries
    }
    if (!pendingVideoJobIds().length) stopVideoPolling()
  }

  function startVideoPolling() {
    if (videoPollTimer != null || !pendingVideoJobIds().length) return
    void pollVideoJobs()
    videoPollTimer = setInterval(() => void pollVideoJobs(), VIDEO_POLL_INTERVAL_MS)
  }

  function stopVideoPolling() {
    if (videoPollTimer != null) {
      clearInterval(videoPollTimer)
      videoPollTimer = null
    }
  }

  // Local image-gen progress bar (JCLAW-228). Image gen is synchronous (no job to poll like video), so the
  // chat polls the sidecar's live step-percent while a generate_image tool call is running; the endpoint
  // returns null for cloud providers and when idle, so the bar only appears for an in-flight LOCAL generation.
  const imageGenPercent = ref<number | null>(null)
  // JCLAW-683: _key of the assistant message whose turn actually invoked
  // generate_image. The progress bar is scoped to this turn (mirroring the
  // generated-video card's generationJobId keying) instead of a global footer
  // bar, so a concurrent generation's 0%-load phase can't leak onto an unrelated
  // streaming turn. Set from handleStreamToolCallEvent, cleared when polling stops.
  const imageGenTurnKey = ref<string | null>(null)
  const IMAGE_PROGRESS_INTERVAL_MS = 1000
  let imageProgressTimer: ReturnType<typeof setInterval> | null = null

  async function pollImageProgress() {
    try {
      const r = await $fetch<{ percent: number | null }>('/api/imagegen/progress')
      // Guard against a late-resolving poll after polling stopped — it must not resurrect a stale bar.
      if (imageProgressTimer != null) imageGenPercent.value = r?.percent ?? null
    }
    catch {
      if (imageProgressTimer != null) imageGenPercent.value = null // transient — next tick retries
    }
  }
  function startImageProgressPolling() {
    if (imageProgressTimer != null) return
    // Set the timer before the first poll so pollImageProgress's "still polling?" guard sees it as active.
    imageProgressTimer = setInterval(() => void pollImageProgress(), IMAGE_PROGRESS_INTERVAL_MS)
    void pollImageProgress()
  }
  function stopImageProgressPolling() {
    if (imageProgressTimer != null) {
      clearInterval(imageProgressTimer)
      imageProgressTimer = null
    }
    imageGenPercent.value = null
    imageGenTurnKey.value = null
  }
  // JCLAW-683: polling now STARTS from handleStreamToolCallEvent when a
  // generate_image tool call fires (scoped to that turn) rather than on every
  // streaming turn. This watch only tears the poll down when the turn ends, so a
  // stale bar never carries into the next turn.
  watch(streaming, (on) => {
    if (!on) stopImageProgressPolling()
  })

  onUnmounted(() => {
    stopVideoPolling()
    stopImageProgressPolling()
  })

  return {
    videoJobStatus,
    imageGenPercent,
    imageGenTurnKey,
    startVideoPolling,
    stopVideoPolling,
    startImageProgressPolling,
  }
}
