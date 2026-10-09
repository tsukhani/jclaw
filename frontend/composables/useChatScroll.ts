import { nextTick, onUnmounted, ref, watch, type Ref } from 'vue'
import type { PrependOlder } from '~/composables/useChatConversation'

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
  /** Prepends older rows without moving what the reader sees. */
  keepViewport: PrependOlder
}

export function useChatScroll(
  streaming: Ref<boolean>,
  streamReasoning: Ref<string>,
  loadOlder?: (keepViewport: PrependOlder) => Promise<void>,
): UseChatScroll {
  const messagesEl = ref<HTMLElement | null>(null)
  let scrollRaf: number | null = null

  // A turn streams into its bubble by index (useChatStream's assistantIdx), so nothing is prepended while one runs.
  async function keepViewport(prepend: () => void): Promise<boolean> {
    if (streaming.value) return false
    const el = messagesEl.value
    const fromBottom = el ? el.scrollHeight - el.scrollTop : 0
    prepend()
    await nextTick()
    if (el) el.scrollTop = el.scrollHeight - fromBottom
    return true
  }

  // Within a screen of the top, older rows load until the reader is a screen away or the conversation's start shows.
  let pulling = false
  async function pullOlderNearTop() {
    if (!loadOlder || pulling) return
    pulling = true
    try {
      for (let el = messagesEl.value; el && !streaming.value && el.scrollTop <= el.clientHeight; el = messagesEl.value) {
        const height = el.scrollHeight
        await loadOlder(keepViewport)
        if (el.scrollHeight === height) break
      }
    }
    finally {
      pulling = false
    }
  }

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
  function atBottom(el: HTMLElement): boolean {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK_PX
  }
  function trackPinned(e: Event) {
    pinnedToBottom = atBottom(e.currentTarget as HTMLElement)
    void pullOlderNearTop()
  }
  // The content is observed too: a card opened in place grows it without a scroll event, leaving the reader above the bottom.
  const resizeObserver = typeof ResizeObserver === 'undefined'
    ? null
    : new ResizeObserver((entries) => {
        const el = messagesEl.value
        if (!el) return
        if (pinnedToBottom && entries.some(entry => entry.target === el)) el.scrollTop = el.scrollHeight
        pinnedToBottom = atBottom(el)
        void pullOlderNearTop()
      })
  let observedContent: Element | null = null
  watch(messagesEl, (el, prev) => {
    if (!resizeObserver) return
    if (prev instanceof HTMLElement) {
      prev.removeEventListener('scroll', trackPinned)
      resizeObserver.unobserve(prev)
    }
    if (observedContent) resizeObserver.unobserve(observedContent)
    observedContent = null
    if (el instanceof HTMLElement) {
      el.addEventListener('scroll', trackPinned, { passive: true })
      resizeObserver.observe(el)
      observedContent = el.firstElementChild
      if (observedContent) resizeObserver.observe(observedContent)
    }
  })

  onUnmounted(() => {
    if (scrollRaf) cancelAnimationFrame(scrollRaf)
    if (reasoningScrollRaf) cancelAnimationFrame(reasoningScrollRaf)
    resizeObserver?.disconnect()
    if (messagesEl.value instanceof HTMLElement) messagesEl.value.removeEventListener('scroll', trackPinned)
  })

  return { messagesEl, scrollToBottom, keepViewport }
}
