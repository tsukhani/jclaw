import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useChatScroll, type UseChatScroll } from '~/composables/useChatScroll'

// Queue-based requestAnimationFrame stub. Unlike a synchronous stub, deferring
// the callbacks lets us assert the RAF *coalescing* (a second scrollToBottom
// while one is pending must not schedule a second frame) before flushing.
let rafQueue: FrameRequestCallback[] = []
let rafId = 0

function flushRaf() {
  const pending = rafQueue
  rafQueue = []
  for (const cb of pending) cb(0)
}

beforeEach(() => {
  rafQueue = []
  rafId = 0
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    rafQueue.push(cb)
    return ++rafId
  })
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/**
 * Mount the composable inside a throwaway component so it gets a real
 * component instance (onUnmounted + watch need one). Returns the reactive
 * inputs plus the composable's surface for direct driving.
 */
function mountScroll() {
  const streaming = ref(false)
  const streamReasoning = ref('')
  let api!: UseChatScroll
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useChatScroll(streaming, streamReasoning)
        return () => h('div')
      },
    }),
  )
  // setup ran synchronously during mount(), so `api` is assigned here.
  return { wrapper, streaming, streamReasoning, api }
}

/** Minimal stand-in for the reasoning-body query: querySelectorAll returns the
 *  supplied bodies so we can assert the pin without a laid-out DOM. */
function fakeViewport(bodies: Array<{ scrollTop: number, scrollHeight: number }>): HTMLElement {
  return {
    querySelectorAll: () => bodies,
  } as unknown as HTMLElement
}

/** A ResizeObserver whose callback the test fires with the targets that changed size. */
function stubResizeObserver() {
  const probe = { fire: (_targets: Element[]) => {}, observed: [] as Element[] }
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: (entries: Array<{ target: Element }>) => void) {
      probe.fire = targets => callback(targets.map(target => ({ target })))
    }

    observe(target: Element) {
      probe.observed.push(target)
    }

    unobserve() {}
    disconnect() {}
  })
  return probe
}

/** A viewport holding the one content rail chat.vue renders, with a settable layout box. */
function viewportWithContent() {
  const el = document.createElement('div')
  const content = el.appendChild(document.createElement('div'))
  const layout = (box: { scrollTop: number, scrollHeight: number, clientHeight: number }) => {
    for (const [key, value] of Object.entries(box)) {
      Object.defineProperty(el, key, { configurable: true, writable: true, value })
    }
  }
  return { el, content, layout }
}

describe('useChatScroll', () => {
  it('pins the viewport to its bottom on the next frame', () => {
    const { api } = mountScroll()
    const el = { scrollTop: 0, scrollHeight: 500 } as HTMLElement
    api.messagesEl.value = el

    api.scrollToBottom()
    expect(el.scrollTop).toBe(0) // deferred to the frame
    flushRaf()
    expect(el.scrollTop).toBe(500)
  })

  it('coalesces overlapping scrollToBottom calls into one frame', () => {
    const { api } = mountScroll()
    api.messagesEl.value = { scrollTop: 0, scrollHeight: 100 } as HTMLElement

    api.scrollToBottom()
    api.scrollToBottom()
    api.scrollToBottom()
    expect(rafQueue).toHaveLength(1)

    // After the frame runs the guard resets, so a later call schedules again.
    flushRaf()
    api.scrollToBottom()
    expect(rafQueue).toHaveLength(1)
  })

  it('is a no-op when the viewport ref is unset', () => {
    const { api } = mountScroll()
    expect(() => {
      api.scrollToBottom()
      flushRaf()
    }).not.toThrow()
  })

  it('pins the last reasoning body to its bottom while streaming', async () => {
    const { api, streaming, streamReasoning } = mountScroll()
    const bodies = [
      { scrollTop: 0, scrollHeight: 100 },
      { scrollTop: 0, scrollHeight: 300 },
    ]
    api.messagesEl.value = fakeViewport(bodies)

    streaming.value = true
    streamReasoning.value = 'thinking...'
    await nextTick()
    flushRaf()

    // Only the last body is pinned; earlier ones keep the user's position.
    expect(bodies[0]!.scrollTop).toBe(0)
    expect(bodies[1]!.scrollTop).toBe(300)
  })

  it('does not pin reasoning when not streaming', async () => {
    const { api, streaming, streamReasoning } = mountScroll()
    const bodies = [{ scrollTop: 0, scrollHeight: 300 }]
    api.messagesEl.value = fakeViewport(bodies)

    streaming.value = false
    streamReasoning.value = 'thinking...'
    await nextTick()
    flushRaf()

    expect(bodies[0]!.scrollTop).toBe(0)
  })

  it('follows rows appended for a reader at the bottom, and leaves one reading above them alone', async () => {
    const resize = stubResizeObserver()
    const { api } = mountScroll()
    const { el, content, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()

    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    api.appendFollowing(() => layout({ scrollTop: 800, scrollHeight: 1300, clientHeight: 200 }))
    // The browser reports the content's growth before the frame runs; growth alone must not scroll.
    resize.fire([content])
    flushRaf()
    expect(el.scrollTop).toBe(1300)

    layout({ scrollTop: 400, scrollHeight: 1300, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    api.appendFollowing(() => layout({ scrollTop: 400, scrollHeight: 1600, clientHeight: 200 }))
    resize.fire([content])
    flushRaf()
    expect(el.scrollTop).toBe(400)
  })

  it('follows a turn\'s frames for a reader at the bottom as its content grows, and leaves one scrolled up where they are', async () => {
    const resize = stubResizeObserver()
    const { api } = mountScroll()
    const { el, content, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()

    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    // A token grows the bubble before its frame scrolls, which the content observer sees first.
    layout({ scrollTop: 800, scrollHeight: 1100, clientHeight: 200 })
    resize.fire([content])
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(1100)

    // The reader scrolls up to read; the next tokens leave them there.
    layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 300, scrollHeight: 1200, clientHeight: 200 })
    resize.fire([content])
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(300)

    // Back at the bottom, it follows again.
    layout({ scrollTop: 1000, scrollHeight: 1200, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 1000, scrollHeight: 1300, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(1300)
  })

  it('keeps following when the scroll event its own scroll caused arrives after the content grew', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })

    // Opening a conversation pins it; a running turn's replay renders before the write's scroll event fires.
    api.scrollToBottom()
    flushRaf()
    layout({ scrollTop: el.scrollTop, scrollHeight: 1400, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(1400)
  })

  it('knows its own scroll by the position the browser clamped the write to', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const el = document.createElement('div')
    el.appendChild(document.createElement('div'))
    const box = { top: 800, height: 1000, client: 200 }
    // As a browser does: scrollTop cannot pass the last position the viewport can reach.
    Object.defineProperty(el, 'scrollTop', {
      configurable: true,
      get: () => box.top,
      set: (v: number) => { box.top = Math.max(0, Math.min(v, box.height - box.client)) },
    })
    Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => box.height })
    Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => box.client })
    api.messagesEl.value = el
    await nextTick()

    box.top = 700
    api.scrollToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(800)
    // The content grows before the write's own event fires, at the clamped 800.
    box.height = 1400
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(1200)
  })

  it('takes a scroll that overtakes its own write for the reader\'s, and the event of a write that lands at the bottom for following', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })

    // The page writes the bottom, but the reader scrolls up before its event fires: one event, at the reader's place.
    api.scrollToBottom()
    flushRaf()
    layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(300)

    // An explicit pin then puts them at the bottom; a scroll in the frame before its write does not undo it.
    api.scrollToBottom()
    layout({ scrollTop: 250, scrollHeight: 1100, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    flushRaf()
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: el.scrollTop, scrollHeight: 1300, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(1300)
  })

  it('keeps following when older rows load above a reader at the bottom', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))

    await api.keepViewport(() => layout({ scrollTop: 800, scrollHeight: 1500, clientHeight: 200 }))
    expect(el.scrollTop).toBe(1300)
    // A turn's replay grows the list before the keep's own scroll event fires.
    layout({ scrollTop: 1300, scrollHeight: 1700, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(1700)
  })

  it('follows again once a conversation is opened, though the reader had scrolled up in the one before', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))

    // The next conversation opens in the same list, which is pinned to its bottom.
    layout({ scrollTop: 300, scrollHeight: 600, clientHeight: 200 })
    api.scrollToBottom()
    flushRaf()
    layout({ scrollTop: el.scrollTop, scrollHeight: 900, clientHeight: 200 })
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(900)
  })

  it('follows content that grows while a turn streams for a reader at the bottom, but not for one scrolled up', async () => {
    const resize = stubResizeObserver()
    const { api, streaming } = mountScroll()
    const { el, content, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    streaming.value = true
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))

    // A late render grows the turn's bubble with no frame after it.
    layout({ scrollTop: 800, scrollHeight: 1300, clientHeight: 200 })
    resize.fire([content])
    expect(el.scrollTop).toBe(1300)

    layout({ scrollTop: 400, scrollHeight: 1300, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 400, scrollHeight: 1600, clientHeight: 200 })
    resize.fire([content])
    expect(el.scrollTop).toBe(400)
  })

  it('leaves a reader who scrolls up between a token and its frame where they went', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))

    api.followToBottom()
    layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    flushRaf()

    expect(el.scrollTop).toBe(300)
  })

  it('follows a new message list\'s first turn though the reader had scrolled up in the list before it', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const first = viewportWithContent()
    api.messagesEl.value = first.el
    await nextTick()
    first.layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
    first.el.dispatchEvent(new Event('scroll'))

    // New Chat unmounts the list; the first turn of the next chat mounts a fresh one.
    const next = viewportWithContent()
    api.messagesEl.value = next.el
    await nextTick()
    next.layout({ scrollTop: 0, scrollHeight: 400, clientHeight: 200 })
    api.followToBottom()
    flushRaf()

    expect(next.el.scrollTop).toBe(400)
  })

  it('keeps a reader at the bottom when the viewport shrinks, and leaves one reading above it alone', async () => {
    const resize = stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()

    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    // An expanded subagent chip above takes 80 px; the browser keeps scrollTop.
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 120 })
    resize.fire([el])
    expect(el.scrollTop).toBe(1000)

    layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 120 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 60 })
    resize.fire([el])
    expect(el.scrollTop).toBe(300)
  })

  it('does not snap a reader whose content grew in place when the viewport then shrinks', async () => {
    const resize = stubResizeObserver()
    const { api } = mountScroll()
    const { el, content, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    expect(resize.observed).toEqual([el, content])

    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    // A Thinking card opened on the last message: 400 px more below the fold, and no scroll event.
    layout({ scrollTop: 800, scrollHeight: 1400, clientHeight: 200 })
    resize.fire([content])
    layout({ scrollTop: 800, scrollHeight: 1400, clientHeight: 120 })
    resize.fire([el])
    expect(el.scrollTop).toBe(800)
  })

  it('stays pinned through streamed growth that scrollToBottom follows', async () => {
    const resize = stubResizeObserver()
    const { api } = mountScroll()
    const { el, content, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()

    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 800, scrollHeight: 1400, clientHeight: 200 })
    api.scrollToBottom()
    flushRaf()
    // The frame's scroll lands before its resize observations; the browser clamps it to the new bottom.
    layout({ scrollTop: 1200, scrollHeight: 1400, clientHeight: 200 })
    resize.fire([content])
    layout({ scrollTop: 1200, scrollHeight: 1400, clientHeight: 120 })
    resize.fire([el])
    expect(el.scrollTop).toBe(1400)
  })

  it('cancels the pending frame on unmount', () => {
    const cancel = globalThis.cancelAnimationFrame as unknown as ReturnType<typeof vi.fn>
    const { wrapper, api } = mountScroll()
    api.messagesEl.value = { scrollTop: 0, scrollHeight: 100 } as HTMLElement
    api.scrollToBottom() // schedules scrollRaf

    wrapper.unmount()
    expect(cancel).toHaveBeenCalled()
  })
})
