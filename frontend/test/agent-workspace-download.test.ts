import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceListing } from '~/types/api'

/**
 * JCLAW-1248 — the per-row download action.
 *
 * The action is a link, not a fetch: what matters is that every row offers one, that it points
 * at the download route for that row's own path, and that a name needing escaping survives.
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
        { path: 'downloads/report #1.pdf', name: 'report #1.pdf', kind: 'file', size: 1024, protected: false, children: null },
      ],
    },
    { path: 'AGENT.md', name: 'AGENT.md', kind: 'file', size: 512, protected: true, children: null },
  ],
}

describe('AgentWorkspaceManager downloads', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('links a file row at the download route for its own path', async () => {
    registerEndpoint('/api/agents/21/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 21 } })
    await flushPromises()

    const link = component.find('[data-testid="ws-download-AGENT.md"]')
    expect(link.attributes('href')).toBe('/api/agents/21/workspace-download/AGENT.md')
    expect(link.attributes('download')).toBeDefined()
    expect(link.attributes('aria-label')).toBe('Download AGENT.md')
  })

  it('offers a folder row the same action, described as a zip', async () => {
    registerEndpoint('/api/agents/22/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 22 } })
    await flushPromises()

    const link = component.find('[data-testid="ws-download-downloads"]')
    expect(link.attributes('href')).toBe('/api/agents/22/workspace-download/downloads')
    expect(link.attributes('title')).toContain('zip')
    expect(component.find('[data-testid="ws-download-AGENT.md"]').attributes('title')).not.toContain('zip')
  })

  it('encodes each path segment so a nested name with punctuation survives', async () => {
    registerEndpoint('/api/agents/23/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 23 } })
    await flushPromises()
    await component.find('[data-testid="ws-row-downloads"] button').trigger('click')

    const link = component.find('[data-testid="ws-download-downloads/report #1.pdf"]')
    expect(link.attributes('href')).toBe('/api/agents/23/workspace-download/downloads/report%20%231.pdf')
  })

  it('gives every row a download action, protected rows included', async () => {
    registerEndpoint('/api/agents/24/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 24 } })
    await flushPromises()

    for (const path of ['downloads', 'AGENT.md']) {
      expect(component.find(`[data-testid="ws-download-${path}"]`).exists()).toBe(true)
    }
    // A Standing Orders row offers the download and nothing else; JCLAW-1249 withholds the delete.
    expect(component.find('[data-testid="ws-actions-AGENT.md"]').element.children).toHaveLength(1)
  })
})
