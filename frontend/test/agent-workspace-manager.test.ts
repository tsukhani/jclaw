import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceListing } from '~/types/api'

/**
 * JCLAW-1247 — the workspace tree on an agent's detail page.
 *
 * The listing is a window, not a viewer: what matters is that the hierarchy, sizes,
 * protected markers and total reach the screen, that a folder opens on demand, and that
 * no row renders content or an action this story did not ship.
 */
const listing: WorkspaceListing = {
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
    { path: '.env', name: '.env', kind: 'file', size: 511, protected: false, children: null },
    { path: 'AGENT.md', name: 'AGENT.md', kind: 'file', size: 1, protected: true, children: null },
  ],
}

describe('AgentWorkspaceManager', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders the tree with sizes, the protected marker and the total', async () => {
    registerEndpoint('/api/agents/7/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 7 } })
    await flushPromises()

    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 1.5 KB')
    expect(component.find('[data-testid="ws-row-downloads"]').text()).toContain('1.0 KB')
    expect(component.find('[data-testid="ws-row-.env"]').exists()).toBe(true)
    expect(component.find('[data-testid="ws-protected-AGENT.md"]').exists()).toBe(true)
    expect(component.find('[data-testid="ws-protected-.env"]').exists()).toBe(false)
  })

  it('expands a folder on click and collapses it again', async () => {
    registerEndpoint('/api/agents/8/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 8 } })
    await flushPromises()

    expect(component.find('[data-testid="ws-row-downloads/report.pdf"]').exists()).toBe(false)
    const folder = component.find('[data-testid="ws-row-downloads"] button')
    expect(folder.attributes('aria-expanded')).toBe('false')

    await folder.trigger('click')
    expect(component.find('[data-testid="ws-row-downloads/report.pdf"]').exists()).toBe(true)
    expect(folder.attributes('aria-expanded')).toBe('true')

    await folder.trigger('click')
    expect(component.find('[data-testid="ws-row-downloads/report.pdf"]').exists()).toBe(false)
  })

  it('renders no file content', async () => {
    registerEndpoint('/api/agents/9/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 9 } })
    await flushPromises()

    expect(component.findAll('textarea, iframe, img, pre').length).toBe(0)
  })

  it('says so when the workspace is empty', async () => {
    registerEndpoint('/api/agents/10/workspace-tree', () => ({ total: 0, entries: [] }))

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 10 } })
    await flushPromises()

    expect(component.text()).toContain('The workspace is empty.')
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 0 B')
  })

  it('surfaces a refused listing instead of an empty tree', async () => {
    registerEndpoint('/api/agents/11/workspace-tree', (event) => {
      setResponseStatus(event, 403)
      return { type: 'error', code: 'operator_only', message: 'Workspace files are operator-only through this API.' }
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 11 } })
    await flushPromises()

    expect(component.text()).toContain('operator-only')
    expect(component.text()).not.toContain('The workspace is empty.')
  })

  it('renders nothing without an agent', async () => {
    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: null } })
    await flushPromises()

    expect(component.find('[data-testid="workspace-manager"]').exists()).toBe(false)
  })
})
