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

// As a browser does: scrollTop cannot pass the last position the viewport can reach.
function clampingViewport(box: { top: number, height: number, client: number }) {
  const el = document.createElement('div')
  el.appendChild(document.createElement('div'))
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => box.top,
    set: (v: number) => { box.top = Math.max(0, Math.min(v, box.height - box.client)) },
  })
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => box.height })
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => box.client })
  return el
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

    el.dispatchEvent(new Event('wheel'))
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
    el.dispatchEvent(new Event('wheel'))
    layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 300, scrollHeight: 1200, clientHeight: 200 })
    resize.fire([content])
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(300)

    // Back at the bottom, it follows again.
    el.dispatchEvent(new Event('wheel'))
    layout({ scrollTop: 1000, scrollHeight: 1200, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 1000, scrollHeight: 1300, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(1300)
  })

  it('keeps following when a scroll event no reader input caused lands short of a bottom that grew', async () => {
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

  it('keeps following through a scroll event no reader input caused, as a conversation\'s rows are swapped in', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const el = document.createElement('div')
    el.appendChild(document.createElement('div'))
    const box = { top: 4961, height: 5474, client: 513 }
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

    // The conversation opens already at the bottom, so its pin's write moves nothing (measured live, JF-229).
    api.scrollToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(4961)
    // The browser then fires a scroll event of its own as the rows settle, 29 px short of the grown bottom.
    box.height = 5503
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(4990)
  })

  it('takes a drag on the scrollbar or a scroll key for the reader, and a click on a card or a key typed in a field for neither', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    const content = el.firstElementChild as HTMLElement
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    // A click on a card in the list, then a layout shift that moves the view up 10 px as the rows grow past it: the
    // reader's own move up would leave the bottom, so following again says the click was not theirs.
    content.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    window.dispatchEvent(new Event('pointerup'))
    box.top = 790
    box.height = 1100
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(900)

    // A key typed in a field inside the list is not the reader either.
    const field = content.appendChild(document.createElement('textarea'))
    field.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true }))
    box.top = 890
    box.height = 1200
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(1000)

    // The reader drags the list's scrollbar up: it stays where they put it.
    el.dispatchEvent(new Event('pointerdown'))
    box.top = 300
    el.dispatchEvent(new Event('scroll'))
    window.dispatchEvent(new Event('pointerup'))
    box.height = 1300
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(300)
  })

  it('takes PageUp pressed on the list for the reader scrolling up', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp' }))
    layout({ scrollTop: 600, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 600, scrollHeight: 1100, clientHeight: 200 })
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(600)
  })

  it('takes the reader\'s scroll after their wheel for theirs, and a pin that puts them back at the bottom for following', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })

    // The page writes the bottom, but the reader scrolls up before its event fires: one event, at the reader's place.
    api.scrollToBottom()
    flushRaf()
    el.dispatchEvent(new Event('wheel'))
    layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(300)

    // An explicit pin then puts them at the bottom; a scroll in the frame before its write does not undo it.
    api.scrollToBottom()
    el.dispatchEvent(new Event('wheel'))
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

  it('tells its own write from the reader\'s scroll a moment after their wheel, though the content grew before its event', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    // The reader wheels back down to the bottom to follow the turn again.
    el.dispatchEvent(new Event('wheel'))
    el.dispatchEvent(new Event('scroll'))

    // A frame follows to 900, then the content grows 200 px before that write's own event fires there.
    box.height = 1100
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(900)
    box.height = 1300
    el.dispatchEvent(new Event('scroll'))
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(1100)
  })

  it('takes a scroll for the reader\'s up to 600 ms after their input, and not after', async () => {
    stubResizeObserver()
    const now = vi.spyOn(performance, 'now')
    try {
      for (const [gap, followed] of [[600, false], [601, true]] as const) {
        const { api } = mountScroll()
        const { el, layout } = viewportWithContent()
        api.messagesEl.value = el
        await nextTick()
        layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
        now.mockReturnValue(10_000)
        el.dispatchEvent(new Event('wheel'))
        now.mockReturnValue(10_000 + gap)
        layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
        el.dispatchEvent(new Event('scroll'))
        layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
        api.followToBottom()
        flushRaf()
        expect(el.scrollTop, `a scroll ${gap} ms after the wheel`).toBe(followed ? 1100 : 300)
      }
    }
    finally {
      now.mockRestore()
    }
  })

  it('takes a scrollbar drag held past the moment for the reader, until a release or the window losing focus ends it', async () => {
    stubResizeObserver()
    const now = vi.spyOn(performance, 'now')
    try {
      const { api } = mountScroll()
      const { el, layout } = viewportWithContent()
      api.messagesEl.value = el
      await nextTick()
      layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
      now.mockReturnValue(10_000)
      el.dispatchEvent(new Event('pointerdown'))
      now.mockReturnValue(15_000)
      layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
      el.dispatchEvent(new Event('scroll'))
      layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
      api.followToBottom()
      flushRaf()
      expect(el.scrollTop).toBe(300)

      // The window losing focus ends the drag: a later layout scroll, short of a grown bottom, is not the reader's.
      window.dispatchEvent(new Event('blur'))
      now.mockReturnValue(20_000)
      layout({ scrollTop: 1000, scrollHeight: 1200, clientHeight: 200 })
      el.dispatchEvent(new Event('scroll'))
      layout({ scrollTop: 1000, scrollHeight: 1300, clientHeight: 200 })
      el.dispatchEvent(new Event('scroll'))
      api.followToBottom()
      flushRaf()
      expect(el.scrollTop).toBe(1300)
    }
    finally {
      now.mockRestore()
    }
  })

  it('counts focus moving in the list, a moving touch and a selection dragged on a row as the reader', async () => {
    stubResizeObserver()
    for (const act of ['focusin', 'touchmove', 'select'] as const) {
      const { api } = mountScroll()
      const { el, content, layout } = viewportWithContent()
      api.messagesEl.value = el
      await nextTick()
      layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
      if (act === 'focusin') content.dispatchEvent(new Event('focusin', { bubbles: true }))
      if (act === 'touchmove') el.dispatchEvent(new Event('touchmove'))
      if (act === 'select') {
        content.dispatchEvent(new Event('pointerdown', { bubbles: true }))
        content.dispatchEvent(new Event('pointermove', { bubbles: true }))
      }
      layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
      el.dispatchEvent(new Event('scroll'))
      window.dispatchEvent(new Event('pointerup'))
      layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
      api.followToBottom()
      flushRaf()
      expect(el.scrollTop, act).toBe(300)
    }
  })

  it('does not count a tap, a sideways wheel, a right-click, Space on a button or a wheel on a list it no longer shows', async () => {
    stubResizeObserver()
    for (const act of ['tap', 'sideways', 'rightClick', 'spaceOnButton', 'oldList'] as const) {
      const { api } = mountScroll()
      const { el, content, layout } = viewportWithContent()
      api.messagesEl.value = el
      await nextTick()
      let list = el
      let size = layout
      if (act === 'tap') content.dispatchEvent(new Event('touchstart', { bubbles: true }))
      if (act === 'sideways') el.dispatchEvent(new WheelEvent('wheel', { deltaX: 40, deltaY: 0 }))
      if (act === 'rightClick') el.dispatchEvent(new MouseEvent('pointerdown', { button: 2 }))
      if (act === 'spaceOnButton') {
        const button = content.appendChild(document.createElement('button'))
        button.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }))
      }
      if (act === 'oldList') {
        const next = viewportWithContent()
        api.messagesEl.value = next.el
        await nextTick()
        el.dispatchEvent(new Event('wheel'))
        list = next.el
        size = next.layout
      }
      size({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
      list.dispatchEvent(new Event('scroll'))
      size({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
      api.followToBottom()
      flushRaf()
      expect(list.scrollTop, act).toBe(1100)
    }
  })

  it('takes a move up it did not make for the reader\'s with no input it saw, but not a shrink that keeps them at the bottom', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    layout({ scrollTop: 800, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))

    // The rows shrink, and the browser clamps the view up with them: still at the bottom, still following.
    layout({ scrollTop: 700, scrollHeight: 900, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 700, scrollHeight: 1000, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(1000)

    // A wheel over an embedded frame scrolls the list up with no input on it: the reader stays where they went.
    layout({ scrollTop: 300, scrollHeight: 1000, clientHeight: 200 })
    el.dispatchEvent(new Event('scroll'))
    layout({ scrollTop: 300, scrollHeight: 1100, clientHeight: 200 })
    api.followToBottom()
    flushRaf()
    expect(el.scrollTop).toBe(300)
  })

  it('does not count focus a click moved, a wheel down at the bottom, or input made before a pin', async () => {
    stubResizeObserver()
    for (const act of ['clickFocus', 'wheelDownAtBottom', 'beforePin'] as const) {
      const { api } = mountScroll()
      const box = { top: 800, height: 1000, client: 200 }
      const el = clampingViewport(box)
      const content = el.firstElementChild as HTMLElement
      api.messagesEl.value = el
      await nextTick()
      el.dispatchEvent(new Event('scroll'))
      if (act === 'clickFocus') {
        content.dispatchEvent(new Event('pointerdown', { bubbles: true }))
        content.dispatchEvent(new Event('focusin', { bubbles: true }))
        window.dispatchEvent(new Event('pointerup'))
      }
      if (act === 'wheelDownAtBottom') el.dispatchEvent(new WheelEvent('wheel', { deltaY: 40 }))
      if (act === 'beforePin') {
        el.dispatchEvent(new Event('wheel'))
        api.scrollToBottom()
        flushRaf()
      }
      // A layout shift moves the view up 10 px as the rows grow past it: a move the reader made would leave the bottom.
      box.top = 790
      box.height = 1100
      el.dispatchEvent(new Event('scroll'))
      api.followToBottom()
      flushRaf()
      expect(el.scrollTop, act).toBe(900)
    }
  })

  it('follows a shorter conversation opened in the same list, though the browser clamps the view up as its rows swap in', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 8000, height: 8200, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    // The page opens a shorter conversation; the browser clamps the view, and more rows render before that event.
    api.scrollToBottom()
    box.height = 2300
    box.top = 2000
    el.dispatchEvent(new Event('scroll'))
    flushRaf()
    expect(el.scrollTop).toBe(2100)
    // Its running turn grows the list.
    box.height = 2500
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(2300)
  })

  it('takes a slow move up it did not make for the reader\'s once it adds up past the slack', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    // A trackpad over an embedded frame scrolls the list up 10 px an event, with no input on the list itself.
    for (const top of [790, 780, 770, 760, 750, 740]) {
      box.top = top
      el.dispatchEvent(new Event('scroll'))
    }
    box.height = 1100
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(740)
  })

  it('leaves a reader who wheels up a few pixels where they are while a turn streams, though that is within the slack', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    box.height = 1050
    api.followToBottom()
    flushRaf()
    el.dispatchEvent(new Event('scroll'))
    expect(el.scrollTop).toBe(850)

    el.dispatchEvent(new WheelEvent('wheel', { deltaY: -10 }))
    box.top = 840
    el.dispatchEvent(new Event('scroll'))
    box.height = 1100
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(840)
  })

  it('stops following on any scroll up the reader makes, a few pixels included', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    el.dispatchEvent(new WheelEvent('wheel', { deltaY: -5 }))
    box.top = 795
    el.dispatchEvent(new Event('scroll'))
    box.height = 1100
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(795)
  })

  it('takes a slow scroll up for the reader\'s from its first step, though each frame follows in between', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    // 3 px an event, and the turn's next frame puts the view back at the bottom after each one.
    for (let step = 0; step < 3; step++) {
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: -3 }))
      box.top -= 3
      el.dispatchEvent(new Event('scroll'))
      box.height += 20
      api.followToBottom()
      flushRaf()
      el.dispatchEvent(new Event('scroll'))
    }
    const left = el.scrollTop
    box.height += 100
    api.followToBottom()
    flushRaf()

    expect(left).toBe(791)
    expect(el.scrollTop).toBe(791)
  })

  it('leaves a reader who scrolled up within the slack where they are when a card grows the rows and a merge appends', async () => {
    const probe = stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    const content = el.firstElementChild as HTMLElement
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    el.dispatchEvent(new WheelEvent('wheel', { deltaY: -12 }))
    box.top = 788
    el.dispatchEvent(new Event('scroll'))

    // A card opens in place, then the turn's saved rows are appended.
    box.height = 1010
    probe.fire([content])
    api.appendFollowing(() => {
      box.height = 1100
    })
    flushRaf()

    expect(el.scrollTop).toBe(788)
  })

  it('keeps a reader who scrolled up within the slack above when, after a pause, they nudge further up', async () => {
    stubResizeObserver()
    const now = vi.spyOn(performance, 'now')
    try {
      const { api } = mountScroll()
      const box = { top: 800, height: 1000, client: 200 }
      const el = clampingViewport(box)
      api.messagesEl.value = el
      await nextTick()
      el.dispatchEvent(new Event('scroll'))
      for (const [at, delta, top] of [[10_000, -12, 788], [11_000, -5, 783]] as const) {
        now.mockReturnValue(at)
        el.dispatchEvent(new WheelEvent('wheel', { deltaY: delta }))
        box.top = top
        el.dispatchEvent(new Event('scroll'))
      }
      box.height = 1100
      api.followToBottom()
      flushRaf()
      expect(el.scrollTop).toBe(783)
      api.appendFollowing(() => {
        box.height = 1200
      })
      flushRaf()
      expect(el.scrollTop).toBe(783)
    }
    finally {
      now.mockRestore()
    }
  })

  it('leaves a reader the rows grew away from outside a turn where they are, whatever scroll comes next', async () => {
    for (const act of ['readerScrollsDown', 'readerBrushesUp', 'layoutScroll'] as const) {
      const probe = stubResizeObserver()
      const { api } = mountScroll()
      const box = { top: 800, height: 1000, client: 200 }
      const el = clampingViewport(box)
      const content = el.firstElementChild as HTMLElement
      api.messagesEl.value = el
      await nextTick()
      el.dispatchEvent(new Event('scroll'))
      // A card opens in place, 400 px tall, with no turn running.
      box.height = 1400
      probe.fire([content])

      if (act === 'readerScrollsDown') {
        el.dispatchEvent(new WheelEvent('wheel', { deltaY: 40 }))
        box.top = 840
      }
      if (act === 'readerBrushesUp') {
        el.dispatchEvent(new WheelEvent('wheel', { deltaY: -5 }))
        box.top = 795
      }
      el.dispatchEvent(new Event('scroll'))
      const left = el.scrollTop
      api.appendFollowing(() => {
        box.height = 1500
      })
      flushRaf()

      expect(el.scrollTop, act).toBe(left)
    }
  })

  it('keeps a reader following through a layout scroll short of the growing bottom while a turn streams, so rows appended next follow', async () => {
    stubResizeObserver()
    const { api, streaming } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    streaming.value = true
    box.height = 1100
    el.dispatchEvent(new Event('scroll'))
    api.appendFollowing(() => {
      box.height = 1150
    })
    flushRaf()

    expect(el.scrollTop).toBe(950)
  })

  it('follows again once a reader who scrolled up comes back into the slack 1 px at a time, short of the very bottom', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    el.dispatchEvent(new WheelEvent('wheel', { deltaY: -30 }))
    box.top = 770
    el.dispatchEvent(new Event('scroll'))

    for (let top = 771; top <= 790; top++) {
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: 1 }))
      box.top = top
      el.dispatchEvent(new Event('scroll'))
    }
    box.height = 1100
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(900)
  })

  it('takes a scroll up of 1 px during a turn for the reader\'s, though it lands next to the follow\'s own write', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    // Each frame follows to the bottom, then the reader's 1 px arrives with that write's own event.
    for (let step = 0; step < 10; step++) {
      box.height += 10
      api.followToBottom()
      flushRaf()
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: -1 }))
      box.top -= 1
      el.dispatchEvent(new Event('scroll'))
    }
    const left = el.scrollTop
    box.height += 50
    api.followToBottom()
    flushRaf()

    expect(left).toBe(800)
    expect(el.scrollTop).toBe(800)
  })

  it('takes a page up for the reader\'s after rows below the view shrank without moving it', async () => {
    const probe = stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    const content = el.firstElementChild as HTMLElement
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    // A card opens in place and the browser keeps the reader's rows in view, moving the list down 100 px.
    box.height = 1400
    probe.fire([content])
    box.top = 900
    el.dispatchEvent(new Event('scroll'))
    // A card further down closes: the list's end comes up, short of the view, so nothing moves.
    box.height = 1250
    probe.fire([content])

    el.dispatchEvent(new KeyboardEvent('keydown', { key: 'PageUp' }))
    box.top = 600
    el.dispatchEvent(new Event('scroll'))
    box.height = 1350
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(600)
  })

  it('keeps following a reader who flicks to the bottom when the browser overshoots it and bounces back', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 700, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    el.dispatchEvent(new WheelEvent('wheel', { deltaY: 130 }))
    // An overscroll bounce reporting a position past the end, then the bounce back to it.
    box.top = 830
    el.dispatchEvent(new Event('scroll'))
    box.top = 800
    el.dispatchEvent(new Event('scroll'))
    box.height = 1100
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(900)
  })

  it('keeps a reader who scrolled up a few pixels above when a layout shift that is not theirs leaves them inside the slack', async () => {
    stubResizeObserver()
    const now = vi.spyOn(performance, 'now')
    try {
      const { api } = mountScroll()
      const box = { top: 800, height: 1000, client: 200 }
      const el = clampingViewport(box)
      api.messagesEl.value = el
      await nextTick()
      el.dispatchEvent(new Event('scroll'))
      now.mockReturnValue(10_000)
      el.dispatchEvent(new WheelEvent('wheel', { deltaY: -10 }))
      box.top = 790
      el.dispatchEvent(new Event('scroll'))

      // Rows above them grow 50 px, and the browser moves the view down with them.
      now.mockReturnValue(11_000)
      box.height = 1050
      box.top = 840
      el.dispatchEvent(new Event('scroll'))
      box.height = 1100
      api.followToBottom()
      flushRaf()

      expect(el.scrollTop).toBe(840)
    }
    finally {
      now.mockRestore()
    }
  })

  it('follows again once a reader who nudged up wheels back down after a pause, though they stop a few pixels short', async () => {
    stubResizeObserver()
    const now = vi.spyOn(performance, 'now')
    try {
      const { api } = mountScroll()
      const box = { top: 800, height: 1000, client: 200 }
      const el = clampingViewport(box)
      api.messagesEl.value = el
      await nextTick()
      el.dispatchEvent(new Event('scroll'))
      for (const [at, delta, top] of [[10_000, -10, 790], [11_000, 5, 795]] as const) {
        now.mockReturnValue(at)
        el.dispatchEvent(new WheelEvent('wheel', { deltaY: delta }))
        box.top = top
        el.dispatchEvent(new Event('scroll'))
      }
      box.height = 1100
      api.followToBottom()
      flushRaf()

      expect(el.scrollTop).toBe(900)
    }
    finally {
      now.mockRestore()
    }
  })

  it('exposes whether the reader has scrolled away: set by their scroll up, cleared by coming back, a pin or a new list', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    const scrollReader = (deltaY: number, top: number) => {
      el.dispatchEvent(new WheelEvent('wheel', { deltaY }))
      box.top = top
      el.dispatchEvent(new Event('scroll'))
    }
    expect(api.scrolledAway.value).toBe(false)

    scrollReader(-40, 760)
    expect(api.scrolledAway.value).toBe(true)
    scrollReader(40, 800)
    expect(api.scrolledAway.value).toBe(false)

    scrollReader(-40, 760)
    api.scrollToBottom()
    expect(api.scrolledAway.value).toBe(false)
    flushRaf()

    scrollReader(-40, 760)
    expect(api.scrolledAway.value).toBe(true)
    api.messagesEl.value = viewportWithContent().el
    await nextTick()
    expect(api.scrolledAway.value).toBe(false)
  })

  it('sets scrolledAway on a nudge inside the slack and on a move up no input caused, and clears it at the very bottom', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 800, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))

    el.dispatchEvent(new WheelEvent('wheel', { deltaY: -5 }))
    box.top = 795
    el.dispatchEvent(new Event('scroll'))
    expect(api.scrolledAway.value).toBe(true)
    api.scrollToBottom()
    flushRaf()
    el.dispatchEvent(new Event('scroll'))
    expect(api.scrolledAway.value).toBe(false)

    // A wheel over an embedded frame moves the list up with no input on it.
    box.top = 600
    el.dispatchEvent(new Event('scroll'))
    expect(api.scrolledAway.value).toBe(true)
    // The rows shrink to the view, and the browser clamps it to the very bottom.
    box.height = 800
    box.top = 600
    el.dispatchEvent(new Event('scroll'))
    expect(api.scrolledAway.value).toBe(false)
  })

  it('keeps following a reader back at the bottom when the rows shrink a moment after their wheel', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const box = { top: 700, height: 1000, client: 200 }
    const el = clampingViewport(box)
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('scroll'))
    el.dispatchEvent(new WheelEvent('wheel', { deltaY: 100 }))
    box.top = 800
    el.dispatchEvent(new Event('scroll'))

    // The saved rows replace the streamed ones 100 px shorter, and the browser clamps the view up with them.
    box.height = 900
    box.top = 700
    el.dispatchEvent(new Event('scroll'))
    box.height = 1000
    api.followToBottom()
    flushRaf()

    expect(el.scrollTop).toBe(800)
  })

  it('follows again once a conversation is opened, though the reader had scrolled up in the one before', async () => {
    stubResizeObserver()
    const { api } = mountScroll()
    const { el, layout } = viewportWithContent()
    api.messagesEl.value = el
    await nextTick()
    el.dispatchEvent(new Event('wheel'))
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

    el.dispatchEvent(new Event('wheel'))
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
    el.dispatchEvent(new Event('wheel'))
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
    first.el.dispatchEvent(new Event('wheel'))
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

    el.dispatchEvent(new Event('wheel'))
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
