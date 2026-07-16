import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import SettingsSubagentsPanel from '~/components/settings/SettingsSubagentsPanel.vue'
import { useProvideSettingsConfig } from '~/composables/useSettingsConfig'

/**
 * ACP harness auto-detection in the Subagents settings panel. The panel injects
 * the page-provided settings-config context, so it's wrapped in a provider
 * harness to mount standalone. Covers: detected harnesses render as chips
 * (available clickable, missing disabled), and one click fills BOTH
 * subagent.acp.command and subagent.acp.harness.
 */
const Harness = defineComponent({
  setup() {
    useProvideSettingsConfig()
    return () => h(SettingsSubagentsPanel)
  },
})

let harnesses: unknown[] = []
let configPosts: Record<string, unknown>[] = []

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
registerEndpoint('/api/subagents/acp-harnesses', () => ({ harnesses }))

beforeEach(() => {
  clearNuxtData()
  configPosts = []
  harnesses = [
    { id: 'claude', name: 'Claude Code', command: 'claude -p', available: true, reason: 'available' },
    { id: 'codex', name: 'Codex', command: 'codex exec', available: false, reason: 'codex not found on PATH' },
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

  it('clicking an available harness POSTs both acp.command and acp.harness', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    const claude = c.findAll('button').find(b => b.text().includes('Claude Code'))!
    await claude.trigger('click')
    await vi.waitFor(() => expect(configPosts.length).toBe(2))

    expect(configPosts).toContainEqual({ key: 'subagent.acp.command', value: 'claude -p' })
    expect(configPosts).toContainEqual({ key: 'subagent.acp.harness', value: 'claude' })
  })
})
