import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref, shallowRef } from 'vue'
import type { Message } from '~/types/api'
import { useMediaGenPolling, type UseMediaGenPolling } from '~/composables/useMediaGenPolling'

// Register per-test (mirroring chat.flows.test.ts): a top-level registration is
// reset before these tests run. The video endpoint echoes a SUCCEEDED status
// for job 42 so the first poll both merges a status and (because SUCCEEDED is
// terminal) stops the loop — no interval leaks. Image progress reports 55%.
beforeEach(() => {
  registerEndpoint('/api/videogen/jobs', () => [
    { id: 42, state: 'SUCCEEDED', resultAttachmentUuid: 'r', resultSizeBytes: 100 },
  ])
  registerEndpoint('/api/imagegen/progress', () => ({ percent: 55 }))
})

function videoMsg(sizeBytes: number): Message {
  return {
    role: 'assistant',
    content: '',
    attachments: [
      { uuid: 'v1', kind: 'VIDEO', generated: true, generationJobId: 42, sizeBytes },
    ],
  } as unknown as Message
}

async function mountPolling(msgs: Message[] = [], streamingInit = false) {
  const messages = shallowRef<Message[]>(msgs)
  const streaming = ref(streamingInit)
  let api!: UseMediaGenPolling
  const wrapper = await mountSuspended(
    defineComponent({
      setup() {
        api = useMediaGenPolling(messages, streaming)
        return () => h('div')
      },
    }),
  )
  return { wrapper, messages, streaming, api }
}

describe('useMediaGenPolling', () => {
  it('polls pending video jobs and merges the returned status', async () => {
    const { wrapper, api } = await mountPolling([videoMsg(0)])
    api.startVideoPolling()
    // vi.waitFor polls until $fetch resolves through the Nitro mock — a single
    // flushPromises round isn't enough for that multi-microtask chain.
    await vi.waitFor(() => expect(api.videoJobStatus.value[42]).toMatchObject({ state: 'SUCCEEDED', resultAttachmentUuid: 'r' }))
    wrapper.unmount()
  })

  it('is a no-op for an already-filled placeholder (nothing pending)', async () => {
    const { wrapper, api } = await mountPolling([videoMsg(4096)]) // sizeBytes > 0 → ready
    api.startVideoPolling()
    await flushPromises()
    expect(api.videoJobStatus.value).toEqual({})
    wrapper.unmount()
  })

  it('polls image progress on start and reflects the reported percent', async () => {
    const { wrapper, api } = await mountPolling([], true)
    api.startImageProgressPolling()
    await vi.waitFor(() => expect(api.imageGenPercent.value).toBe(55))
    wrapper.unmount()
  })

  it('clears the image progress bar and turn key when streaming ends', async () => {
    const { wrapper, api, streaming } = await mountPolling([], true)
    api.startImageProgressPolling()
    api.imageGenTurnKey.value = 'turn-key'
    await vi.waitFor(() => expect(api.imageGenPercent.value).toBe(55))

    streaming.value = false
    await nextTick()
    expect(api.imageGenPercent.value).toBeNull()
    expect(api.imageGenTurnKey.value).toBeNull()
    wrapper.unmount()
  })
})
