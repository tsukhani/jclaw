import { describe, it, expect, beforeEach, vi } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'

const FIXTURE_META = [
  {
    name: 'exec',
    category: 'System',
    icon: 'terminal',
    shortDescription: 'Execute shell commands on the host system.',
    system: false,
    requiresConfig: 'shell.enabled',
    actions: [{ name: 'exec', description: 'Run a shell command' }],
  },
  {
    name: 'web_fetch',
    category: 'Web',
    icon: 'globe',
    shortDescription: 'Fetch any URL.',
    system: false,
    actions: [{ name: 'fetch (text)', description: 'Extract text' }],
  },
  {
    name: 'skills',
    category: 'Utilities',
    icon: 'skills',
    shortDescription: 'Runtime introspection.',
    system: true,
    actions: [{ name: 'listTools', description: 'List tools' }],
  },
]

async function freshComposable() {
  // Reset the Vite/vitest module cache so the composable's module-level
  // `metaList` ref is re-initialised fresh on every test. Without this, the
  // first test's fixture sticks around and breaks later assertions.
  vi.resetModules()
  const mod = await import('~/composables/useToolMeta')
  const inst = mod.useToolMeta()
  await inst.ready()
  await flushPromises()
  return inst
}

beforeEach(() => {
  registerEndpoint('/api/tools/meta', () => FIXTURE_META)
})

describe('useToolMeta (JCLAW-72 backend-driven metadata)', () => {
  it('fetches from /api/tools/meta and exposes tools keyed by name', async () => {
    const { TOOL_META } = await freshComposable()
    expect(TOOL_META.value.exec).toBeDefined()
    expect(TOOL_META.value.exec.category).toBe('System')
    expect(TOOL_META.value.exec.icon).toBe('terminal')
    expect(TOOL_META.value.exec.requiresConfig).toBe('shell.enabled')
    expect(TOOL_META.value.web_fetch.category).toBe('Web')
  })

  it('derives Tailwind class names from category on the client side', async () => {
    const { TOOL_META } = await freshComposable()
    expect(TOOL_META.value.exec.iconBg).toContain('neutral')
    expect(TOOL_META.value.web_fetch.iconBg).toContain('blue')
    expect(TOOL_META.value.skills.iconBg).toContain('emerald')
  })

  it('preserves the legacy "functions" alias for pages/tools.vue compatibility', async () => {
    const { TOOL_META } = await freshComposable()
    expect(TOOL_META.value.exec.functions).toEqual(TOOL_META.value.exec.actions)
  })

  it('getPillClass returns the right category pill classes', async () => {
    const { getPillClass } = await freshComposable()
    expect(getPillClass('exec')).toContain('neutral')
    expect(getPillClass('web_fetch')).toContain('blue')
    expect(getPillClass('skills')).toContain('emerald')
  })

  it('ORDERED_TOOLS follows the backend registry iteration order', async () => {
    const { ORDERED_TOOLS } = await freshComposable()
    expect(ORDERED_TOOLS.value).toEqual(['exec', 'web_fetch', 'skills'])
  })

  it('getToolMeta returns null for unknown tool names', async () => {
    const { getToolMeta } = await freshComposable()
    expect(getToolMeta('does-not-exist')).toBeNull()
  })
})
