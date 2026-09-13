import { onUnmounted, ref, watch, type Ref } from 'vue'

/**
 * Chat scroll coordination (JCLAW-690 stage 4).
 *
 * Owns the messages viewport element and the two RAF-coalesced autoscroll
 * behaviours extracted verbatim from pages/chat.vue:
 *  - {@link UseChatScroll.scrollToBottom} — pins the whole transcript to its
 *    bottom; called from every stream handler that appends content and from
 *    loadConversation / sendMessage.
 *  - the reasoning-body pin — keeps the in-flight message's fixed-height
 *    reasoning region scrolled to its latest thought while streaming.
 *
 * Both RAFs are cancelled on unmount. The composable reads the stream state
 * (`streaming`, `streamReasoning`) it needs as arguments rather than owning it,
 * so useChatStream stays the single owner of that state.
 */
export interface UseChatScroll {
  messagesEl: Ref<HTMLElement | null>
  scrollToBottom: () => void
}

export function useChatScroll(
  streaming: Ref<boolean>,
  streamReasoning: Ref<string>,
): UseChatScroll {
  const messagesEl = ref<HTMLElement | null>(null)
  let scrollRaf: number | null = null

  function scrollToBottom() {
    if (scrollRaf) return
    scrollRaf = requestAnimationFrame(() => {
      if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
      scrollRaf = null
    })
  }

  /**
   * Reasoning bubble is a fixed-height scroll region (see the h-80 data-
   * reasoning-body div in the template). As reasoning tokens stream in, pin
   * the last one to its own bottom so the latest thought is visible without
   * the user having to chase the scroll themselves. Only the in-flight
   * message's bubble is updated — historical messages keep whatever scroll
   * position the user set.
   *
   * RAF-coalesced so a 200 tok/s reasoning burst doesn't force a synchronous
   * layout reflow per chunk (scrollHeight read + scrollTop write is a layout-
   * thrash pattern when fired at chunk rate). Mirrors scrollToBottom's pattern.
   */
  let reasoningScrollRaf: number | null = null
  watch(streamReasoning, () => {
    if (!streaming.value || reasoningScrollRaf != null) return
    reasoningScrollRaf = requestAnimationFrame(() => {
      reasoningScrollRaf = null
      const bodies = messagesEl.value?.querySelectorAll<HTMLElement>('[data-reasoning-body]')
      const last = bodies?.[bodies.length - 1]
      if (last) last.scrollTop = last.scrollHeight
    })
  })

  // A viewport that shrinks (an expanded subagent chip above it) keeps scrollTop, dropping a reader off the bottom.
  const BOTTOM_SLACK_PX = 24
  let pinnedToBottom = true
  function trackPinned(e: Event) {
    const el = e.currentTarget as HTMLElement
    pinnedToBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK_PX
  }
  const resizeObserver = typeof ResizeObserver === 'undefined'
    ? null
    : new ResizeObserver(() => {
        const el = messagesEl.value
        if (pinnedToBottom && el) el.scrollTop = el.scrollHeight
      })
  watch(messagesEl, (el, prev) => {
    if (!resizeObserver) return
    if (prev instanceof HTMLElement) {
      prev.removeEventListener('scroll', trackPinned)
      resizeObserver.unobserve(prev)
    }
    if (el instanceof HTMLElement) {
      el.addEventListener('scroll', trackPinned, { passive: true })
      resizeObserver.observe(el)
    }
  })

  onUnmounted(() => {
    if (scrollRaf) cancelAnimationFrame(scrollRaf)
    if (reasoningScrollRaf) cancelAnimationFrame(reasoningScrollRaf)
    resizeObserver?.disconnect()
    if (messagesEl.value instanceof HTMLElement) messagesEl.value.removeEventListener('scroll', trackPinned)
  })

  return { messagesEl, scrollToBottom }
}
