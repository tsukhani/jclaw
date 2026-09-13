import { describe, it, expect, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref, type Ref } from 'vue'
import { useChatRunningSubagents, type RunningSubagent } from '~/composables/useChatRunningSubagents'

function run(id: number, childConversationId: number | null) {
  return { id, childAgentName: `main-sub-${id}`, parentConversationId: 5, childConversationId, status: 'RUNNING' }
}

async function mountRunning() {
  const selectedConvoId = ref<number | null>(5)
  const streaming = ref(false)
  let runningSubagents!: Ref<RunningSubagent[]>
  await mountSuspended(
    defineComponent({
      setup() {
        ({ runningSubagents } = useChatRunningSubagents(selectedConvoId, streaming))
        return () => h('div')
      },
    }),
  )
  await flushPromises()
  return { runningSubagents, selectedConvoId, streaming }
}

describe('useChatRunningSubagents', () => {
  it('asks for this conversation\'s RUNNING runs and keeps only those with their own transcript', async () => {
    const urls: string[] = []
    registerEndpoint('/api/subagent-runs', (event) => {
      urls.push(String(event.node?.req?.url ?? event.path ?? ''))
      return [run(1, 6), run(2, 5), run(3, null)]
    })
    const { runningSubagents } = await mountRunning()

    expect(urls[0]).toContain('parentConversationId=5')
    expect(urls[0]).toContain('status=RUNNING')
    expect(runningSubagents.value).toEqual([{ id: 1, childAgentName: 'main-sub-1', childConversationId: 6 }])
  })

  it('refetches when a turn ends, dropping a run that stopped reporting RUNNING', async () => {
    let running = true
    registerEndpoint('/api/subagent-runs', () => (running ? [run(1, 6)] : []))
    const { runningSubagents, streaming } = await mountRunning()
    expect(runningSubagents.value).toHaveLength(1)

    running = false
    streaming.value = true
    await nextTick()
    streaming.value = false
    // The 5s poll can't fire inside waitFor's 1s budget, so only the turn-end refetch passes this.
    await vi.waitFor(() => expect(runningSubagents.value).toEqual([]))
  })

  it('clears the chips when the conversation closes', async () => {
    registerEndpoint('/api/subagent-runs', () => [run(1, 6)])
    const { runningSubagents, selectedConvoId } = await mountRunning()
    expect(runningSubagents.value).toHaveLength(1)

    selectedConvoId.value = null
    await flushPromises()
    expect(runningSubagents.value).toEqual([])
  })
})
