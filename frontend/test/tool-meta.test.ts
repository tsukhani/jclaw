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
    actionCount: 1,
  },
  {
    name: 'web_fetch',
    category: 'Web',
    icon: 'globe',
    shortDescription: 'Fetch any URL.',
    actionCount: 2,
  },
  {
    // Stand-in utility tool for the fixture — exercises the composable's
    // handling of a generic Utilities-category entry (pill colors etc.),
    // not a specific tool name.
    name: 'introspect',
    category: 'Utilities',
    icon: 'wrench',
    shortDescription: 'Stub utility tool used only by this fixture.',
    actionCount: 1,
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

  it('carries each tool\'s action count from the catalog (JF-142)', async () => {
    const { TOOL_META } = await freshComposable()
    expect(TOOL_META.value.exec!.actionCount).toBe(1)
    expect(TOOL_META.value.web_fetch!.actionCount).toBe(2)
  })

  it('loadDetail reads one tool\'s description and actions from its own route', async () => {
    const detail = { ...FIXTURE_META[1], actionCount: undefined, description: 'Fetch any URL, in full.', actions: [{ name: 'fetch', description: 'Fetch.' }] }
    let reads = 0
    const off = registerEndpoint('/api/tools/web_fetch', () => {
      reads++
      return detail
    })
    try {
      const { loadDetail } = await freshComposable()
      expect(await loadDetail('web_fetch')).toEqual(JSON.parse(JSON.stringify(detail)))
      expect(reads).toBe(1)
    }
    finally {
      off()
    }
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
