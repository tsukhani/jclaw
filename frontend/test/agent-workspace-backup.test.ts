import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceListing } from '~/types/api'

/**
 * JCLAW-1251 — the whole-workspace backup button in the section header.
 *
 * It is a link, so the browser owns the save and the server's Content-Disposition owns the
 * filename; what this spec pins is where it points and when it is offered at all.
 */
const listing: WorkspaceListing = {
  total: 1536,
  entries: [
    { path: 'AGENT.md', name: 'AGENT.md', kind: 'file', size: 1536, protected: true, children: null },
  ],
}

describe('AgentWorkspaceManager backup', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('offers a backup link in the header, beside the total', async () => {
    registerEndpoint('/api/agents/31/workspace-tree', () => listing)

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 31 } })
    await flushPromises()

    const backup = component.find('[data-testid="workspace-backup"]')
    expect(backup.attributes('href')).toBe('/api/agents/31/workspace-backup')
    expect(backup.attributes('download')).toBeDefined()
    expect(backup.text()).toContain('Back up')
    expect(component.find('[data-testid="workspace-total"]').exists()).toBe(true)
  })

  it('still offers the backup when the workspace is empty', async () => {
    registerEndpoint('/api/agents/32/workspace-tree', () => ({ total: 0, entries: [] }))

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 32 } })
    await flushPromises()

    expect(component.find('[data-testid="workspace-backup"]').attributes('href'))
      .toBe('/api/agents/32/workspace-backup')
  })

  it('offers no backup when the listing was refused', async () => {
    registerEndpoint('/api/agents/33/workspace-tree', (event) => {
      setResponseStatus(event, 403)
      return { type: 'error', code: 'operator_only', message: 'Workspace files are operator-only through this API.' }
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 33 } })
    await flushPromises()

    expect(component.find('[data-testid="workspace-backup"]').exists()).toBe(false)
  })
})
