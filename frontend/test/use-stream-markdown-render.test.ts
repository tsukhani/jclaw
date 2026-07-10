import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h, ref, shallowRef } from 'vue'
import { mount } from '@vue/test-utils'
import type { Message } from '~/types/api'
import { useStreamMarkdownRender, type UseStreamMarkdownRender } from '~/composables/useStreamMarkdownRender'

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

function mountRender() {
  const streamContent = ref('')
  const streamReasoning = ref('')
  const selectedAgentId = ref<number | null>(1)
  const messages = shallowRef<Message[]>([])
  let api!: UseStreamMarkdownRender
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useStreamMarkdownRender(streamContent, streamReasoning, selectedAgentId, messages)
        return () => h('div')
      },
    }),
  )
  return { wrapper, streamContent, streamReasoning, selectedAgentId, messages, api }
}

describe('useStreamMarkdownRender', () => {
  it('renders content markdown to HTML after the throttle interval', () => {
    const { api, streamContent } = mountRender()
    streamContent.value = 'hello world'
    expect(api.streamContentHtml.value).toBe('') // nothing until the frame fires
    api.scheduleStreamContentRender()
    vi.advanceTimersByTime(80)
    expect(api.streamContentHtml.value).toContain('hello world')
  })

  it('renders reasoning markdown independently of content', () => {
    const { api, streamReasoning } = mountRender()
    streamReasoning.value = 'thinking hard'
    api.scheduleStreamReasoningRender()
    vi.advanceTimersByTime(80)
    expect(api.streamReasoningHtml.value).toContain('thinking hard')
    expect(api.streamContentHtml.value).toBe('')
  })

  it('coalesces overlapping content renders into a single timer and uses the latest buffer', () => {
    const { api, streamContent } = mountRender()
    streamContent.value = 'first'
    api.scheduleStreamContentRender()
    vi.advanceTimersByTime(40) // still within the throttle window
    streamContent.value = 'first and second'
    api.scheduleStreamContentRender() // guard: timer pending → no new frame
    expect(vi.getTimerCount()).toBe(1)
    vi.advanceTimersByTime(40) // now the single frame fires (total 80ms)
    expect(api.streamContentHtml.value).toContain('first and second')
  })

  it('flushStreamRender renders both buffers immediately and clears pending timers', () => {
    const { api, streamContent, streamReasoning } = mountRender()
    streamContent.value = 'final content'
    streamReasoning.value = 'final reasoning'
    api.scheduleStreamContentRender() // pending timer that flush should cancel
    api.flushStreamRender()
    // Rendered synchronously, no timer advance needed.
    expect(api.streamContentHtml.value).toContain('final content')
    expect(api.streamReasoningHtml.value).toContain('final reasoning')
    // The pending throttle timer was cleared by the flush.
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not throw after unmount when a timer was pending', () => {
    const { wrapper, api, streamContent } = mountRender()
    streamContent.value = 'x'
    api.scheduleStreamContentRender()
    expect(() => {
      wrapper.unmount() // onUnmounted clears the pending timer
      vi.advanceTimersByTime(80)
    }).not.toThrow()
  })
})
