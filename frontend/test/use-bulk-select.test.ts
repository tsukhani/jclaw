import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { setResponseStatus, type H3Event } from 'h3'
import { defineComponent, h, ref } from 'vue'
import { useBulkSelect, type UseBulkSelectOptions } from '~/composables/useBulkSelect'
import { useConfirm } from '~/composables/useConfirm'

type Row = { id: number }
type Api = ReturnType<typeof useBulkSelect<Row>>

/** Every request the endpoints answered, and whether the composable reported itself busy at the time. */
const requests: { method: string, id: number, busy: boolean }[] = []

/** Rows 1..3; the ids `refuse` names answer their DELETE with a 502 envelope naming the path. */
async function mount(refuse: (id: number) => boolean, opts: Partial<UseBulkSelectOptions<Row>> = {}) {
  let api!: Api
  for (const id of [1, 2, 3]) {
    registerEndpoint(`/api/tasks/${id}`, (event: H3Event) => {
      requests.push({ method: event.method, id, busy: api.deletingBulk.value })
      if (!refuse(id)) return { deleted: true }
      setResponseStatus(event, 502)
      return { code: 'upstream_unavailable', message: `Bad Gateway for /api/tasks/${id}`, template: null }
    })
  }
  await mountSuspended(defineComponent({
    setup() {
      api = useBulkSelect({
        rows: ref([{ id: 1 }, { id: 2 }, { id: 3 }]),
        deleteUrl: id => `/api/tasks/${id}`,
        confirmCopy: count => ({ title: 'Delete', message: `Delete ${count}?` }),
        ...opts,
      })
      return () => h('div')
    },
  }))
  return api
}

/** {@link mount} in select mode with every row selected. */
async function harness(refuse: (id: number) => boolean, onComplete?: () => void, opts: Partial<UseBulkSelectOptions<Row>> = {}) {
  const api = await mount(refuse, { onComplete, ...opts })
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
  requests.length = 0
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

describe('useBulkSelect — selection', () => {
  it('starts outside select mode and not busy', async () => {
    const api = await mount(() => false)

    expect(api.selectMode.value).toBe(false)
    expect(api.deletingBulk.value).toBe(false)
  })

  it('toggles one row in and out of the selection', async () => {
    const api = await mount(() => false)
    api.enter()

    api.toggle(2)
    api.toggle(3)
    expect([...api.selectedIds.value]).toEqual([2, 3])
    api.toggle(2)
    expect([...api.selectedIds.value]).toEqual([3])
  })

  it('selects only the selectable rows, and a second select-all clears them', async () => {
    const api = await mount(() => false, { selectable: row => row.id !== 2 })
    api.enter()

    api.toggleAll()
    expect([...api.selectedIds.value]).toEqual([1, 3])
    api.toggleAll()
    expect(api.selectedIds.value.size).toBe(0)
  })

  it('selects nothing before the rows have loaded', async () => {
    const api = await mount(() => false, { rows: ref<Row[] | null>(null) })
    api.enter()

    api.toggleAll()
    expect(api.selectableRows.value).toEqual([])
    expect(api.selectedIds.value.size).toBe(0)
  })
})

describe('useBulkSelect — the delete sweep', () => {
  it('deletes nothing when the confirmation is cancelled', async () => {
    const onComplete = vi.fn()
    const api = await harness(() => false, onComplete)

    const pending = api.deleteSelected()
    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    useConfirm()._resolve(false)
    await pending

    expect(requests).toEqual([])
    expect(onComplete).not.toHaveBeenCalled()
    expect([...api.selectedIds.value]).toEqual([1, 2, 3])
    expect(api.selectMode.value).toBe(true)
  })

  it('opens no dialog when nothing is selected', async () => {
    const api = await mount(() => false)
    api.enter()

    const pending = api.deleteSelected()
    expect(useConfirm()._state.open).toBe(false)
    await pending
    expect(requests).toEqual([])
  })

  it('asks with a danger dialog built from the selection size, its button defaulting to Delete', async () => {
    const api = await harness(() => false)

    const pending = api.deleteSelected()
    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    const { title, message, confirmText, variant } = useConfirm()._state
    expect({ title, message, confirmText, variant }).toEqual({ title: 'Delete', message: 'Delete 3?', confirmText: 'Delete', variant: 'danger' })
    useConfirm()._resolve(false)
    await pending
  })

  it('uses the caller\'s button label when it gives one', async () => {
    const api = await harness(() => false, undefined, { confirmCopy: () => ({ title: 'Remove', message: 'Remove?', confirmText: 'Remove' }) })

    const pending = api.deleteSelected()
    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    expect(useConfirm()._state.confirmText).toBe('Remove')
    useConfirm()._resolve(false)
    await pending
  })

  it('sends one DELETE per selected row, in order, flagged busy until the sweep ends', async () => {
    const api = await harness(() => false)

    await deleteConfirmed(api)

    expect(requests).toEqual([1, 2, 3].map(id => ({ method: 'DELETE', id, busy: true })))
    expect(api.deletingBulk.value).toBe(false)
  })

  it('finishes a sweep for a caller that passes no onComplete', async () => {
    const api = await harness(() => false)

    await deleteConfirmed(api)

    expect(api.selectMode.value).toBe(false)
  })
})
