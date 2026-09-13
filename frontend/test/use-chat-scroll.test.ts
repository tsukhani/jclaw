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
