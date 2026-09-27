import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { RowData } from '@tanstack/vue-table'
import type { DataTableColumn } from '~/utils/data-table'
import DataTable from '~/components/DataTable.vue'

interface TestRow {
  id: number
  name: string
  status: string
}

const testColumns: DataTableColumn<TestRow>[] = [
  { accessorKey: 'name', header: 'Name' },
  { accessorKey: 'status', header: 'Status' },
]

const testData: TestRow[] = [
  { id: 1, name: 'Alpha', status: 'active' },
  { id: 2, name: 'Beta', status: 'inactive' },
  { id: 3, name: 'Gamma', status: 'active' },
]

describe('DataTable', () => {
  it('renders table headers', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: testData },
    })
    expect(component.text()).toContain('Name')
    expect(component.text()).toContain('Status')
  })

  it('renders data rows', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: testData },
    })
    expect(component.text()).toContain('Alpha')
    expect(component.text()).toContain('Beta')
    expect(component.text()).toContain('Gamma')
  })

  it('shows empty message when no data', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: [] as TestRow[], emptyMessage: 'Nothing here' },
    })
    expect(component.text()).toContain('Nothing here')
  })

  it('shows empty action button when provided', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: [] as TestRow[], emptyMessage: 'Empty', emptyAction: 'Create one' },
    })
    expect(component.text()).toContain('Create one')
  })

  it('shows skeleton rows when loading', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: [] as TestRow[], loading: true },
    })
    const skeletons = component.findAll('.animate-pulse')
    expect(skeletons).toHaveLength(10) // 5 rows × 2 columns
  })

  it('emits row-click when row is clicked', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: testData },
    })
    const rows = component.findAll('tbody tr')
    await rows[0]!.trigger('click')
    const emitted = component.emitted('row-click')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toEqual(testData[0])
  })

  it('renders sort indicators on sortable columns', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: testData },
    })
    // Click the Name header to sort
    await component.find('th button').trigger('click')
    // Should show an arrow indicator
    expect(component.text()).toMatch(/[↑↓]/)
  })

  it('makes each sortable header a button and reports its state through aria-sort', async () => {
    const columns: DataTableColumn<TestRow>[] = [
      ...testColumns,
      { id: 'actions', header: 'Actions', enableSorting: false },
    ]
    const component = await mountSuspended(DataTable, {
      props: { columns: columns as DataTableColumn<RowData>[], data: testData },
    })
    const [name, status, actions] = component.findAll('th')
    // A <th> is not focusable; the button is what puts the sort on the Tab order.
    expect(name!.find('button').attributes('type')).toBe('button')
    expect(status!.find('button').exists()).toBe(true)
    expect(actions!.find('button').exists()).toBe(false)
    expect(actions!.attributes('aria-sort')).toBeUndefined()

    expect(name!.attributes('aria-sort')).toBe('none')
    await name!.find('button').trigger('click')
    expect(name!.attributes('aria-sort')).toMatch(/^(ascending|descending)$/)
    expect(status!.attributes('aria-sort')).toBe('none')
  })

  it('leaves Enter on a sort button to the button once a row is highlighted', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns as DataTableColumn<RowData>[], data: testData },
    })
    const wrapper = component.find('[tabindex="0"]')
    await wrapper.trigger('keydown', { key: 'ArrowDown' })
    await component.find('th button').trigger('keydown', { key: 'Enter' })
    expect(component.emitted('row-click')).toBeFalsy()

    // The wrapper's own row navigation is unchanged.
    await wrapper.trigger('keydown', { key: 'Enter' })
    expect(component.emitted('row-click')![0]![0]).toEqual(testData[0])
  })
})

// ── Component exports ───────────────────────────────────────────────────────

describe('Table component exports', () => {
  it('exports all table subcomponents', async () => {
    const table = await import('~/components/ui/table')
    expect(table.Table).toBeDefined()
    expect(table.TableBody).toBeDefined()
    expect(table.TableCell).toBeDefined()
    expect(table.TableHead).toBeDefined()
    expect(table.TableHeader).toBeDefined()
    expect(table.TableRow).toBeDefined()
    expect(table.TableEmpty).toBeDefined()
    expect(table.TableFooter).toBeDefined()
    expect(table.TableCaption).toBeDefined()
  })
})
