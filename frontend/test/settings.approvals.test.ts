import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sectionGroups } from '~/components/settings/sections'

/**
 * Page-level tests for the Tool Approvals section (JCLAW-1022).
 *
 * The policy is instance-wide and has no UI anywhere else, so these also pin that it
 * reads back what is stored and that a conf-capped save surfaces the backend's reason
 * rather than silently reverting.
 */
interface GrantSummary {
  totalGrants: number
  agentsWithGrants: number
  agents: Array<{ agentId: number, agentName: string, tools: string[] }>
}

const NO_GRANTS: GrantSummary = { totalGrants: 0, agentsWithGrants: 0, agents: [] }

function baseEndpoints(
  configEntries: Array<{ key: string, value: string }> = [],
  summary: GrantSummary = NO_GRANTS,
) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/config', () => ({
    entries: configEntries.map(e => ({ ...e, updatedAt: '2026-08-17T00:00:00Z' })),
  }))
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/tool-approvals/summary', () => summary)
}

async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Tool Approvals', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('files the section under Security, ahead of the two specific mechanisms', () => {
    // Placement is the decision this section exists to record: the policy also governs
    // whether a coding-harness subagent may launch, so it must not live inside Shell.
    const security = sectionGroups.find(g => g.label === 'Security')
    expect(security).toBeTruthy()
    const ids = security!.sections.map(s => s.id)
    expect(ids).toEqual(['approvals', 'shell', 'malware'])
  })

  it('defaults to allow when no row is stored, matching the backend default', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const select = component.find('[data-testid="approval-off-channel-policy"]')
    expect(select.exists()).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('allow')
  })

  async function choosePolicy(component: Awaited<ReturnType<typeof mountSettingsSection>>, value: string) {
    await component.find('[data-testid="approval-off-channel-policy"]').setValue(value)
    await flushPromises()
    await flushPromises()
    return component.find('[data-testid="approval-policy-error"]')
  }

  it('shows the reason a capped policy is refused and puts the select back (JCLAW-1221)', async () => {
    baseEndpoints([{ key: 'tool.approval.offChannelPolicy', value: 'ask' }])
    registerEndpoint('/api/config', {
      method: 'POST',
      handler: (event) => {
        setResponseStatus(event, 403)
        return { type: 'error', code: 'forbidden', message: 'tool.approval.offChannelPolicy is capped at ask by application.conf.' }
      },
    })
    const component = await mountSettingsSection('approvals')

    const error = await choosePolicy(component, 'allow')

    expect(error.text()).toBe('tool.approval.offChannelPolicy is capped at ask by application.conf.')
    const select = component.find('[data-testid="approval-off-channel-policy"]')
    expect((select.element as HTMLSelectElement).value).toBe('ask')
  })

  it('names the request when a proxy answers with a page instead of the error envelope (JCLAW-1221)', async () => {
    baseEndpoints([{ key: 'tool.approval.offChannelPolicy', value: 'ask' }])
    registerEndpoint('/api/config', {
      method: 'POST',
      handler: (event) => {
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    const component = await mountSettingsSection('approvals')

    const shown = (await choosePolicy(component, 'deny')).text()

    expect(shown).toContain('/api/config')
    expect(shown).toContain('502')
    expect(shown).not.toBe('Could not save the approval policy.')
  })

  it('renders the stored policy', async () => {
    baseEndpoints([{ key: 'tool.approval.offChannelPolicy', value: 'deny' }])
    const component = await mountSettingsSection('approvals')

    const select = component.find('[data-testid="approval-off-channel-policy"]')
    expect((select.element as HTMLSelectElement).value).toBe('deny')
  })

  it('offers exactly allow, ask and deny', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const values = component.findAll('[data-testid="approval-off-channel-policy"] option')
      .map(o => (o.element as HTMLOptionElement).value)
    expect(values).toEqual(['allow', 'ask', 'deny'])
  })

  it('reports when nothing holds a standing grant', async () => {
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const rollup = component.find('[data-testid="standing-grants-rollup"]')
    expect(rollup.exists()).toBe(true)
    expect(rollup.text()).toContain('No standing grants')
  })

  it('rolls up standing grants across agents and links to each', async () => {
    // The sweep this panel exists for: grants predating JCLAW-1061 are still in force
    // and mostly unnecessary, and no per-agent page can tell you whether any remain.
    baseEndpoints([], {
      totalGrants: 3,
      agentsWithGrants: 2,
      agents: [
        { agentId: 1, agentName: 'main', tools: ['exec', 'browser'] },
        { agentId: 2, agentName: 'scout', tools: ['exec'] },
      ],
    })
    const component = await mountSettingsSection('approvals')

    const rollup = component.find('[data-testid="standing-grants-rollup"]')
    expect(rollup.text()).toContain('3 standing grants across 2 agents')
    expect(rollup.text()).toContain('main')
    expect(rollup.text()).toContain('scout')
    // Revoke is deliberately not here — it belongs on the agent's own page.
    expect(rollup.text()).toContain('Revoke from the agent')
    expect(rollup.findAll('button')).toHaveLength(0)

    const links = rollup.findAll('a').map(a => a.attributes('href'))
    expect(links).toContain('/agents/main')
    expect(links).toContain('/agents/scout')
  })

  it('says a prompt still reaches you when someone else asks', async () => {
    // The question this panel most has to answer: does allow switch off Telegram's
    // approval panel? Not for anyone but you — JCLAW-1061 skips the prompt only for a
    // sender the channel proved is the owner, and the copy has to draw that line.
    baseEndpoints()
    const component = await mountSettingsSection('approvals')

    const text = component.text()
    expect(text).toContain('cannot reach you for approval')
    expect(text).toMatch(/someone else messages the agent on Telegram or Slack/)
    expect(text).toMatch(/asked there regardless of this setting/)
  })
})
