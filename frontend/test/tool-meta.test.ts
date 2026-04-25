import { describe, it, expect, beforeEach, vi } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'

const FIXTURE_META = [
  {
    // JCLAW-172: shell.enabled is gone — exec registers unconditionally and
    // its requiresConfig hint is null. Per-agent enable still lives on the
    // Tools page via AgentToolConfig.
    name: 'exec',
    category: 'System',
    icon: 'terminal',
    shortDescription: 'Execute shell commands on the host system.',
    system: false,
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
    // Stand-in system tool for the fixture. Doesn't need to match any real
    // registered tool — the test exercises the composable's handling of a
    // system=true entry (pill colors etc.), not a specific tool name.
    name: 'introspect',
    category: 'Utilities',
    icon: 'wrench',
    shortDescription: 'Stub system tool used only by this fixture.',
    system: true,
    actions: [{ name: 'inspect', description: 'Inspect something' }],
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
    expect(TOOL_META.value.exec!.category).toBe('System')
    expect(TOOL_META.value.exec!.icon).toBe('terminal')
    // JCLAW-172: requiresConfig is no longer set on exec; the previous
    // shell.enabled gate is gone.
    expect(TOOL_META.value.exec!.requiresConfig).toBeUndefined()
    expect(TOOL_META.value.web_fetch!.category).toBe('Web')
  })

  it('derives Tailwind class names from category on the client side', async () => {
    const { TOOL_META } = await freshComposable()
    expect(TOOL_META.value.exec!.iconBg).toContain('neutral')
    expect(TOOL_META.value.web_fetch!.iconBg).toContain('blue')
    expect(TOOL_META.value.introspect!.iconBg).toContain('emerald')
  })

  it('preserves the legacy "functions" alias for pages/tools.vue compatibility', async () => {
    const { TOOL_META } = await freshComposable()
    expect(TOOL_META.value.exec!.functions).toEqual(TOOL_META.value.exec!.actions)
  })

  it('getPillClass returns the right category pill classes', async () => {
    const { getPillClass } = await freshComposable()
    expect(getPillClass('exec')).toContain('neutral')
    expect(getPillClass('web_fetch')).toContain('blue')
    expect(getPillClass('introspect')).toContain('emerald')
  })

  it('ORDERED_TOOLS follows the backend registry iteration order', async () => {
    const { ORDERED_TOOLS } = await freshComposable()
    expect(ORDERED_TOOLS.value).toEqual(['exec', 'web_fetch', 'introspect'])
  })

  it('getToolMeta returns null for unknown tool names', async () => {
    const { getToolMeta } = await freshComposable()
    expect(getToolMeta('does-not-exist')).toBeNull()
  })
})
