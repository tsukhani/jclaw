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
  /** Appends rows outside a stream, following them down only when the reader was at the bottom. */
  appendFollowing: (append: () => void) => void
  /** {@link UseChatScroll.scrollToBottom} for a reader at the bottom only: a turn's frames leave one reading above alone. */
  followToBottom: () => void
}

export function useChatScroll(
  streaming: Ref<boolean>,
  streamReasoning: Ref<string>,
  loadOlder?: (keepViewport: PrependOlder) => Promise<void>,
): UseChatScroll {
  const messagesEl = ref<HTMLElement | null>(null)
  let scrollRaf: number | null = null

  // A viewport that shrinks (an expanded subagent chip above it) keeps scrollTop, dropping a reader off the bottom.
  const BOTTOM_SLACK_PX = 24
  let pinnedToBottom = true
  // Set by the reader's own scrolling alone: content that grows under a reader at the bottom leaves them following a turn.
  let readerAbove = false
  function atBottom(el: HTMLElement): boolean {
    return el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK_PX
  }

  // The page's own scrollTop writes fire scroll events too, and content that grows before one fires leaves it short of
  // the bottom: the event a write caused is not the reader scrolling up (JF-229).
  let writtenTop: number | null = null
  function setScrollTop(el: HTMLElement, top: number) {
    const before = el.scrollTop
    el.scrollTop = top
    // Read back: the browser clamps the write to the last position it can reach. A write that moved nothing fires no
    // event, so it records nothing, or the next scroll at that offset would pass for the page's own.
    if (el.scrollTop !== before) writtenTop = el.scrollTop
  }

  async function keepViewport(prepend: () => void): Promise<boolean> {
    const el = messagesEl.value
    const fromBottom = el ? el.scrollHeight - el.scrollTop : 0
    prepend()
    await nextTick()
    if (el) setScrollTop(el, el.scrollHeight - fromBottom)
    return true
  }

  // Within a screen of the top, older rows load until the reader is a screen away or the conversation's start shows.
  let pulling = false
  async function pullOlderNearTop() {
    if (!loadOlder || pulling) return
    pulling = true
    try {
      for (let el = messagesEl.value; el && el.scrollTop <= el.clientHeight; el = messagesEl.value) {
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
    // Every caller puts the reader at the bottom on purpose (a conversation opening, the operator's own send, a command's
    // answer, rows appended for a reader at the bottom), so the view follows again whatever the reader did before: the
    // list is reused across conversations.
    readerAbove = false
    pinnedToBottom = true
    if (scrollRaf) return
    scrollRaf = requestAnimationFrame(() => {
      if (messagesEl.value) setScrollTop(messagesEl.value, messagesEl.value.scrollHeight)
      scrollRaf = null
    })
  }

  /**
   * Reasoning bubble is a fixed-height scroll region (data-reasoning-body in
   * ChatThinkingCard.vue). As reasoning tokens stream in, pin
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

  function trackPinned(e: Event) {
    const el = e.currentTarget as HTMLElement
    const own = writtenTop != null && Math.abs(el.scrollTop - writtenTop) <= 1
    writtenTop = null
    if (own) {
      // Content that grew since the page's own write moved the bottom, not the reader. A write that put them at the
      // bottom puts them back to following, though they scrolled in the frame before it.
      if (atBottom(el)) readerAbove = false
      pinnedToBottom = !readerAbove
    }
    else {
      pinnedToBottom = atBottom(el)
      readerAbove = !pinnedToBottom
    }
    void pullOlderNearTop()
  }
  // The content is observed too: a card opened in place grows it without a scroll event, leaving the reader above the
  // bottom. While a turn streams, growth with no frame after it (a merge mid-turn, a late render) is followed instead,
  // unless the reader scrolled away (JF-229). The end-of-turn merge lands with streaming already off: endTurn follows it.
  const resizeObserver = typeof ResizeObserver === 'undefined'
    ? null
    : new ResizeObserver((entries) => {
        const el = messagesEl.value
        if (!el) return
        const viewportResized = entries.some(entry => entry.target === el)
        if ((pinnedToBottom && viewportResized) || (streaming.value && !readerAbove)) setScrollTop(el, el.scrollHeight)
        pinnedToBottom = atBottom(el)
        void pullOlderNearTop()
      })
  let observedContent: Element | null = null
  watch(messagesEl, (el, prev) => {
    // A new list (an empty chat's first turn mounts one) starts with its reader at the bottom, and the old list's pending
    // scroll event never arrives.
    pinnedToBottom = true
    readerAbove = false
    writtenTop = null
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

  // Read before the append: the content observer re-reads it as false once the new rows grow the content.
  function appendFollowing(append: () => void) {
    const follow = pinnedToBottom
    append()
    if (follow) scrollToBottom()
  }

  // Checked again when the frame runs: a reader scrolling up between a token and its frame stays where they went.
  let followRaf: number | null = null
  function followToBottom() {
    if (readerAbove || followRaf != null) return
    followRaf = requestAnimationFrame(() => {
      followRaf = null
      if (!readerAbove && messagesEl.value) setScrollTop(messagesEl.value, messagesEl.value.scrollHeight)
    })
  }

  onUnmounted(() => {
    if (scrollRaf) cancelAnimationFrame(scrollRaf)
    if (followRaf) cancelAnimationFrame(followRaf)
    if (reasoningScrollRaf) cancelAnimationFrame(reasoningScrollRaf)
    resizeObserver?.disconnect()
    if (messagesEl.value instanceof HTMLElement) messagesEl.value.removeEventListener('scroll', trackPinned)
  })

  return { messagesEl, scrollToBottom, keepViewport, appendFollowing, followToBottom }
}
