import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { setResponseStatus, type H3Event } from 'h3'
import { defineComponent, h, ref } from 'vue'
import { useBulkSelect } from '~/composables/useBulkSelect'
import { useConfirm } from '~/composables/useConfirm'

type Api = ReturnType<typeof useBulkSelect<{ id: number }>>

/** Rows 1..3; the ids `refuse` names answer their DELETE with a 502 envelope naming the path. */
async function harness(refuse: (id: number) => boolean, onComplete: () => void) {
  for (const id of [1, 2, 3]) {
    registerEndpoint(`/api/tasks/${id}`, (event: H3Event) => {
      if (!refuse(id)) return { deleted: true }
      setResponseStatus(event, 502)
      return { code: 'upstream_unavailable', message: `Bad Gateway for /api/tasks/${id}`, template: null }
    })
  }
  let api!: Api
  await mountSuspended(defineComponent({
    setup() {
      api = useBulkSelect({
        rows: ref([{ id: 1 }, { id: 2 }, { id: 3 }]),
        deleteUrl: id => `/api/tasks/${id}`,
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

afterEach(() => {
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
})

describe('useBulkSelect — a sweep that fails part-way (JCLAW-1221)', () => {
  it('drops the rows it deleted, refreshes, and keeps the rest selected with the reason', async () => {
    const onComplete = vi.fn()
    const api = await harness(id => id === 2, onComplete)

    await deleteConfirmed(api)

    expect(onComplete).toHaveBeenCalledOnce()
    expect([...api.selectedIds.value]).toEqual([2, 3])
    expect(api.selectMode.value).toBe(true)
    expect(api.bulkError.value?.message).toContain('/api/tasks/2')
    expect(api.bulkError.value?.code).toBe('upstream_unavailable')
  })

  it('refreshes nothing when the first delete fails', async () => {
    const onComplete = vi.fn()
    const api = await harness(() => true, onComplete)

    await deleteConfirmed(api)

    expect(onComplete).not.toHaveBeenCalled()
    expect([...api.selectedIds.value]).toEqual([1, 2, 3])
    expect(api.bulkError.value?.message).toContain('/api/tasks/1')
  })

  it('leaves select mode and refreshes when every delete succeeds', async () => {
    const onComplete = vi.fn()
    const api = await harness(() => false, onComplete)

    await deleteConfirmed(api)

    expect(onComplete).toHaveBeenCalledOnce()
    expect(api.selectMode.value).toBe(false)
    expect(api.bulkError.value).toBeNull()
  })
})
