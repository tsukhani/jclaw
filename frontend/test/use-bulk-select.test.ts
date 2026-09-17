import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h, ref } from 'vue'
import { useBulkSelect } from '~/composables/useBulkSelect'
import { useConfirm } from '~/composables/useConfirm'

type Api = ReturnType<typeof useBulkSelect<{ id: number }>>

async function harness(deleteOne: (id: number) => Promise<unknown>, onComplete: () => void) {
  let api!: Api
  await mountSuspended(defineComponent({
    setup() {
      api = useBulkSelect({
        rows: ref([{ id: 1 }, { id: 2 }, { id: 3 }]),
        deleteOne,
        onComplete,
        confirmCopy: count => ({ title: 'Delete', message: `Delete ${count}?` }),
      })
      return () => h('div')
    },
  }))
  api.enter()
  api.toggleAll()
  return api
}

async function deleteConfirmed(api: Api) {
  const pending = api.deleteSelected()
  await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
  useConfirm()._resolve(true)
  await pending
}

function refusal(id: number) {
  return Object.assign(new Error(`[DELETE] "/api/tasks/${id}": 502 Bad Gateway`), { statusCode: 502 })
}

afterEach(() => {
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
})

describe('useBulkSelect — a sweep that fails part-way (JCLAW-1221)', () => {
  it('drops the rows it deleted, refreshes, and keeps the rest selected with the reason', async () => {
    const onComplete = vi.fn()
    const api = await harness(async (id) => {
      if (id === 2) throw refusal(2)
    }, onComplete)

    await deleteConfirmed(api)

    expect(onComplete).toHaveBeenCalledOnce()
    expect([...api.selectedIds.value]).toEqual([2, 3])
    expect(api.selectMode.value).toBe(true)
    expect(api.bulkError.value?.message).toContain('/api/tasks/2')
  })

  it('refreshes nothing when the first delete fails', async () => {
    const onComplete = vi.fn()
    const api = await harness(async (id) => {
      throw refusal(id)
    }, onComplete)

    await deleteConfirmed(api)

    expect(onComplete).not.toHaveBeenCalled()
    expect([...api.selectedIds.value]).toEqual([1, 2, 3])
    expect(api.bulkError.value?.message).toContain('/api/tasks/1')
  })

  it('leaves select mode and refreshes when every delete succeeds', async () => {
    const onComplete = vi.fn()
    const api = await harness(async () => {}, onComplete)

    await deleteConfirmed(api)

    expect(onComplete).toHaveBeenCalledOnce()
    expect(api.selectMode.value).toBe(false)
    expect(api.bulkError.value).toBeNull()
  })
})
