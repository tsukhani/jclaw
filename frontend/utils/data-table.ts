import {
  columnSizingFeature,
  columnVisibilityFeature,
  createSortedRowModel,
  rowSelectionFeature,
  rowSortingFeature,
  tableFeatures,
  type ColumnDef,
  type RowData,
} from '@tanstack/vue-table'

/**
 * The feature set every DataTable is built from. v9 makes features explicit — the core row
 * model is implicit, sorting and selection are opt-in — and types column definitions against
 * them, so this cannot live inside DataTable.vue: callers declaring columns need the same type.
 */
export const dataTableFeatures = tableFeatures({
  // columnVisibility backs row.getVisibleCells(); columnSizing backs a column's `size`.
  // Both were implicit in v8 and are opt-in here.
  columnSizingFeature,
  columnVisibilityFeature,
  rowSelectionFeature,
  rowSortingFeature,
  sortedRowModel: createSortedRowModel(),
})

/** A column definition for {@link dataTableFeatures}. Replaces v8's `ColumnDef<TData, unknown>`. */
export type DataTableColumn<TData extends RowData> = ColumnDef<typeof dataTableFeatures, TData, unknown>
