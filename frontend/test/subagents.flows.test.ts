import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { clearNuxtData } from '#app'
import Subagents from '~/pages/subagents.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import PeekPanel from '~/components/PeekPanel.vue'

/**
 * pages/subagents.vue flow coverage for the JCLAW-690-adjacent layout parity
 * with the conversations page. The sibling subagents.page.test.ts pins row /
 * kill / view-transcript rendering; this spec drives the two flows that page
 * gained to match /conversations:
 *
 *   - Delete all: filter payload mirrors the list's params (parentAgent name
 *     → id, status), human-readable filter echo in the dialog, type-'delete'
 *     gate, DELETE /api/subagent-runs with the filter body.
 *   - Quick preview: fetches the child conversation transcript and renders it
 *     (roles + '(tool call)' placeholder) in the PeekPanel; update:open closes.
 */

const routeQuery = { value: {} as Record<string, string> }
mockNuxtImport('useRoute', () => () => ({ query: routeQuery.value }))

/** Mount with a sibling ConfirmDialog so confirm() flows render (the dialog
 *  reads useConfirm()'s module-singleton state; in prod it lives at app root). */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(Subagents), h(ConfirmDialog)])
  },
})

function run(over: Record<string, unknown> = {}) {
  return {
    id: 11,
    parentAgentId: 1,
    parentAgentName: 'main',
    childAgentId: 2,
    childAgentName: 'main-sub-abc',
    parentConversationId: 5,
    childConversationId: 6,
    mode: 'session',
    status: 'COMPLETED',
    startedAt: '2026-05-14T09:00:00Z',
    endedAt: '2026-05-14T09:00:30Z',
    outcome: 'Task done.',
    ...over,
  }
}

let listRows: unknown[] = []
let totalCount = 0
let capturedQueries: Record<string, unknown>[] = []
let deleteBody: Record<string, unknown> | null = null

registerEndpoint('/api/agents', () => [
  { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openai', modelId: 'gpt-4.1' },
  { id: 2, name: 'helper', enabled: true, isMain: false, modelProvider: 'openai', modelId: 'gpt-4.1' },
])
registerEndpoint('/api/subagent-runs', {
  method: 'GET',
  handler: async (event) => {
    const { getQuery } = await import('h3')
    capturedQueries.push({ ...getQuery(event) })
    event.node.res.setHeader('x-total-count', String(totalCount))
    return listRows
  },
})
registerEndpoint('/api/subagent-runs', {
  method: 'DELETE',
  handler: async (event) => {
    const { readBody } = await import('h3')
    deleteBody = await readBody(event) as Record<string, unknown>
    return { deleted: totalCount }
  },
})
registerEndpoint('/api/conversations/6/messages', () => [
  { id: 1, role: 'user', content: 'delegate this', createdAt: '2026-05-14T09:00:00Z' },
  { id: 2, role: 'assistant', content: 'on it', createdAt: '2026-05-14T09:00:10Z' },
  { id: 3, role: 'tool', content: '', createdAt: '2026-05-14T09:00:20Z' },
])

beforeEach(() => {
  clearNuxtData()
  localStorage.clear() // FilterBar saved views persist per storage key
  routeQuery.value = {}
  listRows = [run(), run({ id: 12, childAgentName: 'main-sub-def', childConversationId: 7 })]
  totalCount = 2
  capturedQueries = []
  deleteBody = null
})

afterEach(() => {
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
  document.body.querySelectorAll('[data-slot="sheet-content"]').forEach(el => el.remove())
})

async function commitFilter(component: Awaited<ReturnType<typeof mountSuspended>>, query: string) {
  const input = component.find('input[aria-label="Filter query"]')
  await input.setValue(query)
  await input.trigger('keydown', { key: 'Enter' })
  await flushPromises()
}

describe('Subagents — delete all with filter scope', () => {
  it('echoes the active filters in the dialog and DELETEs with the filter payload', async () => {
    const component = await mountSuspended(Harness)
    await flushPromises()

    await commitFilter(component, 'parentAgent:main status:COMPLETED')

    const deleteAllBtn = component.findAll('button').find(b => b.text().startsWith('Delete all'))!
    expect(deleteAllBtn.text()).toBe('Delete all matching')
    await deleteAllBtn.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).toBeTruthy()
    // Human-readable echo of the active filters before the gate word.
    expect(dialog!.textContent).toContain('parentAgent:main')
    expect(dialog!.textContent).toContain('status:COMPLETED')

    // requireText gate: confirm stays locked until 'delete' is typed.
    const gateInput = document.body.querySelector<HTMLInputElement>('[role="dialog"] input[type="text"]')
    expect(gateInput).toBeTruthy()
    gateInput!.value = 'delete'
    gateInput!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete 2')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleteBody).not.toBeNull())

    // The server-side filter mirrors the UI filters, with the parent-agent
    // name resolved to its id.
    expect(deleteBody!.filter).toEqual({ parentAgentId: 1, status: 'COMPLETED' })
  })

  it('unfiltered delete all sends an empty filter object', async () => {
    const component = await mountSuspended(Harness)
    await flushPromises()

    const deleteAllBtn = component.findAll('button').find(b => b.text().startsWith('Delete all'))!
    // No active filters → label drops the "matching" qualifier.
    expect(deleteAllBtn.text()).toBe('Delete all')
    await deleteAllBtn.trigger('click')
    await flushPromises()

    const gateInput = document.body.querySelector<HTMLInputElement>('[role="dialog"] input[type="text"]')
    gateInput!.value = 'delete'
    gateInput!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()

    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete 2')
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleteBody).not.toBeNull())

    expect(deleteBody!.filter).toEqual({})
  })
})

describe('Subagents — quick preview peek panel', () => {
  it('opens the panel with run meta, the child transcript, and the (tool call) placeholder', async () => {
    const component = await mountSuspended(Subagents)
    await flushPromises()

    // First row's quick-preview button (child conversation #6).
    await component.findAll('button[title="Quick preview"]')[0]!.trigger('click')
    await flushPromises()
    await nextTick()

    const panelText = document.body.querySelector('[data-slot="sheet-content"]')?.textContent ?? ''
    // Run meta strip.
    expect(panelText).toContain('main-sub-abc')
    expect(panelText).toContain('session')
    expect(panelText).toContain('COMPLETED')
    // Outcome block + child transcript messages.
    expect(panelText).toContain('Task done.')
    expect(panelText).toContain('delegate this')
    expect(panelText).toContain('on it')
    // Empty tool-message content falls back to the placeholder.
    expect(panelText).toContain('(tool call)')

    // Closing via the panel's update:open contract flips the open state off.
    const panel = component.findComponent(PeekPanel)
    expect(panel.props('open')).toBe(true)
    panel.vm.$emit('update:open', false)
    await nextTick()
    expect(panel.props('open')).toBe(false)
  })
})

describe('Subagents — pagination', () => {
  it('Next/Prev move the offset and the page label follows x-total-count', async () => {
    totalCount = 42 // 3 pages at pageSize 20
    const component = await mountSuspended(Subagents)
    await flushPromises()

    // rangeEnd is page-arithmetic (min(page × 20, total)), independent of how
    // many rows the stub actually returned.
    expect(component.text()).toContain('Showing 1–20 of 42')
    expect(component.text()).toContain('Page 1 of 3')
    expect(component.findAll('button').find(b => b.text() === 'Prev')!.attributes('disabled')).toBeDefined()

    await component.findAll('button').find(b => b.text() === 'Next')!.trigger('click')
    // page → offset lives in the `url` computed; the watch turns the change
    // into a refetch, so wait for the captured offset rather than asserting
    // synchronously.
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.offset).toBe('20'))
    expect(component.text()).toContain('Page 2 of 3')

    // Both pager buttons are disabled while the refetch is in flight; wait for
    // Prev to re-arm before clicking (a trigger on a disabled button no-ops).
    const prevBtn = component.findAll('button').find(b => b.text() === 'Prev')!
    await vi.waitFor(() => expect(prevBtn.attributes('disabled')).toBeUndefined())
    await prevBtn.trigger('click')
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.offset).toBe('0'))
    expect(component.text()).toContain('Page 1 of 3')
  })

  it('committing a filter resets to page 1', async () => {
    totalCount = 42
    const component = await mountSuspended(Subagents)
    await flushPromises()

    await component.findAll('button').find(b => b.text() === 'Next')!.trigger('click')
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.offset).toBe('20'))

    const input = component.find('input[aria-label="Filter query"]')
    await input.setValue('status:COMPLETED')
    await input.trigger('keydown', { key: 'Enter' })
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.offset).toBe('0'))
    expect(capturedQueries.at(-1)!.status).toBe('COMPLETED')
    expect(component.text()).toContain('Page 1 of 3')
  })
})
