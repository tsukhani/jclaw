import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick } from 'vue'
import { clearNuxtData } from '#app'
import Conversations from '~/pages/conversations/index.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import PeekPanel from '~/components/PeekPanel.vue'

/**
 * pages/conversations/index.vue flow coverage. The sibling
 * conversations.page.test.ts pins row/icon navigation and structural
 * rendering; this spec drives the still-uncovered flows:
 *
 *   - FilterBar commit → load() query params (q / name / channel / peer,
 *     agent-name → agentId resolution, unknown agent dropped).
 *   - Pagination: Next/Prev drive offset + page label off x-total-count.
 *   - Selection: header select-all/deselect-all, row checkbox toggle, and
 *     the checkbox's click-stopPropagation (no row navigation).
 *   - Bulk delete: ConfirmDialog cancel skips the DELETE; confirm sends
 *     the selected ids and reloads.
 *   - Delete all: filter payload + human-readable filter echo in the
 *     dialog, type-'delete' gate, DELETE with the filter body.
 *   - Quick preview: fetches messages, renders roles + '(tool call)'
 *     placeholder in the PeekPanel; update:open closes it.
 *   - CSV export via the FilterBar export button (RFC-4180 quote doubling).
 */

const { navigateToMock } = vi.hoisted(() => ({
  navigateToMock: vi.fn().mockResolvedValue(undefined),
}))

mockNuxtImport('navigateTo', () => navigateToMock)

/** Mount with a sibling ConfirmDialog so confirm() flows render (the dialog
 *  reads useConfirm()'s module-singleton state; in prod it lives at app root). */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(Conversations), h(ConfirmDialog)])
  },
})

function convo(over: Record<string, unknown> = {}) {
  return {
    id: 101,
    agentId: 1,
    agentName: 'main',
    channelType: 'web',
    peerId: 'admin',
    messageCount: 2,
    preview: 'Hi there',
    parentConversationId: null,
    createdAt: '2026-06-29T10:00:00Z',
    updatedAt: '2026-06-29T10:05:00Z',
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
registerEndpoint('/api/conversations', {
  method: 'GET',
  handler: async (event) => {
    const { getQuery } = await import('h3')
    capturedQueries.push({ ...getQuery(event) })
    event.node.res.setHeader('x-total-count', String(totalCount))
    return listRows
  },
})
registerEndpoint('/api/conversations', {
  method: 'DELETE',
  handler: async (event) => {
    const { readBody } = await import('h3')
    deleteBody = await readBody(event) as Record<string, unknown>
    return { status: 'ok' }
  },
})
registerEndpoint('/api/conversations/101/messages', () => [
  { id: 1, role: 'user', content: 'hello from user', createdAt: '2026-06-29T10:00:00Z' },
  { id: 2, role: 'assistant', content: 'assistant says hi', createdAt: '2026-06-29T10:01:00Z' },
  { id: 3, role: 'tool', content: '', createdAt: '2026-06-29T10:02:00Z' },
])

beforeEach(() => {
  clearNuxtData()
  localStorage.clear() // FilterBar saved views persist per storage key
  navigateToMock.mockClear()
  listRows = [convo(), convo({ id: 102, preview: 'Second one', peerId: 'bob' })]
  totalCount = 2
  capturedQueries = []
  deleteBody = null
})

afterEach(() => {
  // ConfirmDialog + PeekPanel teleport to <body>; drop leftovers so the
  // next test's body-wide queries don't match stale nodes.
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
  document.body.querySelectorAll('[data-slot="sheet-content"]').forEach(el => el.remove())
})

async function commitFilter(component: Awaited<ReturnType<typeof mountSuspended>>, query: string) {
  const input = component.find('input[aria-label="Filter query"]')
  await input.setValue(query)
  await input.trigger('keydown', { key: 'Enter' })
  await flushPromises()
}

describe('Conversations — filter commit drives load() params', () => {
  it('maps q/name/channel/peer filters and resolves the agent name to agentId', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()
    const initialLoads = capturedQueries.length
    expect(initialLoads).toBeGreaterThanOrEqual(1)

    await commitFilter(component, 'q:morning channel:web agent:main peer:bob')
    expect(capturedQueries.length).toBeGreaterThan(initialLoads)

    const q = capturedQueries.at(-1)!
    expect(q.q).toBe('morning')
    expect(q.channel).toBe('web')
    expect(q.agentId).toBe('1') // 'main' resolved case-insensitively to id 1
    expect(q.peer).toBe('bob')
    expect(q.offset).toBe('0') // filter change resets to page 1
    expect(q.limit).toBe('20')
  })

  it('drops the agent param when the name matches no agent', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()

    await commitFilter(component, 'agent:ghost')

    const q = capturedQueries.at(-1)!
    expect(q.agentId).toBeUndefined()
    expect(q.offset).toBe('0')
  })
})

describe('Conversations — pagination', () => {
  it('Next/Prev move the offset and the page label follows x-total-count', async () => {
    totalCount = 42 // 3 pages at pageSize 20
    const component = await mountSuspended(Conversations)
    await flushPromises()

    // rangeEnd is page-arithmetic (min(page × 20, total)), independent of
    // how many rows the stub actually returned.
    expect(component.text()).toContain('Showing 1–20 of 42')
    expect(component.text()).toContain('Page 1 of 3')

    await component.findAll('button').find(b => b.text() === 'Next')!.trigger('click')
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.offset).toBe('20'))
    expect(component.text()).toContain('Page 2 of 3')

    // The async list stub resolves a beat after the offset capture, and both
    // pager buttons are disabled while load() is in flight — wait for Prev to
    // re-arm before clicking it (a trigger on a disabled button is a no-op).
    const prevBtn = component.findAll('button').find(b => b.text() === 'Prev')!
    await vi.waitFor(() => expect(prevBtn.attributes('disabled')).toBeUndefined())
    await prevBtn.trigger('click')
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.offset).toBe('0'))
    expect(component.text()).toContain('Page 1 of 3')
  })
})

describe('Conversations — selection + bulk delete', () => {
  it('select-all arms the Delete button with the count; deselect-all disarms it', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()

    const headerCheckbox = component.find('thead input[type="checkbox"]')
    expect(headerCheckbox.exists()).toBe(true)

    await headerCheckbox.setValue(true)
    await nextTick()
    const deleteBtn = component.findAll('button').find(b => b.text().startsWith('Delete') && !b.text().includes('all'))!
    expect(deleteBtn.text()).toBe('Delete 2')
    expect(deleteBtn.attributes('disabled')).toBeUndefined()

    await headerCheckbox.setValue(false)
    await nextTick()
    expect(deleteBtn.text()).toBe('Delete')
    expect(deleteBtn.attributes('disabled')).toBeDefined()
  })

  it('row checkbox click does not trigger row navigation (stopPropagation)', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()

    // The checkbox intercepts click so the row-level navigate-to-chat
    // handler must not fire. (Selection side-effects of the click are
    // covered separately below — jsdom's activation behavior on a
    // dispatched click also fires change.)
    await component.find('tbody tr input[type="checkbox"]').trigger('click')
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('row checkbox change toggles the id in and out of the selection', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()

    const rowCheckbox = component.find('tbody tr input[type="checkbox"]')
    await rowCheckbox.setValue(true)
    await nextTick()
    const deleteBtn = component.findAll('button').find(b => b.text().startsWith('Delete') && !b.text().includes('all'))!
    expect(deleteBtn.text()).toBe('Delete 1')

    // Toggling again removes the id from the selection.
    await rowCheckbox.setValue(false)
    await nextTick()
    expect(deleteBtn.text()).toBe('Delete')
  })

  it('cancel on the confirm dialog skips the DELETE', async () => {
    const component = await mountSuspended(Harness)
    await flushPromises()

    await component.find('thead input[type="checkbox"]').setValue(true)
    await nextTick()
    await component.findAll('button').find(b => b.text() === 'Delete 2')!.trigger('click')
    await flushPromises()

    const cancelBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Cancel')
    expect(cancelBtn).toBeTruthy()
    cancelBtn!.click()
    await flushPromises()
    await flushPromises()

    expect(deleteBody).toBeNull()
  })

  it('confirm sends DELETE with the selected ids and reloads the list', async () => {
    const component = await mountSuspended(Harness)
    await flushPromises()
    const loadsBefore = capturedQueries.length

    await component.find('thead input[type="checkbox"]').setValue(true)
    await nextTick()
    await component.findAll('button').find(b => b.text() === 'Delete 2')!.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog?.textContent).toContain('Delete 2 conversations')
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deleteBody).not.toBeNull())
    await flushPromises()

    expect([...(deleteBody!.ids as number[])].sort((a, b) => a - b)).toEqual([101, 102])
    expect(capturedQueries.length).toBeGreaterThan(loadsBefore) // reloaded
  })
})

describe('Conversations — delete all with filter scope', () => {
  it('echoes the active filters in the dialog and DELETEs with the filter payload', async () => {
    const component = await mountSuspended(Harness)
    await flushPromises()

    await commitFilter(component, 'channel:web agent:main peer:bob name:hi')

    const deleteAllBtn = component.findAll('button').find(b => b.text().startsWith('Delete all'))!
    expect(deleteAllBtn.text()).toBe('Delete all matching')
    await deleteAllBtn.trigger('click')
    await flushPromises()

    const dialog = document.body.querySelector('[role="dialog"]')
    expect(dialog).toBeTruthy()
    // Human-readable echo of every active filter, so the operator sees the
    // scope before typing the gate word.
    expect(dialog!.textContent).toContain('channel:web')
    expect(dialog!.textContent).toContain('agent:main')
    expect(dialog!.textContent).toContain('peer:bob')
    expect(dialog!.textContent).toContain('name:hi')

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

    // The server-side filter mirrors the UI filters, with the agent name
    // resolved to its id.
    expect(deleteBody!.filter).toEqual({ channel: 'web', agentId: 1, peer: 'bob', name: 'hi' })
  })
})

describe('Conversations — server-side sort', () => {
  it('clicking a column header refetches with sort/dir params (manual sorting)', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()

    // DataTable runs in manualSorting mode: a header click emits sort-change,
    // which load() turns into sort/dir query params. Click the Channel header.
    const channelHeader = component.findAll('th').find(th => th.text().includes('Channel'))!
    expect(channelHeader).toBeTruthy()
    await channelHeader.trigger('click')
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.sort).toBe('channelType'))
    const firstDir = capturedQueries.at(-1)!.dir
    expect(firstDir).toBeDefined()

    // A second click on the same column flips the direction (still server-side).
    await channelHeader.trigger('click')
    await vi.waitFor(() => expect(capturedQueries.at(-1)!.dir).not.toBe(firstDir))
    expect(capturedQueries.at(-1)!.sort).toBe('channelType')
    // Sort resets to page 1.
    expect(capturedQueries.at(-1)!.offset).toBe('0')
  })
})

describe('Conversations — quick preview peek panel', () => {
  it('opens the panel with conversation meta, messages, and the (tool call) placeholder', async () => {
    const component = await mountSuspended(Conversations)
    await flushPromises()

    await component.find('button[title="Quick preview"]').trigger('click')
    await flushPromises()
    await nextTick()

    // PeekPanel teleports to <body> (Sheet). Scope to the sheet's own content:
    // the background table row still renders 'Hi there'/'web'/'main', so a
    // whole-body check would pass even with the panel's meta strip broken.
    const panelText = document.body.querySelector('[data-slot="sheet-content"]')?.textContent ?? ''
    expect(panelText).toContain('Hi there')
    expect(panelText).toContain('hello from user')
    expect(panelText).toContain('assistant says hi')
    // Empty tool-message content falls back to the placeholder.
    expect(panelText).toContain('(tool call)')
    // Meta strip shows channel/agent/peer/count.
    expect(panelText).toContain('web')
    expect(panelText).toContain('main')

    // No navigation happened — the preview button stops propagation so the
    // row-click handler (which would navigate to /chat) never fires.
    expect(navigateToMock).not.toHaveBeenCalled()

    // Closing via the panel's update:open contract flips the page's open
    // state off. (The sheet DOM may linger through reka-ui's presence
    // animation, which never completes in jsdom — the prop is the
    // reliable observable.)
    const panel = component.findComponent(PeekPanel)
    expect(panel.props('open')).toBe(true)
    panel.vm.$emit('update:open', false)
    await nextTick()
    expect(panel.props('open')).toBe(false)
  })
})

describe('Conversations — CSV export', () => {
  let capturedBlob: Blob | null
  let downloadName: string
  let clickSpy: MockInstance<() => void>

  beforeEach(() => {
    capturedBlob = null
    downloadName = ''
    URL.createObjectURL = vi.fn((b: Blob) => {
      capturedBlob = b
      return 'blob:conversations-test'
    }) as typeof URL.createObjectURL
    URL.revokeObjectURL = vi.fn() as typeof URL.revokeObjectURL
    clickSpy = vi.spyOn(HTMLElement.prototype, 'click')
      .mockImplementation(function (this: HTMLElement) {
        downloadName = (this as HTMLAnchorElement).download ?? ''
      })
  })

  afterEach(() => {
    clickSpy.mockRestore()
    delete (URL as Partial<typeof URL>).createObjectURL
    delete (URL as Partial<typeof URL>).revokeObjectURL
  })

  function blobText(b: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(String(r.result))
      r.onerror = () => reject(r.error ?? new Error('read failed'))
      r.readAsText(b)
    })
  }

  it('exports the loaded rows with quote-doubled previews via the FilterBar export button', async () => {
    listRows = [
      convo({ id: 101, preview: 'say "hi"', peerId: 'admin', messageCount: 3 }),
      convo({ id: 102, preview: 'plain', peerId: null }),
    ]
    const component = await mountSuspended(Conversations)
    await flushPromises()

    await component.find('button[title="Export"]').trigger('click')

    expect(capturedBlob).not.toBeNull()
    const csv = await blobText(capturedBlob!)
    const lines = csv.split('\n')
    expect(lines[0]).toBe('ID,Name,Channel,Agent,Peer,Messages,Created,Updated')
    // Embedded quotes doubled per RFC 4180; whole preview wrapped in quotes.
    expect(lines[1]).toBe('101,"say ""hi""",web,main,admin,3,2026-06-29T10:00:00Z,2026-06-29T10:05:00Z')
    // Null peer serializes as an empty cell.
    expect(lines[2]).toBe('102,"plain",web,main,,2,2026-06-29T10:00:00Z,2026-06-29T10:05:00Z')
    expect(downloadName).toBe('conversations.csv')
  })
})
