import { describe, it, expect, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { h } from 'vue'
import type { ColumnDef } from '@tanstack/vue-table'
import DataTable from '~/components/DataTable.vue'

interface TestRow {
  id: number
  name: string
  status: string
}

const testColumns: ColumnDef<TestRow, any>[] = [
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
      props: { columns: testColumns, data: testData },
    })
    expect(component.text()).toContain('Name')
    expect(component.text()).toContain('Status')
  })

  it('renders data rows', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns, data: testData },
    })
    expect(component.text()).toContain('Alpha')
    expect(component.text()).toContain('Beta')
    expect(component.text()).toContain('Gamma')
  })

  it('shows empty message when no data', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns, data: [], emptyMessage: 'Nothing here' },
    })
    expect(component.text()).toContain('Nothing here')
  })

  it('shows empty action button when provided', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns, data: [], emptyMessage: 'Empty', emptyAction: 'Create one' },
    })
    expect(component.text()).toContain('Create one')
  })

  it('shows skeleton rows when loading', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns, data: [], loading: true },
    })
    const skeletons = component.findAll('.animate-pulse')
    expect(skeletons.length).toBe(10) // 5 rows × 2 columns
  })

  it('emits row-click when row is clicked', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns, data: testData },
    })
    const rows = component.findAll('tbody tr')
    await rows[0].trigger('click')
    const emitted = component.emitted('row-click')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toEqual(testData[0])
  })

  it('renders sort indicators on sortable columns', async () => {
    const component = await mountSuspended(DataTable, {
      props: { columns: testColumns, data: testData },
    })
    // Click the Name header to sort
    const headers = component.findAll('th')
    await headers[0].trigger('click')
    // Should show an arrow indicator
    expect(component.text()).toMatch(/[↑↓]/)
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
