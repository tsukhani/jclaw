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

  it('cancels the pending frame on unmount', () => {
    const cancel = globalThis.cancelAnimationFrame as unknown as ReturnType<typeof vi.fn>
    const { wrapper, api } = mountScroll()
    api.messagesEl.value = { scrollTop: 0, scrollHeight: 100 } as HTMLElement
    api.scrollToBottom() // schedules scrollRaf

    wrapper.unmount()
    expect(cancel).toHaveBeenCalled()
  })
})
