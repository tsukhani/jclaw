import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus, type H3Event } from 'h3'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceListing } from '~/types/api'

/**
 * JCLAW-1249 — deleting a workspace file or folder from the tree.
 *
 * The delete is confirmed before it is issued, re-reads the listing rather than reloading the
 * page, and is simply absent on a Standing Orders row. What the UI hides the backend still
 * refuses, so a refusal that reaches the component has to stay on screen.
 */
const full: WorkspaceListing = {
  total: 1536,
  entries: [
    {
      path: 'downloads',
      name: 'downloads',
      kind: 'dir',
      size: 1024,
      protected: false,
      children: [
        { path: 'downloads/report.pdf', name: 'report.pdf', kind: 'file', size: 1024, protected: false, children: null },
      ],
    },
    { path: 'notes.txt', name: 'notes.txt', kind: 'file', size: 511, protected: false, children: null },
    { path: 'AGENT.md', name: 'AGENT.md', kind: 'file', size: 1, protected: true, children: null },
  ],
}

const pruned: WorkspaceListing = {
  total: 1025,
  entries: full.entries.filter(entry => entry.path !== 'notes.txt'),
}

let unregister: Array<() => void> = []

afterEach(() => {
  unregister.forEach(off => off())
  unregister = []
})

/** Serves each listing in turn, so the component's own re-read is what swaps the tree. */
function stubTree(agentId: number, listings: WorkspaceListing[]) {
  let call = 0
  unregister.push(registerEndpoint(`/api/agents/${agentId}/workspace-tree`, () => {
    const listing = listings[Math.min(call, listings.length - 1)]!
    call += 1
    return listing
  }))
}

function stubDelete(url: string, handler: (event: H3Event) => unknown) {
  const spy = vi.fn(handler)
  unregister.push(registerEndpoint(url, { method: 'DELETE', handler: spy }))
  return spy
}

async function mountTree(agentId: number) {
  const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId } })
  await flushPromises()
  return component
}

describe('AgentWorkspaceManager — delete', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('deletes a file once confirmed and re-reads the tree and total', async () => {
    stubTree(20, [full, pruned])
    const del = stubDelete('/api/agents/20/workspace-tree/notes.txt', () => ({ status: 'ok', path: 'notes.txt' }))

    const component = await mountTree(20)
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 1.5 KB')

    await component.find('[data-testid="ws-delete-notes.txt"]').trigger('click')
    await component.find('[data-testid="ws-delete-confirm-notes.txt"]').trigger('click')

    // The delete and the re-read that follows it are two round trips, so wait for the second.
    await vi.waitFor(() => expect(component.find('[data-testid="ws-row-notes.txt"]').exists()).toBe(false))
    expect(del).toHaveBeenCalledTimes(1)
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 1.0 KB')
  })

  it('leaves the file alone when the confirmation is cancelled', async () => {
    stubTree(21, [full])
    const del = stubDelete('/api/agents/21/workspace-tree/notes.txt', () => ({ status: 'ok' }))

    const component = await mountTree(21)
    await component.find('[data-testid="ws-delete-notes.txt"]').trigger('click')
    await component.find('[data-testid="ws-delete-cancel-notes.txt"]').trigger('click')
    await flushPromises()

    expect(del).not.toHaveBeenCalled()
    expect(component.find('[data-testid="ws-row-notes.txt"]').exists()).toBe(true)
    expect(component.find('[data-testid="ws-delete-notes.txt"]').exists()).toBe(true)
  })

  it('deletes a folder by its own path, leaving the subtree to the backend', async () => {
    stubTree(22, [full, { total: 512, entries: full.entries.filter(entry => entry.path !== 'downloads') }])
    const del = stubDelete('/api/agents/22/workspace-tree/downloads', () => ({ status: 'ok', path: 'downloads' }))

    const component = await mountTree(22)
    await component.find('[data-testid="ws-delete-downloads"]').trigger('click')
    await component.find('[data-testid="ws-delete-confirm-downloads"]').trigger('click')

    await vi.waitFor(() => expect(component.find('[data-testid="ws-row-downloads"]').exists()).toBe(false))
    expect(del).toHaveBeenCalledTimes(1)
  })

  it('offers no delete control on a Standing Orders row', async () => {
    stubTree(23, [full])

    const component = await mountTree(23)

    expect(component.find('[data-testid="ws-delete-AGENT.md"]').exists()).toBe(false)
    expect(component.find('[data-testid="ws-actions-AGENT.md"]').element.children.length).toBe(0)
    expect(component.find('[data-testid="ws-delete-notes.txt"]').exists()).toBe(true)
  })

  it('keeps the row and names the refusal when the backend says no', async () => {
    stubTree(24, [full])
    stubDelete('/api/agents/24/workspace-tree/notes.txt', (event) => {
      setResponseStatus(event, 403)
      return { type: 'error', code: 'forbidden', message: 'The workspace root and the Standing Orders files cannot be deleted.' }
    })

    const component = await mountTree(24)
    await component.find('[data-testid="ws-delete-notes.txt"]').trigger('click')
    await component.find('[data-testid="ws-delete-confirm-notes.txt"]').trigger('click')
    await flushPromises()

    expect(component.text()).toContain('cannot be deleted')
    expect(component.find('[data-testid="ws-row-notes.txt"]').exists()).toBe(true)
  })
})
