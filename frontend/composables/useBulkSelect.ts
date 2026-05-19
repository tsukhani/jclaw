/**
 * Bulk-select state machine shared across admin list pages (Tasks,
 * Subagent Runs, and any future page that wants the same trash-icon →
 * checkbox column → "Delete N" toolbar). Pulled out of the per-page
 * <script setup> to keep the JS-side boilerplate from accumulating as
 * we add more list pages.
 *
 * The composable owns three pieces of state — `selectMode`,
 * `selectedIds`, `deletingBulk` — and the handlers that mutate them
 * (`enter`, `exit`, `toggle`, `toggleAll`, `deleteSelected`). Each
 * caller supplies the rows source, an optional selectable-row
 * predicate (used by Subagent Runs to exclude RUNNING from "select
 * all" since the backend rejects delete on live rows), a per-id
 * delete function, and the confirm-dialog copy.
 *
 * The `confirm` dialog itself lives in {@link useConfirm}; this
 * composable composes the two so a caller only writes the one-liner
 * `useBulkSelect(...)` instead of recreating the wiring.
 */

/** Minimum row shape — bulk-select only needs a stable id per row. */
export interface BulkSelectRow {
  id: number
}

export interface UseBulkSelectOptions<T extends BulkSelectRow> {
  /** Source of every row currently rendered on the page. */
  rows: Ref<T[] | null | undefined>
  /**
   * Filter applied to {@link rows} before "select all" considers a
   * row a candidate. Defaults to "all rows". Subagent Runs uses this
   * to drop RUNNING rows, which the backend rejects with 409.
   */
  selectable?: (row: T) => boolean
  /** Per-id DELETE request issued sequentially for each selection. */
  deleteOne: (id: number) => Promise<unknown>
  /** Hook fired after a successful sweep so callers can refresh data. */
  onComplete?: () => void | Promise<void>
  /**
   * Builder for the confirm-dialog copy keyed off the selection size.
   * Returns the title + message + button label; variant is always
   * danger because every caller of this composable is irreversible.
   */
  confirmCopy: (count: number) => { title: string, message: string, confirmText?: string }
}

export function useBulkSelect<T extends BulkSelectRow>(opts: UseBulkSelectOptions<T>) {
  const { confirm } = useConfirm()

  const selectMode = ref(false)
  const selectedIds = ref<Set<number>>(new Set())
  const deletingBulk = ref(false)

  const selectableRows = computed(() => {
    const all = opts.rows.value ?? []
    return opts.selectable ? all.filter(opts.selectable) : all
  })

  function enter() {
    selectMode.value = true
    selectedIds.value = new Set()
  }

  function exit() {
    selectMode.value = false
    selectedIds.value = new Set()
  }

  function toggle(id: number) {
    const next = new Set(selectedIds.value)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    selectedIds.value = next
  }

  function toggleAll() {
    if (!selectableRows.value.length) return
    if (selectedIds.value.size === selectableRows.value.length) {
      selectedIds.value = new Set()
    }
    else {
      selectedIds.value = new Set(selectableRows.value.map(r => r.id))
    }
  }

  async function deleteSelected() {
    if (!selectedIds.value.size) return
    const count = selectedIds.value.size
    const copy = opts.confirmCopy(count)
    const ok = await confirm({
      title: copy.title,
      message: copy.message,
      confirmText: copy.confirmText ?? 'Delete',
      variant: 'danger',
    })
    if (!ok) return
    deletingBulk.value = true
    try {
      // Sequential — selections are user-curated (small), and parallel
      // fires would contend on per-entity FK-cascade locks for no
      // observable speedup. Surface per-row errors loudly so the
      // operator knows which id failed mid-sweep.
      for (const id of selectedIds.value) {
        await opts.deleteOne(id)
      }
      exit()
      await opts.onComplete?.()
    }
    catch (e) {
      console.error('Bulk delete failed:', e)
    }
    finally {
      deletingBulk.value = false
    }
  }

  return {
    selectMode,
    selectedIds,
    deletingBulk,
    selectableRows,
    enter,
    exit,
    toggle,
    toggleAll,
    deleteSelected,
  }
}
