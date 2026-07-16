import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsSubagentsPanel from '~/components/settings/SettingsSubagentsPanel.vue'
import { useProvideSettingsConfig } from '~/composables/useSettingsConfig'

/**
 * ACP harness auto-detection + custom-harness registration in the Subagents
 * settings panel. The panel injects the page-provided settings-config context,
 * so it's wrapped in a provider harness to mount standalone. Covers: detected
 * harnesses render as chips (available clickable, missing disabled); one click
 * fills BOTH subagent.acp.command and subagent.acp.harness; a submitted custom
 * command is probed and, when its binary resolves, becomes a new chip (else an
 * inline error); removing a custom chip deletes it.
 */
interface DetectedHarness {
  id: string
  name: string
  command: string
  harness: string
  available: boolean
  reason: string
  custom: boolean
  acpSupport: string
  acpDetail: string
}

const Harness = defineComponent({
  setup() {
    useProvideSettingsConfig()
    return () => h(SettingsSubagentsPanel)
  },
})

let harnesses: DetectedHarness[] = []
let configPosts: Record<string, unknown>[] = []
let addedCommands: string[] = []
let removedCommands: string[] = []

registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: [] }) })
registerEndpoint('/api/config', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    configPosts.push(await readBody(event) as Record<string, unknown>)
    return { status: 'ok' }
  },
})
registerEndpoint('/api/providers', () => [])
registerEndpoint('/api/agents', () => [
  { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openai', modelId: 'gpt-4.1' },
])
registerEndpoint('/api/subagents/acp-harnesses', { method: 'GET', handler: () => ({ harnesses }) })
registerEndpoint('/api/subagents/acp-harnesses', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    const cmd = (await readBody(event) as { command: string }).command
    addedCommands.push(cmd)
    const available = cmd.includes('good')
    const chip: DetectedHarness = {
      id: `custom:${cmd}`, name: cmd.split(/\s+/)[0]!, command: cmd, harness: 'generic',
      available, reason: available ? 'available' : 'binary not found on PATH', custom: true,
      acpSupport: 'none', acpDetail: '',
    }
    if (available) harnesses.push(chip)
    return chip
  },
})
registerEndpoint('/api/subagents/acp-harnesses', {
  method: 'DELETE',
  handler: async (event) => {
    const { getQuery } = await import('h3')
    const cmd = getQuery(event).command as string
    removedCommands.push(cmd)
    harnesses = harnesses.filter(x => x.command !== cmd)
    return { harnesses }
  },
})

beforeEach(() => {
  clearNuxtData()
  configPosts = []
  addedCommands = []
  removedCommands = []
  harnesses = [
    { id: 'claude', name: 'Claude Code', command: 'claude -p', harness: 'claude', available: true, reason: 'available', custom: false, acpSupport: 'adapter-missing', acpDetail: 'Needs the claude-code-acp adapter' },
    { id: 'codex', name: 'Codex', command: 'codex exec', harness: 'codex', available: false, reason: 'codex not found on PATH', custom: false, acpSupport: 'none', acpDetail: 'No ACP — runs via the stdin/stdout wrapper' },
  ]
})

describe('SettingsSubagentsPanel — ACP harness detection', () => {
  it('renders a chip per detected harness; available is clickable, missing is disabled', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    const claude = c.findAll('button').find(b => b.text().includes('Claude Code'))!
    const codex = c.findAll('button').find(b => b.text().includes('Codex'))!
    expect(claude).toBeTruthy()
    expect(claude.text()).toContain('claude -p')
    expect(claude.attributes('disabled')).toBeUndefined()
    expect(codex.attributes('disabled')).toBeDefined()
  })

  it('badges each chip with its ACP support', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    // claude → adapter-missing → the "ACP · adapter" badge; codex → none → stdin/stdout.
    expect(c.find('[data-testid="acp-badge-claude"]').text()).toBe('ACP · adapter')
    expect(c.find('[data-testid="acp-badge-claude"]').attributes('title')).toContain('claude-code-acp')
    expect(c.find('[data-testid="acp-badge-codex"]').text()).toBe('stdin/stdout')
  })

  it('clicking an available harness POSTs both acp.command and acp.harness', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    const claude = c.findAll('button').find(b => b.text().includes('Claude Code'))!
    await claude.trigger('click')
    await vi.waitFor(() => expect(configPosts.length).toBe(2))

    expect(configPosts).toContainEqual({ key: 'subagent.acp.command', value: 'claude -p' })
    expect(configPosts).toContainEqual({ key: 'subagent.acp.harness', value: 'claude' })
  })

  it('adding a resolvable custom command probes it and shows a new chip', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    const input = c.find('input[aria-label="Custom harness command"]')
    await input.setValue('good-tool run')
    await c.findAll('button').find(b => b.text().trim() === 'Add')!.trigger('click')
    await vi.waitFor(() => expect(addedCommands).toContain('good-tool run'))
    await flushPromises()

    // The new chip is now listed after the refresh.
    expect(c.findAll('button').some(b => b.text().includes('good-tool run'))).toBe(true)
  })

  it('adding an unresolved custom command shows an inline error and no chip', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    const input = c.find('input[aria-label="Custom harness command"]')
    await input.setValue('missing-tool run')
    await c.findAll('button').find(b => b.text().trim() === 'Add')!.trigger('click')
    await vi.waitFor(() => expect(addedCommands).toContain('missing-tool run'))
    await flushPromises()

    expect(c.text()).toContain('binary not found on PATH')
    expect(c.findAll('button').some(b => b.text().includes('missing-tool run'))).toBe(false)
  })

  it('removing a custom chip deletes it', async () => {
    harnesses.push({
      id: 'custom:aider --message', name: 'aider', command: 'aider --message', harness: 'generic',
      available: true, reason: 'available', custom: true, acpSupport: 'none', acpDetail: '',
    })
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button[aria-label="Remove custom harness"]').trigger('click')
    await vi.waitFor(() => expect(removedCommands).toContain('aider --message'))
    await flushPromises()

    expect(c.findAll('button').some(b => b.text().includes('aider --message'))).toBe(false)
  })
})
