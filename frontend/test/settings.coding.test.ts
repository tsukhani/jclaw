import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsCodingPanel from '~/components/settings/SettingsCodingPanel.vue'
import { useProvideSettingsConfig } from '~/composables/useSettingsConfig'

/**
 * The Coding settings panel: ACP harness auto-detection, custom-harness
 * registration, the harness model override, and the read-only preview of what a
 * runtime="acp" spawn actually launches. The panel injects the page-provided
 * settings-config context, so it's wrapped in a provider harness to mount
 * standalone. Covers: detected harnesses render as chips (available clickable,
 * missing disabled); one click fills BOTH subagent.acp.command and
 * subagent.acp.harness; a submitted custom command is probed and, when its
 * binary resolves, becomes a new chip (else an inline error); removing a custom
 * chip deletes it; the model override writes both acp model keys; and the launch
 * preview shows the per-harness flags without them being stored in the command.
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

interface CommandPreview {
  command: string
  harness: string
  effective: string
  env: string[]
  rejection: string | null
  acpAdapter: boolean
}

const Harness = defineComponent({
  setup() {
    useProvideSettingsConfig()
    return () => h(SettingsCodingPanel)
  },
})

let harnesses: DetectedHarness[] = []
let preview: CommandPreview
let configPosts: Record<string, unknown>[] = []
let addedCommands: string[] = []
let removedCommands: string[] = []

let configDeletes: string[] = []

// One configured provider so the acp.model picker has a provider::model option.
registerEndpoint('/api/config', {
  method: 'GET',
  handler: () => ({
    entries: [
      { key: 'subagent.acp.command', value: 'claude -p' },
      { key: 'provider.ollama.baseUrl', value: 'http://localhost:11434/v1' },
      { key: 'provider.ollama.models', value: JSON.stringify([{ id: 'qwen3.5:9b', name: 'Qwen 3.5 9B' }]) },
    ],
  }),
})
registerEndpoint('/api/config/subagent.acp.modelProvider', {
  method: 'DELETE',
  handler: () => {
    configDeletes.push('subagent.acp.modelProvider')
    return { status: 'ok' }
  },
})
registerEndpoint('/api/config/subagent.acp.modelId', {
  method: 'DELETE',
  handler: () => {
    configDeletes.push('subagent.acp.modelId')
    return { status: 'ok' }
  },
})
registerEndpoint('/api/config', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    configPosts.push(await readBody(event) as Record<string, unknown>)
    return { status: 'ok' }
  },
})
registerEndpoint('/api/providers', () => [])
registerEndpoint('/api/subagents/acp-harnesses', { method: 'GET', handler: () => ({ harnesses }) })
registerEndpoint('/api/subagents/acp-command', { method: 'GET', handler: () => preview })
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
  configDeletes = []
  addedCommands = []
  removedCommands = []
  harnesses = [
    { id: 'claude', name: 'Claude Code', command: 'claude -p', harness: 'claude', available: true, reason: 'available', custom: false, acpSupport: 'adapter-missing', acpDetail: 'Needs the claude-code-acp adapter' },
    { id: 'codex', name: 'Codex', command: 'codex exec', harness: 'codex', available: false, reason: 'codex not found on PATH', custom: false, acpSupport: 'none', acpDetail: 'No ACP — runs via the stdin/stdout wrapper' },
  ]
  // No model override: the launch is the configured command verbatim.
  preview = { command: 'claude -p', harness: 'claude', effective: 'claude -p', env: [], rejection: null, acpAdapter: false }
})

describe('SettingsCodingPanel — ACP harness detection', () => {
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

describe('SettingsCodingPanel — acp harness model override', () => {
  it('offers the configured provider models and POSTs both acp model keys on pick', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    const select = c.find('select[aria-label="ACP harness model"]')
    expect(select.exists()).toBe(true)
    const values = select.findAll('option').map(o => (o.element as HTMLOptionElement).value)
    expect(values).toEqual(['', 'ollama::qwen3.5:9b'])

    await select.setValue('ollama::qwen3.5:9b')
    await vi.waitFor(() => expect(configPosts.length).toBe(2))
    expect(configPosts).toContainEqual({ key: 'subagent.acp.modelProvider', value: 'ollama' })
    expect(configPosts).toContainEqual({ key: 'subagent.acp.modelId', value: 'qwen3.5:9b' })
  })

  it('picking the harness default DELETEs both acp model keys', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('select[aria-label="ACP harness model"]').setValue('')
    await vi.waitFor(() => expect(configDeletes.length).toBe(2))
    expect(configDeletes).toEqual(['subagent.acp.modelProvider', 'subagent.acp.modelId'])
    expect(configPosts).toEqual([])
  })
})

describe('SettingsCodingPanel — effective launch preview', () => {
  it('stays hidden while the launch is the configured command verbatim', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.find('[data-testid="acp-launch-preview"]').exists()).toBe(false)
  })

  it('shows the per-harness model flags and the env the override travels in', async () => {
    preview = {
      command: 'claude -p',
      harness: 'claude',
      effective: 'claude -p --model qwen3.5:9b',
      env: ['ANTHROPIC_MODEL', 'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN'],
      rejection: null,
      acpAdapter: false,
    }
    const c = await mountSuspended(Harness)
    await flushPromises()

    const row = c.find('[data-testid="acp-launch-preview"]')
    expect(row.exists()).toBe(true)
    expect(row.text()).toContain('claude -p --model qwen3.5:9b')
    // The harness id is what picks --model over -m, so it's named beside the command.
    expect(row.text()).toContain('harness claude')
    expect(row.text()).toContain('ANTHROPIC_BASE_URL')
    // The flags are appended at launch, never written back into the command.
    expect(configPosts).toEqual([])
  })

  it('says when real ACP over stdio replaces the configured command', async () => {
    preview = {
      command: 'claude -p', harness: 'claude', effective: 'claude-agent-acp',
      env: ['ANTHROPIC_MODEL'], rejection: null, acpAdapter: true,
    }
    const c = await mountSuspended(Harness)
    await flushPromises()

    const row = c.find('[data-testid="acp-launch-preview"]')
    expect(row.text()).toContain('claude-agent-acp')
    expect(row.text()).toContain('replaces the command above')
  })

  it('surfaces a harness that cannot take the override', async () => {
    preview = {
      command: 'opencode run', harness: 'opencode', effective: 'opencode run', env: [],
      rejection: 'harness \'opencode\' takes no model override from JClaw; set its model in the harness\'s own configuration.',
      acpAdapter: false,
    }
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.find('[data-testid="acp-launch-preview"]').text()).toContain('takes no model override')
  })
})
