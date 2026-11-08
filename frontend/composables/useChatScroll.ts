import { nextTick, onUnmounted, ref, watch, type Ref } from 'vue'
import type { PrependOlder } from '~/composables/useChatConversation'

/**
 * Chat scroll coordination (JCLAW-690 stage 4).
 *
 * Owns the messages viewport element and how it follows the conversation:
 *  - {@link UseChatScroll.scrollToBottom} pins the transcript to its bottom: a conversation opening, the operator's
 *    own send, a command's answer, rows appended for a reader at the bottom.
 *  - {@link UseChatScroll.followToBottom} follows a turn's frames for a reader who has not scrolled away, and the
 *    content observer follows growth while a turn streams.
 *  - the reasoning-body pin keeps the in-flight message's fixed-height reasoning region on its latest thought.
 *
 * Only the reader's own input says they scrolled away (JF-229): the browser fires scroll events for the page's
 * writes and for layout changes too. Its frames and listeners are released on unmount. The composable reads the
 * stream state (`streaming`, `streamReasoning`) it needs as arguments, so useChatStream stays its single owner.
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

  // Only the reader's own input says they scrolled away. The browser fires scroll events for the page's own writes and
  // for layout changes too (a conversation's rows swapped in as it opens), and any of them can land short of a bottom
  // that is still growing: read as the reader's, one stopped the follow for good (JF-229). A moment after the reader's
  // input the page's own writes are still told apart, as their events can land short of the bottom as well.
  const READER_INPUT_MS = 600
  const SCROLL_KEYS = new Set(['PageUp', 'PageDown', 'ArrowUp', 'ArrowDown', 'Home', 'End', ' '])
  let readerInputAt = Number.NEGATIVE_INFINITY
  // Held on the list's own box (its scrollbar, its margins) or with the middle button: a drag, which can outlast the
  // moment; held on a row, the reader may drag a selection that carries the list along.
  let draggingList = false
  let pointerOnRow = false
  let writtenTop: number | null = null
  // Where the page or the reader last put the view. A move up from it that this page saw no input for is the reader's
  // once it passes the slack, whatever moved it (a wheel over an embedded frame, find in page): a layout change keeps a
  // reader at the bottom where they were. Measured from here, not from the last event, so a slow drift adds up.
  let placedTop: number | null = null
  // The furthest the view could reach when it was placed. A clamp moves the view up to the very bottom once that falls
  // (rows that shrank, a taller viewport); any other move up is a move, even when rows below the view shrank too.
  let placedMax = 0
  function place(el: HTMLElement, top: number) {
    placedMax = Math.max(0, el.scrollHeight - el.clientHeight)
    placedTop = Math.min(Math.max(top, 0), placedMax)
  }
  function setScrollTop(el: HTMLElement, top: number) {
    const before = el.scrollTop
    el.scrollTop = top
    // Read back: the browser clamps the write. A write that moved nothing fires no event, so it records nothing.
    if (el.scrollTop !== before) writtenTop = el.scrollTop
    place(el, el.scrollTop)
  }
  function noteReaderInput() {
    readerInputAt = performance.now()
  }
  function noteReaderWheel(e: Event) {
    const wheel = e as WheelEvent
    // A sideways swipe (a wide code block), a pinch-zoom, a wheel something else took, or one down at the very bottom
    // does not move the list. Inside the slack it still can, and that is a reader coming back.
    if (wheel.deltaY === 0 || wheel.ctrlKey || wheel.defaultPrevented) return
    const el = e.currentTarget as HTMLElement
    if (wheel.deltaY > 0 && el.scrollHeight - el.scrollTop - el.clientHeight <= 1) return
    noteReaderInput()
  }
  function noteReaderKey(e: Event) {
    const key = (e as KeyboardEvent).key
    const target = e.target as HTMLElement | null
    if (!SCROLL_KEYS.has(key) || !target) return
    if (target.isContentEditable || /^(?:INPUT|TEXTAREA|SELECT)$/.test(target.tagName)) return
    // Space presses a focused button rather than scrolling; on a link it still scrolls.
    if (key === ' ' && target.closest('button, [role="button"], summary')) return
    noteReaderInput()
  }
  function noteReaderPointer(e: Event) {
    const button = (e as MouseEvent).button ?? 0
    // A right-click opens a menu, and its release can go to the menu.
    if (button > 1) return
    if (button === 1 || e.target === e.currentTarget) {
      draggingList = true
      noteReaderInput()
    }
    else {
      pointerOnRow = true
    }
  }
  function noteReaderPointerMove(e: Event) {
    // A move with no button held is a release this page never heard.
    if ((e as PointerEvent).buttons === 0) {
      endPointer()
      return
    }
    if (pointerOnRow) noteReaderInput()
  }
  // Focus a click moved is not a scroll; focus Tab or a screen reader moved is.
  function noteReaderFocus() {
    if (!pointerOnRow && !draggingList) noteReaderInput()
  }
  function forgetReaderInput() {
    readerInputAt = Number.NEGATIVE_INFINITY
    draggingList = false
    pointerOnRow = false
  }
  function endPointer() {
    draggingList = false
    pointerOnRow = false
  }
  function byReader(): boolean {
    return draggingList || performance.now() - readerInputAt <= READER_INPUT_MS
  }
  const passive = { passive: true }
  function watchReaderInput(el: HTMLElement) {
    el.addEventListener('wheel', noteReaderWheel, passive)
    // A touch scrolls only once it moves: a tap on a card is not a scroll.
    el.addEventListener('touchmove', noteReaderInput, passive)
    el.addEventListener('keydown', noteReaderKey)
    // Tab, and a screen reader moving through the list, scroll it to the focused row.
    el.addEventListener('focusin', noteReaderFocus)
    el.addEventListener('pointerdown', noteReaderPointer)
    el.addEventListener('pointermove', noteReaderPointerMove, passive)
  }
  function unwatchReaderInput(el: HTMLElement) {
    el.removeEventListener('wheel', noteReaderWheel)
    el.removeEventListener('touchmove', noteReaderInput)
    el.removeEventListener('keydown', noteReaderKey)
    el.removeEventListener('focusin', noteReaderFocus)
    el.removeEventListener('pointerdown', noteReaderPointer)
    el.removeEventListener('pointermove', noteReaderPointerMove)
  }
  // A drag can end anywhere on the page (captured, so a row that stops the event cannot hide it), at a context menu, or
  // with the window losing focus mid-drag.
  window.addEventListener('pointerup', endPointer, true)
  window.addEventListener('pointercancel', endPointer, true)
  window.addEventListener('contextmenu', endPointer, true)
  window.addEventListener('blur', endPointer)

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
    forgetReaderInput()
    // The list is reused: where the last conversation left the view says nothing about this one.
    placedTop = null
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
    const max = Math.max(0, el.scrollHeight - el.clientHeight)
    // A position past either end, as an overscroll bounce may report, is clamped so its bounce back is no move.
    const top = Math.min(Math.max(el.scrollTop, 0), max)
    const fromBottom = max - top
    const bottom = fromBottom <= BOTTOM_SLACK_PX
    const own = writtenTop != null && Math.abs(top - writtenTop) <= 1
    writtenTop = null
    const placed = placedTop
    const clamped = max < placedMax - 0.5 && fromBottom <= 1
    const movedUp = placed != null && top < placed && !clamped
    // A reader's nudge can land within a pixel of the page's own write and arrive with its event.
    if (byReader() && (!own || movedUp)) {
      if (placed == null) {
        readerAbove = !bottom
      }
      else {
        // Any move up the reader makes, a few pixels included, means they are reading what came before. Coming back
        // into the slack (any move down, or resting at the very bottom) puts them back to following.
        const back = !movedUp && (top > placed || fromBottom <= 1)
        readerAbove = movedUp || (readerAbove && !(bottom && back))
      }
      place(el, top)
    }
    else if (!own && placed != null && top < placed - BOTTOM_SLACK_PX && !bottom) {
      readerAbove = true
      place(el, top)
    }
    else {
      // Not the reader's: content that grew moved the bottom, not them. Only the very bottom puts a reader who scrolled
      // away back to following; a layout shift that leaves them inside the slack is not them coming back.
      if (fromBottom <= 1) readerAbove = false
      if (own || fromBottom <= 1 || placed == null || top > placed) place(el, top)
    }
    // While a turn streams, an event short of its growing bottom keeps following; outside one, a reader the rows grew
    // away from (a card opened in place) stays where they are when the next rows are appended.
    pinnedToBottom = !readerAbove && (bottom || own || streaming.value)
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
        // A reader who scrolled up can still be within the slack: the next append must not pull them down.
        pinnedToBottom = !readerAbove && atBottom(el)
        void pullOlderNearTop()
      })
  let observedContent: Element | null = null
  watch(messagesEl, (el, prev) => {
    // A new list (an empty chat's first turn mounts one) starts with its reader at the bottom.
    pinnedToBottom = true
    readerAbove = false
    writtenTop = null
    placedTop = null
    forgetReaderInput()
    if (!resizeObserver) return
    if (prev instanceof HTMLElement) {
      prev.removeEventListener('scroll', trackPinned)
      unwatchReaderInput(prev)
      resizeObserver.unobserve(prev)
    }
    if (observedContent) resizeObserver.unobserve(observedContent)
    observedContent = null
    if (el instanceof HTMLElement) {
      el.addEventListener('scroll', trackPinned, { passive: true })
      watchReaderInput(el)
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
    if (messagesEl.value instanceof HTMLElement) {
      messagesEl.value.removeEventListener('scroll', trackPinned)
      unwatchReaderInput(messagesEl.value)
    }
    window.removeEventListener('pointerup', endPointer, true)
    window.removeEventListener('pointercancel', endPointer, true)
    window.removeEventListener('contextmenu', endPointer, true)
    window.removeEventListener('blur', endPointer)
  })

  return { messagesEl, scrollToBottom, keepViewport, appendFollowing, followToBottom }
}
