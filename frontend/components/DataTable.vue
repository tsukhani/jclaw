<script setup lang="ts" generic="TData">
import {
  FlexRender,
  useVueTable,
  getCoreRowModel,
  getSortedRowModel,
  type ColumnDef,
  type SortingState,
  type RowSelectionState,
} from '@tanstack/vue-table'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '~/components/ui/table'

const props = withDefaults(defineProps<{
  columns: ColumnDef<TData, unknown>[]
  data: TData[]
  loading?: boolean
  emptyMessage?: string
  emptyAction?: string
  /** Enable row selection checkboxes */
  selectable?: boolean
}>(), {
  loading: false,
  emptyMessage: 'No data',
  emptyAction: '',
  selectable: false,
})

const emit = defineEmits<{
  (e: 'row-click', row: TData): void
  (e: 'empty-action'): void
}>()

// ── Table state ─────────────────────────────────────────────────────────────
const sorting = ref<SortingState>([])
const rowSelection = ref<RowSelectionState>({})
const focusedRowIndex = ref(-1)

const table = useVueTable({
  get data() { return props.data },
  get columns() { return props.columns },
  getCoreRowModel: getCoreRowModel(),
  getSortedRowModel: getSortedRowModel(),
  state: {
    get sorting() { return sorting.value },
    get rowSelection() { return rowSelection.value },
  },
  onSortingChange: (updater) => {
    sorting.value = typeof updater === 'function' ? updater(sorting.value) : updater
  },
  onRowSelectionChange: (updater) => {
    rowSelection.value = typeof updater === 'function' ? updater(rowSelection.value) : updater
  },
  enableRowSelection: props.selectable,
})

// Expose selection state for parent components
defineExpose({
  getSelectedRows: () => table.getSelectedRowModel().rows.map(r => r.original),
  clearSelection: () => { rowSelection.value = {} },
  toggleAllRowsSelected: (selected: boolean) => table.toggleAllRowsSelected(selected),
})

// ── Keyboard navigation ────────────────────────────────────────────────────
const tableRef = ref<HTMLElement | null>(null)

function handleKeydown(e: KeyboardEvent) {
  const rows = table.getRowModel().rows
  if (!rows.length) return

  if (e.key === 'ArrowDown') {
    e.preventDefault()
    focusedRowIndex.value = Math.min(focusedRowIndex.value + 1, rows.length - 1)
  }
  else if (e.key === 'ArrowUp') {
    e.preventDefault()
    focusedRowIndex.value = Math.max(focusedRowIndex.value - 1, 0)
  }
  else if (e.key === 'Enter' && focusedRowIndex.value >= 0) {
    e.preventDefault()
    const row = rows[focusedRowIndex.value]
    if (row) emit('row-click', row.original)
  }
}

// ── Sort indicator ──────────────────────────────────────────────────────────
function sortIcon(sorted: false | 'asc' | 'desc') {
  if (sorted === 'asc') return '↑'
  if (sorted === 'desc') return '↓'
  return ''
}
</script>

<template>
  <!-- NOSONAR(Web:S6845) — tabindex=0 is intentional: the wrapper is the keyboard focus target so arrow-key handlers (see @keydown) can drive row navigation across the semantic <Table> below. -->
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- focus-trap wrapper around semantic <Table>; tabindex + keydown provides arrow-key row navigation -->
  <div
    ref="tableRef"
    tabindex="0"
    class="focus:outline-hidden"
    @keydown="handleKeydown"
  >
    <Table>
      <TableHeader class="sticky top-0 z-10 bg-surface-elevated">
        <TableRow
          v-for="headerGroup in table.getHeaderGroups()"
          :key="headerGroup.id"
          class="border-b border-border"
        >
          <TableHead
            v-for="header in headerGroup.headers"
            :key="header.id"
            class="px-4 py-2.5 text-xs text-fg-muted font-medium"
            :class="{ 'cursor-pointer select-none hover:text-fg-strong': header.column.getCanSort() }"
            @click="header.column.getToggleSortingHandler()?.($event)"
          >
            <div class="flex items-center gap-1">
              <FlexRender
                v-if="!header.isPlaceholder"
                :render="header.column.columnDef.header"
                :props="header.getContext()"
              />
              <span
                v-if="header.column.getIsSorted()"
                class="text-fg-strong"
              >
                {{ sortIcon(header.column.getIsSorted()) }}
              </span>
            </div>
          </TableHead>
        </TableRow>
      </TableHeader>

      <TableBody>
        <!-- Loading skeleton -->
        <template v-if="loading">
          <TableRow
            v-for="i in 5"
            :key="`skeleton-${i}`"
            class="border-b border-border"
          >
            <TableCell
              v-for="col in columns.length"
              :key="`skeleton-${i}-${col}`"
              class="px-4 py-2.5"
            >
              <div class="h-4 bg-muted rounded animate-pulse" />
            </TableCell>
          </TableRow>
        </template>

        <!-- Empty state -->
        <template v-else-if="!table.getRowModel().rows.length">
          <TableRow>
            <TableCell
              :colspan="columns.length"
              class="px-4 py-8 text-center text-sm text-fg-muted"
            >
              {{ emptyMessage }}
              <button
                v-if="emptyAction"
                class="ml-2 text-emerald-600 dark:text-emerald-400 hover:underline"
                @click="emit('empty-action')"
              >
                {{ emptyAction }}
              </button>
            </TableCell>
          </TableRow>
        </template>

        <!-- Data rows -->
        <template v-else>
          <TableRow
            v-for="(row, index) in table.getRowModel().rows"
            :key="row.id"
            class="border-b border-border text-sm cursor-pointer transition-colors"
            :class="[
              index === focusedRowIndex ? 'bg-muted' : 'hover:bg-muted',
              row.getIsSelected() ? 'bg-emerald-500/5' : '',
            ]"
            @click="emit('row-click', row.original)"
          >
            <TableCell
              v-for="cell in row.getVisibleCells()"
              :key="cell.id"
              class="px-4 py-2.5"
            >
              <FlexRender
                :render="cell.column.columnDef.cell"
                :props="cell.getContext()"
              />
            </TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>
</template>
