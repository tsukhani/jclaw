import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import AgentToolApprovals from '~/components/AgentToolApprovals.vue'

/**
 * JCLAW-1062 — the per-agent list/revoke surface for standing tool approvals.
 *
 * Revoke lives here rather than in Settings because a grant is per-agent. The
 * assertions worth having are that a grant is visible at all (until this ticket the
 * only way to see one was to watch the event log after the fact) and that revoking
 * re-reads the list, so a stale row cannot linger on screen looking still-granted.
 */
describe('AgentToolApprovals', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('lists the agent\'s standing grants', async () => {
    registerEndpoint('/api/agents/7/tool-approvals', () => [
      { id: 1, toolName: 'exec' },
      { id: 2, toolName: 'mcp_google-workspace-mcp' },
    ])

    const component = await mountSuspended(AgentToolApprovals, { props: { agentId: 7 } })
    await flushPromises()

    expect(component.text()).toContain('exec')
    expect(component.text()).toContain('mcp_google-workspace-mcp')
  })

  it('says so plainly when nothing is granted', async () => {
    registerEndpoint('/api/agents/8/tool-approvals', () => [])

    const component = await mountSuspended(AgentToolApprovals, { props: { agentId: 8 } })
    await flushPromises()

    expect(component.text()).toContain('Every dangerous action is prompted')
  })

  it('revokes a grant and re-reads the list', async () => {
    let deleted = false
    registerEndpoint('/api/agents/9/tool-approvals', () => (deleted ? [] : [{ id: 1, toolName: 'exec' }]))
    registerEndpoint('/api/agents/9/tool-approvals/exec', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { ok: true }
      },
    })

    const component = await mountSuspended(AgentToolApprovals, { props: { agentId: 9 } })
    await flushPromises()
    expect(component.text()).toContain('exec')

    await component.find('[data-testid="revoke-exec"]').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(deleted).toBe(true)
    expect(component.text()).toContain('Every dangerous action is prompted')
  })

  // JCLAW-1131: a refused revoke used to be dropped on the floor — `mutate` returned null
  // and nothing was rendered, so the grant stayed on screen looking revoked-in-progress.
  it('reports a refused revoke with the three parts the backend supplied', async () => {
    registerEndpoint('/api/agents/11/tool-approvals', () => [{ id: 1, toolName: 'exec' }])
    registerEndpoint('/api/agents/11/tool-approvals/exec', {
      method: 'DELETE',
      handler: (event) => {
        setResponseStatus(event, 403)
        return {
          type: 'error',
          code: 'operator_only',
          message: 'Only the operator may revoke a grant.',
          template: {
            whatBroke: 'Only the operator can make this change — an agent cannot.',
            whatToCheck: 'This is a deliberate boundary.',
            howToRetry: 'Make the change yourself in the admin UI.',
          },
        }
      },
    })

    const component = await mountSuspended(AgentToolApprovals, { props: { agentId: 11 } })
    await flushPromises()
    await component.find('[data-testid="revoke-exec"]').trigger('click')
    await flushPromises()

    const alert = component.find('[data-testid="api-error"]')
    expect(alert.text()).toContain('Only the operator may revoke a grant.')
    expect(alert.text()).toContain('Make the change yourself in the admin UI.')
  })

  it('warns that a grant ignores which channel the agent is reached on', async () => {
    // The hazard JCLAW-1062 exists for: a grant made while an agent was DM-only keeps
    // applying after it joins a group, where the prompt was the only guest barrier.
    registerEndpoint('/api/agents/10/tool-approvals', () => [{ id: 1, toolName: 'exec' }])

    const component = await mountSuspended(AgentToolApprovals, { props: { agentId: 10 } })
    await flushPromises()

    expect(component.text()).toMatch(/no channel dimension/)
  })
})
