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

import type { ApiErrorDetails } from '~/types/api'

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
  /** URL the composable DELETEs for each selected id, sequentially. */
  deleteUrl: (id: number) => string
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

  const { mutate, errorDetails } = useApiMutation()

  const selectMode = ref(false)
  const selectedIds = ref<Set<number>>(new Set())
  const deletingBulk = ref(false)
  const bulkError = ref<ApiErrorDetails | null>(null)

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
    bulkError.value = null
    const deleted: number[] = []
    let failed = false
    // Sequential — selections are user-curated (small), and parallel
    // fires would contend on per-entity FK-cascade locks for no
    // observable speedup.
    for (const id of selectedIds.value) {
      if (await mutate(opts.deleteUrl(id), { method: 'DELETE' }) === null) {
        failed = true
        break
      }
      deleted.push(id)
    }
    if (failed) {
      // Stop at the failure. What was deleted leaves the selection and, through the refresh, the list;
      // the rest stay selected so the operator can retry once the reason is fixed.
      bulkError.value = errorDetails.value ? { ...errorDetails.value } : null
      const remaining = new Set(selectedIds.value)
      for (const id of deleted) remaining.delete(id)
      selectedIds.value = remaining
    }
    else {
      exit()
    }
    deletingBulk.value = false
    if (deleted.length) await opts.onComplete?.()
  }

  return {
    selectMode,
    selectedIds,
    deletingBulk,
    bulkError,
    selectableRows,
    enter,
    exit,
    toggle,
    toggleAll,
    deleteSelected,
  }
}
