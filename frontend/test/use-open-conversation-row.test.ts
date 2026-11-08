import { describe, it, expect, afterEach, vi } from 'vitest'
import { computed, defineComponent, h, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import type { Conversation } from '~/types/api'
import { useOpenConversationRow } from '~/composables/useOpenConversationRow'

const unregister: Array<() => void> = []
afterEach(() => {
  while (unregister.length) unregister.pop()!()
})

function row(id: number, extra: Partial<Conversation> = {}): Conversation {
  return { id, messageRowCount: 7, userPromptCount: 2, compactionCount: 1, ...extra } as Conversation
}

function setup(opts: { id: number | null, listed: Conversation | null, streaming?: boolean }) {
  const selected = ref<number | null>(opts.id)
  const listed = ref<Conversation | null>(opts.listed)
  const streaming = ref(opts.streaming ?? false)
  let open!: ReturnType<typeof useOpenConversationRow>
  mount(defineComponent({
    setup() {
      open = useOpenConversationRow(selected, computed(() => listed.value), streaming)
      return () => h('div')
    },
  }))
  return { selected, listed, streaming, open: () => open.value }
}

function serve(id: number, body: Conversation, hits: number[]) {
  unregister.push(registerEndpoint(`/api/conversations/${id}`, () => {
    hits.push(id)
    return body
  }))
}

describe('useOpenConversationRow', () => {
  it('uses the sidebar row and fetches nothing when the list holds the conversation', async () => {
    const hits: number[] = []
    serve(5, row(5, { messageRowCount: 99 }), hits)
    const { open } = setup({ id: 5, listed: row(5) })
    await flushPromises()
    expect(open()?.messageRowCount).toBe(7)
    expect(hits).toEqual([])
  })

  it('fetches the one row when the sidebar list lacks the conversation', async () => {
    const hits: number[] = []
    serve(6, row(6, { messageRowCount: 386, userPromptCount: 15, compactionCount: 0 }), hits)
    const { open } = setup({ id: 6, listed: null })
    expect(open()).toBeNull()
    await vi.waitFor(() => expect(open()?.messageRowCount).toBe(386))
    expect(open()?.userPromptCount).toBe(15)
    expect(hits).toEqual([6])
  })

  it('shows no row for a conversation that is not selected any more', async () => {
    const hits: number[] = []
    serve(8, row(8), hits)
    serve(9, row(9, { messageRowCount: 4 }), hits)
    const { open, selected } = setup({ id: 8, listed: null })
    await vi.waitFor(() => expect(open()?.id).toBe(8))
    selected.value = 9
    expect(open()).toBeNull()
    await vi.waitFor(() => expect(open()?.messageRowCount).toBe(4))
  })

  it('reads again when a turn ends, so the counts follow the new rows', async () => {
    const hits: number[] = []
    serve(10, row(10), hits)
    const { streaming } = setup({ id: 10, listed: null, streaming: true })
    await flushPromises()
    expect(hits).toEqual([])
    streaming.value = false
    await vi.waitFor(() => expect(hits).toEqual([10]))
  })

  it('stays empty when nothing is selected', async () => {
    const { open } = setup({ id: null, listed: null })
    await flushPromises()
    expect(open()).toBeNull()
  })
})
