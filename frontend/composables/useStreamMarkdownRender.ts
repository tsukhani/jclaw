import { onUnmounted, ref, triggerRef, type Ref, type ShallowRef } from 'vue'
import { renderMarkdownStreaming } from '~/utils/chat-markdown'
import type { Message } from '~/types/api'

/**
 * Throttled markdown rendering for the in-flight streaming bubble (JCLAW-690
 * stage 4d; behaviour extracted verbatim from pages/chat.vue).
 *
 * The streaming bubble reads streamContentHtml / streamReasoningHtml (updated
 * at most once per STREAM_RENDER_INTERVAL_MS) instead of running the full
 * renderMarkdown pipeline on every token mutation — a 200 tok/s burst would
 * otherwise run marked.parse + DOMPurify on a growing string at full throttle.
 *
 * A cohesive sub-unit of the SSE machine with a narrow waist: it reads the raw
 * stream buffers + selectedAgentId, owns the throttle timers, and triggerRefs
 * the caller's messages shallowRef at the throttle cadence so the compiled
 * v-for slot re-runs and the bubble's v-html picks up the latest HTML. The
 * eventual useChatStream composes this rather than reimplementing it.
 */
export interface UseStreamMarkdownRender {
  streamContentHtml: Ref<string>
  streamReasoningHtml: Ref<string>
  scheduleStreamContentRender: () => void
  scheduleStreamReasoningRender: () => void
  flushStreamRender: () => void
}

export function useStreamMarkdownRender(
  streamContent: Ref<string>,
  streamReasoning: Ref<string>,
  selectedAgentId: Ref<number | null>,
  messages: ShallowRef<Message[]>,
): UseStreamMarkdownRender {
  // Throttled markdown HTML for the streaming bubble. Updated at most once per
  // STREAM_RENDER_INTERVAL_MS so a 200 tok/s reasoning burst does not run
  // marked.parse + DOMPurify on a growing string at full throttle. Final state
  // gets a forced flush in onComplete so the bubble lands on the exact final
  // text before we hand off to renderMarkdown's cached path.
  const streamContentHtml = ref('')
  const streamReasoningHtml = ref('')

  // Throttle interval for the streaming bubble's markdown render. ~80 ms gives
  // roughly 12.5 fps, which the eye reads as live for streaming text while
  // capping marked.parse + DOMPurify on a growing string. Streaming providers
  // often fire 50-200 events per second; without this cap the main thread
  // saturates by token ~1000 of a long reasoning response.
  const STREAM_RENDER_INTERVAL_MS = 80
  let streamRenderTimer: ReturnType<typeof setTimeout> | null = null
  let streamReasoningTimer: ReturnType<typeof setTimeout> | null = null

  function scheduleStreamContentRender() {
    if (streamRenderTimer != null) return
    streamRenderTimer = setTimeout(() => {
      streamRenderTimer = null
      streamContentHtml.value = renderMarkdownStreaming(streamContent.value, selectedAgentId.value)
      // Vue's compiled v-for slot tracks the messages shallowRef as its primary
      // dep — top-level refs read inside the slot's v-html (streamContentHtml)
      // alone don't always re-run the slot's render under v-for + shallowRef.
      // Force a re-render at the throttle's cadence so the in-flight bubble's
      // v-html picks up the latest streamContentHtml string. Cost is at most
      // STREAM_RENDER_INTERVAL_MS — i.e. the same cap the throttle already
      // imposes; tokens still stream as fast as they arrive.
      triggerRef(messages)
    }, STREAM_RENDER_INTERVAL_MS)
  }

  function scheduleStreamReasoningRender() {
    if (streamReasoningTimer != null) return
    streamReasoningTimer = setTimeout(() => {
      streamReasoningTimer = null
      streamReasoningHtml.value = renderMarkdownStreaming(streamReasoning.value, selectedAgentId.value)
      triggerRef(messages)
    }, STREAM_RENDER_INTERVAL_MS)
  }

  // Force an immediate render and clear any pending throttle timer. Called at
  // stream start (reset) and stream end (flush the last delta into the bubble
  // before we hand off to renderMarkdown's cached path).
  function flushStreamRender() {
    if (streamRenderTimer != null) {
      clearTimeout(streamRenderTimer)
      streamRenderTimer = null
    }
    if (streamReasoningTimer != null) {
      clearTimeout(streamReasoningTimer)
      streamReasoningTimer = null
    }
    streamContentHtml.value = renderMarkdownStreaming(streamContent.value, selectedAgentId.value)
    streamReasoningHtml.value = renderMarkdownStreaming(streamReasoning.value, selectedAgentId.value)
    triggerRef(messages)
  }

  onUnmounted(() => {
    if (streamRenderTimer != null) clearTimeout(streamRenderTimer)
    if (streamReasoningTimer != null) clearTimeout(streamReasoningTimer)
  })

  return {
    streamContentHtml,
    streamReasoningHtml,
    scheduleStreamContentRender,
    scheduleStreamReasoningRender,
    flushStreamRender,
  }
}
