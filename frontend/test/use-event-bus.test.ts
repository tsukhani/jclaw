import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import { useState } from '#app'
import { useEventBus } from '~/composables/useEventBus'

class FakeEventSource {
  static latest: FakeEventSource | null = null
  onopen: (() => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: (() => void) | null = null

  constructor(readonly url: string) {
    FakeEventSource.latest = this
  }

  close() {}
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useEventBus', () => {
  it('runs an onOpen handler on each (re)connect until its component unmounts', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const opened = vi.fn()
    const wrapper = await mountSuspended(defineComponent({
      setup() {
        useState<boolean>('auth:authenticated').value = true
        useEventBus().onOpen(opened)
        return () => h('div')
      },
    }))
    const source = FakeEventSource.latest
    expect(source?.url).toBe('/api/events')

    source!.onopen!()
    source!.onopen!()
    expect(opened).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    source!.onopen!()
    expect(opened).toHaveBeenCalledTimes(2)
  })
})
