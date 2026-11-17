import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { setResponseStatus, type H3Event } from 'h3'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceEntry, WorkspaceListing } from '~/types/api'

/**
 * JF-497 — ticking several workspace entries and deleting them in one confirmed run.
 *
 * A folder's DELETE takes its subtree, so a ticked folder covers its contents and no request goes
 * out for them; under a filter, "select all" stops at the entries that match by their own name.
 */
function file(path: string, size = 512, isProtected = false): WorkspaceEntry {
  return { path, name: path.split('/').pop()!, kind: 'file', size, protected: isProtected, children: null }
}

function dir(path: string, children: WorkspaceEntry[]): WorkspaceEntry {
  return { path, name: path.split('/').pop()!, kind: 'dir', size: children.reduce((n, c) => n + c.size, 0), protected: false, children }
}

const LISTING: WorkspaceListing = {
  total: 4096,
  entries: [
    dir('downloads', [file('downloads/report.pdf'), file('downloads/invoice.pdf')]),
    file('screenshot-1.png'),
    file('screenshot-2.png'),
    file('notes.txt'),
    file('AGENT.md', 24, true),
  ],
}

function without(listing: WorkspaceListing, ...gone: string[]): WorkspaceListing {
  const prune = (list: WorkspaceEntry[]): WorkspaceEntry[] => list
    .filter(entry => !gone.includes(entry.path))
    .map(entry => entry.children ? { ...entry, children: prune(entry.children) } : entry)
  return { total: listing.total, entries: prune(listing.entries) }
}

let unregister: Array<() => void> = []

afterEach(() => {
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
  unregister.forEach(off => off())
  unregister = []
})

/** Serves each listing in turn, so the component's own re-read is what swaps the tree. */
function stubTree(agentId: number, listings: WorkspaceListing[]) {
  let call = 0
  const spy = vi.fn(() => {
    const listing = listings[Math.min(call, listings.length - 1)]!
    call += 1
    return listing
  })
  unregister.push(registerEndpoint(`/api/agents/${agentId}/workspace-tree`, spy))
  return spy
}

function stubDelete(url: string, handler: (event: H3Event) => unknown = () => ({ status: 'ok' })) {
  const spy = vi.fn(handler)
  unregister.push(registerEndpoint(url, { method: 'DELETE', handler: spy }))
  return spy
}

async function mountTree(agentId: number) {
  const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId } })
  await flushPromises()
  return component
}

function rowPaths(component: VueWrapper) {
  return component.findAll('[data-testid^="ws-row-"]').map(r => r.attributes('data-testid')!.replace('ws-row-', ''))
}

function checkbox(component: VueWrapper, path: string) {
  return component.find<HTMLInputElement>(`[data-testid="ws-select-${path}"]`)
}

function deleteButton(component: VueWrapper) {
  return component.find<HTMLButtonElement>('[data-testid="workspace-delete-selected"]')
}

async function startSelecting(component: VueWrapper) {
  await component.find('[data-testid="workspace-select"]').trigger('click')
}

async function tick(component: VueWrapper, ...paths: string[]) {
  for (const path of paths) await checkbox(component, path).setValue(true)
}

function statusText(component: VueWrapper) {
  return component.find('[data-testid="workspace-delete-status"]').text()
}

/** Each log line as [state, the label the operator reads]. */
function logLines(component: VueWrapper) {
  return component.findAll('[data-testid^="ws-log-"]').map(li => [
    li.attributes('data-testid')!.replace('ws-log-', ''),
    li.attributes('data-state'),
    li.find('[data-role="state"]').text(),
  ])
}

/** A DELETE that answers only once the test opens it, so the run can be watched mid-flight. */
function gate() {
  let open!: () => void
  const opened = new Promise<void>((resolve) => {
    open = resolve
  })
  return { open, handler: async () => {
    await opened
    return { status: 'ok' }
  } }
}

async function answerConfirm(yes: boolean) {
  await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
  useConfirm()._resolve(yes)
  await flushPromises()
}

describe('AgentWorkspaceManager — select and delete several', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('deletes every ticked entry once confirmed, re-reads the tree once and leaves selection mode', async () => {
    const tree = stubTree(40, [LISTING, without(LISTING, 'screenshot-1.png', 'screenshot-2.png')])
    const first = stubDelete('/api/agents/40/workspace-tree/screenshot-1.png')
    const second = stubDelete('/api/agents/40/workspace-tree/screenshot-2.png')

    const component = await mountTree(40)
    await startSelecting(component)
    await tick(component, 'screenshot-1.png', 'screenshot-2.png')
    expect(deleteButton(component).text()).toBe('Delete 2')

    await deleteButton(component).trigger('click')
    await answerConfirm(true)

    await vi.waitFor(() => expect(rowPaths(component)).toEqual(['downloads', 'notes.txt', 'AGENT.md']))
    expect(first).toHaveBeenCalledTimes(1)
    expect(second).toHaveBeenCalledTimes(1)
    expect(tree).toHaveBeenCalledTimes(2)
    expect(deleteButton(component).exists()).toBe(false)
    expect(component.find('[data-testid="ws-select-notes.txt"]').exists()).toBe(false)
  })

  it('leaves selection mode as soon as the last DELETE lands, not when the slow re-read answers', async () => {
    const reread = gate()
    let reads = 0
    unregister.push(registerEndpoint('/api/agents/55/workspace-tree', async () => {
      reads += 1
      if (reads === 1) return LISTING
      await reread.handler()
      return without(LISTING, 'notes.txt')
    }))
    stubDelete('/api/agents/55/workspace-tree/notes.txt')

    const component = await mountTree(55)
    await startSelecting(component)
    await tick(component, 'notes.txt')
    await deleteButton(component).trigger('click')
    await answerConfirm(true)

    await vi.waitFor(() => expect(reads).toBe(2))
    expect(deleteButton(component).exists()).toBe(false)
    expect(component.find('[data-testid="workspace-select"]').exists()).toBe(true)
    expect(statusText(component)).toBe('Deleted notes.txt.')
    reread.open()
    await flushPromises()
  })

  it('asks once, naming the files and folders that go', async () => {
    stubTree(41, [LISTING])

    const component = await mountTree(41)
    await startSelecting(component)
    await tick(component, 'downloads', 'notes.txt')
    await deleteButton(component).trigger('click')

    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    const { title, message, confirmText, variant } = useConfirm()._state
    expect({ title, message, confirmText, variant }).toEqual({
      title: 'Delete from the workspace',
      message: 'Permanently delete 1 file and 1 folder with everything in it? This cannot be undone.\n\ndownloads/\nnotes.txt',
      confirmText: 'Delete',
      variant: 'danger',
    })
  })

  it('lists ten entries in full and folds the eleventh into a count', async () => {
    const names = Array.from({ length: 11 }, (_, i) => `shot-${String(i + 1).padStart(2, '0')}.png`)
    stubTree(42, [{ total: 11 * 512, entries: names.map(n => file(n)) }])

    const component = await mountTree(42)
    await startSelecting(component)
    await tick(component, ...names.slice(0, 10))
    await deleteButton(component).trigger('click')
    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    expect(useConfirm()._state.message).toBe(
      `Permanently delete 10 files? This cannot be undone.\n\n${names.slice(0, 10).join('\n')}`)
    useConfirm()._resolve(false)
    await flushPromises()

    await tick(component, names[10]!)
    await deleteButton(component).trigger('click')
    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    expect(useConfirm()._state.message).toBe(
      `Permanently delete 11 files? This cannot be undone.\n\n${names.slice(0, 10).join('\n')}\n…and 1 more`)
  })

  it('deletes nothing when the confirmation is cancelled, and keeps the ticks', async () => {
    stubTree(43, [LISTING])
    const del = stubDelete('/api/agents/43/workspace-tree/notes.txt')

    const component = await mountTree(43)
    await startSelecting(component)
    await tick(component, 'notes.txt')
    await deleteButton(component).trigger('click')
    await answerConfirm(false)

    expect(del).not.toHaveBeenCalled()
    expect(rowPaths(component)).toEqual(['downloads', 'screenshot-1.png', 'screenshot-2.png', 'notes.txt', 'AGENT.md'])
    expect(checkbox(component, 'notes.txt').element.checked).toBe(true)
    expect(deleteButton(component).text()).toBe('Delete 1')
  })

  it('lets a ticked folder cover its contents and sends one DELETE for the folder alone', async () => {
    stubTree(44, [LISTING, without(LISTING, 'downloads')])
    const folder = stubDelete('/api/agents/44/workspace-tree/downloads')
    const report = stubDelete('/api/agents/44/workspace-tree/downloads/report.pdf')

    const component = await mountTree(44)
    await component.find('[data-testid="ws-row-downloads"] button').trigger('click')
    await startSelecting(component)
    await tick(component, 'downloads/report.pdf')
    expect(deleteButton(component).text()).toBe('Delete 1')

    await tick(component, 'downloads')
    for (const child of ['downloads/report.pdf', 'downloads/invoice.pdf']) {
      expect(checkbox(component, child).element.checked).toBe(true)
      expect(checkbox(component, child).element.disabled).toBe(true)
    }
    expect(deleteButton(component).text()).toBe('Delete 1')

    await deleteButton(component).trigger('click')
    await answerConfirm(true)
    await vi.waitFor(() => expect(rowPaths(component)).toEqual(['screenshot-1.png', 'screenshot-2.png', 'notes.txt', 'AGENT.md']))
    expect(folder).toHaveBeenCalledTimes(1)
    expect(report).not.toHaveBeenCalled()
    expect(statusText(component)).toBe('Deleted downloads/.')
  })

  it('selects all shown entries but the Standing Orders, and a second click clears them', async () => {
    stubTree(45, [LISTING])

    const component = await mountTree(45)
    await startSelecting(component)

    expect(component.find('[data-testid="ws-select-AGENT.md"]').exists()).toBe(false)
    expect(component.find('[data-testid="ws-delete-notes.txt"]').exists()).toBe(false)
    const selectAll = component.find<HTMLInputElement>('[data-testid="workspace-select-all"]')
    expect(selectAll.element.closest('label')!.textContent!.trim()).toBe('Select all')

    await selectAll.setValue(true)
    expect(deleteButton(component).text()).toBe('Delete 4')
    for (const path of ['downloads', 'screenshot-1.png', 'screenshot-2.png', 'notes.txt']) {
      expect(checkbox(component, path).element.checked).toBe(true)
    }

    await selectAll.setValue(false)
    expect(deleteButton(component).text()).toBe('Delete')
    expect(deleteButton(component).element.disabled).toBe(true)
  })

  it('selects all matching under a filter, never a folder shown only to lead to a match', async () => {
    stubTree(46, [LISTING])

    const component = await mountTree(46)
    await startSelecting(component)
    await component.find('[data-testid="workspace-filter"]').setValue('report')
    await vi.waitFor(() => expect(rowPaths(component)).toEqual(['downloads', 'downloads/report.pdf']))

    const selectAll = component.find<HTMLInputElement>('[data-testid="workspace-select-all"]')
    expect(selectAll.element.closest('label')!.textContent!.trim()).toBe('Select all matching')
    await selectAll.setValue(true)

    expect(checkbox(component, 'downloads/report.pdf').element.checked).toBe(true)
    expect(checkbox(component, 'downloads').element.checked).toBe(false)
    expect(deleteButton(component).text()).toBe('Delete 1')
  })

  it('stops at a refusal: what was deleted leaves the tree, the rest stays ticked, the refusal is shown', async () => {
    stubTree(47, [LISTING, without(LISTING, 'screenshot-1.png')])
    stubDelete('/api/agents/47/workspace-tree/screenshot-1.png')
    stubDelete('/api/agents/47/workspace-tree/screenshot-2.png', (event) => {
      setResponseStatus(event, 500)
      return { type: 'error', code: 'internal_error', message: 'Could not delete the workspace entry: busy' }
    })
    const notReached = stubDelete('/api/agents/47/workspace-tree/notes.txt')

    const component = await mountTree(47)
    await startSelecting(component)
    await tick(component, 'screenshot-1.png', 'screenshot-2.png', 'notes.txt')
    await deleteButton(component).trigger('click')
    await answerConfirm(true)

    await vi.waitFor(() => expect(rowPaths(component)).toEqual(['downloads', 'screenshot-2.png', 'notes.txt', 'AGENT.md']))
    expect(notReached).not.toHaveBeenCalled()
    expect(checkbox(component, 'screenshot-2.png').element.checked).toBe(true)
    expect(checkbox(component, 'notes.txt').element.checked).toBe(true)
    expect(deleteButton(component).text()).toBe('Delete 2')
    expect(component.find('[data-testid="api-error"]').text()).toContain('Could not delete the workspace entry: busy')
    expect(statusText(component)).toBe('Deleted 1 of 3; stopped at screenshot-2.png.')
    expect(logLines(component)).toEqual([
      ['screenshot-1.png', 'deleted', 'deleted'],
      ['screenshot-2.png', 'failed', 'failed'],
      ['notes.txt', 'queued', 'not deleted'],
    ])

    await checkbox(component, 'notes.txt').setValue(false)
    expect(component.find('[data-testid="workspace-delete-log"]').exists()).toBe(false)
    expect(component.findAll('[data-testid^="ws-tally-"]').map(li => li.attributes('data-testid'))).toEqual(['ws-tally-screenshot-2.png'])
  })

  it('claims nothing deleted when the first entry is refused', async () => {
    const tree = stubTree(52, [LISTING])
    stubDelete('/api/agents/52/workspace-tree/notes.txt', (event) => {
      setResponseStatus(event, 403)
      return { type: 'error', code: 'forbidden', message: 'The workspace root and the Standing Orders files cannot be deleted.' }
    })

    const component = await mountTree(52)
    await startSelecting(component)
    await tick(component, 'notes.txt')
    await deleteButton(component).trigger('click')
    await answerConfirm(true)

    await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
    expect(statusText(component)).toBe('')
    expect(tree).toHaveBeenCalledTimes(1)
    expect(checkbox(component, 'notes.txt').element.checked).toBe(true)
  })

  it('shows each entry going: the count, the row in flight, and the list and total shrinking before the run ends', async () => {
    const tree = stubTree(51, [LISTING, without(LISTING, 'screenshot-1.png', 'screenshot-2.png')])
    const first = gate()
    const second = gate()
    stubDelete('/api/agents/51/workspace-tree/screenshot-1.png', first.handler)
    stubDelete('/api/agents/51/workspace-tree/screenshot-2.png', second.handler)

    const component = await mountTree(51)
    await startSelecting(component)
    await tick(component, 'screenshot-1.png', 'screenshot-2.png')
    await deleteButton(component).trigger('click')
    await answerConfirm(true)

    await vi.waitFor(() => expect(statusText(component)).toBe('Deleting 1 of 2: screenshot-1.png'))
    expect(logLines(component)).toEqual([
      ['screenshot-1.png', 'deleting', 'deleting…'],
      ['screenshot-2.png', 'queued', 'waiting'],
    ])
    expect(component.find('[data-testid="workspace-selection"]').exists()).toBe(false)
    expect(component.find('[data-testid="ws-deleting-screenshot-1.png"]').text()).toBe('Deleting…')
    expect(deleteButton(component).text()).toBe('Deleting…')
    expect(deleteButton(component).element.disabled).toBe(true)

    first.open()
    await vi.waitFor(() => expect(statusText(component)).toBe('Deleting 2 of 2: screenshot-2.png'))
    expect(rowPaths(component)).toEqual(['downloads', 'screenshot-2.png', 'notes.txt', 'AGENT.md'])
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 3.5 KB')
    expect(logLines(component)).toEqual([
      ['screenshot-1.png', 'deleted', 'deleted'],
      ['screenshot-2.png', 'deleting', 'deleting…'],
    ])
    expect(tree).toHaveBeenCalledTimes(1)

    second.open()
    await vi.waitFor(() => expect(statusText(component)).toBe('Deleted 2 files.'))
    expect(rowPaths(component)).toEqual(['downloads', 'notes.txt', 'AGENT.md'])
    expect(tree).toHaveBeenCalledTimes(2)
    expect(logLines(component)).toEqual([
      ['screenshot-1.png', 'deleted', 'deleted'],
      ['screenshot-2.png', 'deleted', 'deleted'],
    ])

    await component.find('[data-testid="workspace-delete-dismiss"]').trigger('click')
    expect(statusText(component)).toBe('')
    expect(component.find('[data-testid="workspace-delete-log"]').exists()).toBe(false)
  })

  it('tallies every tick, flags the ones the list hides, and unticks from the tally', async () => {
    stubTree(54, [LISTING])

    const component = await mountTree(54)
    await startSelecting(component)
    await tick(component, 'notes.txt', 'downloads')
    await component.find('[data-testid="workspace-filter"]').setValue('screenshot')
    await vi.waitFor(() => expect(rowPaths(component)).toEqual(['screenshot-1.png', 'screenshot-2.png']))
    await tick(component, 'screenshot-1.png')

    const summary = () => component.find('[data-testid="workspace-selection-summary"]').text()
    const hiddenTag = (path: string) => component.find(`[data-testid="ws-tally-${path}"] [title="Filtered out, or inside a collapsed folder"]`).exists()
    expect(summary()).toBe('3 selected, 2 hidden from the list below')
    expect(component.findAll('[data-testid^="ws-tally-"]').map(li => li.attributes('data-testid'))).toEqual([
      'ws-tally-notes.txt', 'ws-tally-downloads', 'ws-tally-screenshot-1.png',
    ])
    expect([hiddenTag('notes.txt'), hiddenTag('downloads'), hiddenTag('screenshot-1.png')]).toEqual([true, true, false])
    expect(component.find('[data-testid="ws-tally-downloads"]').text()).toContain('with everything in it')

    await component.find('[data-testid="ws-untick-notes.txt"]').trigger('click')
    expect(summary()).toBe('2 selected, 1 hidden from the list below')
    expect(deleteButton(component).text()).toBe('Delete 2')

    await component.find('[data-testid="ws-untick-downloads"]').trigger('click')
    expect(summary()).toBe('1 selected')
  })

  it('shows a single row delete in flight and names what went', async () => {
    stubTree(53, [LISTING, without(LISTING, 'notes.txt')])
    const only = gate()
    stubDelete('/api/agents/53/workspace-tree/notes.txt', only.handler)

    const component = await mountTree(53)
    await component.find('[data-testid="ws-delete-notes.txt"]').trigger('click')
    await component.find('[data-testid="ws-delete-confirm-notes.txt"]').trigger('click')

    await vi.waitFor(() => expect(statusText(component)).toBe('Deleting notes.txt'))
    expect(component.find('[data-testid="ws-deleting-notes.txt"]').exists()).toBe(true)
    expect(component.find('[data-testid="ws-delete-confirm-notes.txt"]').exists()).toBe(false)

    only.open()
    await vi.waitFor(() => expect(statusText(component)).toBe('Deleted notes.txt.'))
    expect(component.find('[data-testid="ws-row-notes.txt"]').exists()).toBe(false)
  })

  it('leaves selection mode with nothing ticked on Cancel, and brings the row delete back', async () => {
    stubTree(48, [LISTING])

    const component = await mountTree(48)
    await startSelecting(component)
    await tick(component, 'notes.txt')
    await component.find('[data-testid="workspace-select-cancel"]').trigger('click')

    expect(component.find('[data-testid="ws-select-notes.txt"]').exists()).toBe(false)
    expect(component.find('[data-testid="ws-delete-notes.txt"]').exists()).toBe(true)

    await startSelecting(component)
    expect(checkbox(component, 'notes.txt').element.checked).toBe(false)
    expect(deleteButton(component).element.disabled).toBe(true)
  })

  it('drops a ticked entry the refresh no longer lists', async () => {
    stubTree(49, [LISTING, without(LISTING, 'notes.txt')])

    const component = await mountTree(49)
    await startSelecting(component)
    await tick(component, 'notes.txt', 'screenshot-1.png')
    expect(deleteButton(component).text()).toBe('Delete 2')

    await (component.vm as unknown as { reload: () => Promise<void> }).reload()
    await flushPromises()

    expect(deleteButton(component).text()).toBe('Delete 1')
    await deleteButton(component).trigger('click')
    await vi.waitFor(() => expect(useConfirm()._state.open).toBe(true))
    expect(useConfirm()._state.message).toBe('Permanently delete 1 file? This cannot be undone.\n\nscreenshot-1.png')
  })
})

describe('AgentWorkspaceManager — filter box', () => {
  it('hides the browser\'s own clear button, leaving the panel\'s as the only one', async () => {
    clearNuxtData()
    stubTree(50, [LISTING])

    const component = await mountTree(50)

    expect(component.find('[data-testid="workspace-filter"]').classes()).toContain('[&::-webkit-search-cancel-button]:appearance-none')
  })
})
