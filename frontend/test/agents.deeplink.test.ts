import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Agents from '~/pages/agents/[[name]].vue'

/**
 * URL-addressable agent detail: /agents lists, /agents/<name> opens that agent.
 *
 * The name is the address rather than the row id — the API constrains names to
 * ^\w[\w-]{0,63}$ and enforces uniqueness, so they are safe URL segments with no
 * encoding, and they read.
 *
 * The route is the source of truth, so two arrival paths have to work and they
 * are not the same code path:
 *
 *   - the name is already in the URL when the page mounts (deep link, reload, or
 *     the command palette arriving from another page) — only the watcher's
 *     `immediate` run sees it;
 *   - the name changes while already on the page — nothing remounts, so only the
 *     watcher proper fires.
 */

function setupApi() {
  registerEndpoint('/api/agents', () => [
    {
      id: 1,
      name: 'main',
      modelProvider: 'ollama-cloud',
      modelId: 'kimi-k2.5',
      enabled: true,
      isMain: true,
      providerConfigured: true,
      thinkingMode: null,
      compressionEnabled: true,
      compressionJson: true,
      compressionCode: true,
      compressionText: true,
      compressionTargetRatio: 0.3,
      acpAllowed: false,
      memoryAutocaptureEnabled: true,
      memoryAutocaptureModelInherited: true,
      memoryAutocaptureProvider: 'ollama-cloud',
      memoryAutocaptureModel: 'kimi-k2.5',
      createdAt: '2026-04-01T10:00:00Z',
      updatedAt: '2026-04-22T10:00:00Z',
    },
    {
      id: 2,
      name: 'helper',
      modelProvider: 'openai',
      modelId: 'gpt-4',
      enabled: true,
      isMain: false,
      providerConfigured: true,
      thinkingMode: null,
      compressionEnabled: false,
      compressionJson: false,
      compressionCode: false,
      compressionText: false,
      compressionTargetRatio: 0.3,
      acpAllowed: false,
      memoryAutocaptureEnabled: true,
      memoryAutocaptureModelInherited: true,
      memoryAutocaptureProvider: 'openai',
      memoryAutocaptureModel: 'gpt-4',
      createdAt: '2026-04-10T10:00:00Z',
      updatedAt: '2026-04-20T10:00:00Z',
    },
  ])
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/tools/meta', () => [])
  registerEndpoint('/api/agents/2/tools', () => [])
  registerEndpoint('/api/agents/2/skills', () => [])
  registerEndpoint('/api/agents/2/shell/effective-allowlist', () => ({ global: [], bySkill: {} }))
  registerEndpoint('/api/agents/2/workspace/AGENT.md', () => ({ content: 'helper instructions' }))
  registerEndpoint('/api/config/agent.helper.shell.bypassAllowlist', () => ({ value: 'false' }))
  registerEndpoint('/api/config/agent.helper.shell.allowGlobalPaths', () => ({ value: 'false' }))
}

// Without this, the previous case's Agents instance stays mounted and alive.
// Two live pages both watch the route; the older one reacts too and the case
// under test races against it.
enableAutoUnmount(afterEach)

beforeEach(async () => {
  // useFetch caches by URL across mounts; clear so each case refetches.
  clearNuxtData()
  // The router is shared across cases in this file, so a case that navigated
  // would otherwise leak its URL into the next one's mount.
  await useRouter().replace('/agents')
})

/** The edit form is the only place an <input> carries an agent's name. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
const openAgentName = (component: any): string[] =>
  component.findAll('input').map((i: { element: HTMLInputElement }) => i.element.value)

describe('Agents page — URL-addressable agent detail', () => {
  // Mounting the component with a `route` proves the page reacts to the param;
  // it does not prove Nuxt maps the URL to this page at all. Without this, a
  // filename that stopped generating the optional-param route would leave every
  // other case here green while /agents/helper 404s in the browser.
  it('registers /agents and /agents/<name> as routes served by this page', () => {
    const router = useRouter()
    for (const path of ['/agents', '/agents/helper']) {
      const matched = router.resolve(path).matched
      expect(matched.length, `${path} should match a route`).toBeGreaterThan(0)
    }
    expect(router.resolve('/agents/helper').matched[0]?.components?.default)
      .toBe(router.resolve('/agents').matched[0]?.components?.default)
  })

  it('opens the agent named in the URL when the page mounts there', async () => {
    setupApi()
    const component = await mountSuspended(Agents, { route: '/agents/helper' })
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('matches the name case-insensitively so a hand-typed URL still lands', async () => {
    setupApi()
    const component = await mountSuspended(Agents, { route: '/agents/HELPER' })
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('opens the agent when the URL changes while already on the page', async () => {
    setupApi()
    const component = await mountSuspended(Agents, { route: '/agents' })
    await flushPromises()
    expect(openAgentName(component)).not.toContain('helper')

    await useRouter().push('/agents/helper')
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
  })

  it('returns the URL to /agents when the form is closed, so the same agent can be re-picked', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Agents, { route: '/agents/helper' })
    await flushPromises()
    expect(openAgentName(component)).toContain('helper')

    // Closing has to move the URL too: if it stayed at /agents/helper with the
    // form shut, re-picking that agent would be a same-URL push the watcher
    // never sees.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
    const back = component.findAll('button').find((b: any) => b.text().includes('Back to agents'))
    expect(back, 'the form should offer a way back to the listing').toBeTruthy()
    await back!.trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/agents'))
    expect(openAgentName(component)).not.toContain('helper')

    await router.push('/agents/helper')
    await flushPromises()
    expect(openAgentName(component)).toContain('helper')
  })

  it('keeps the edit form open when the shared breadcrumb ref is cleared', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Agents, { route: '/agents/helper' })
    await flushPromises()
    expect(openAgentName(component)).toContain('helper')

    // NuxtPage remounts the page when the param changes, and the outgoing
    // instance's onUnmounted nulls this shared ref after the incoming one has
    // already set it. Treating that as "close the form" made opening an agent
    // bounce straight back to /agents. Only the create form, which has no URL
    // of its own, reacts to this ref now.
    useBreadcrumbExtra().value = null
    await flushPromises()

    expect(openAgentName(component)).toContain('helper')
    expect(router.currentRoute.value.fullPath).toBe('/agents/helper')
  })

  it('falls back to the listing for a name that matches no agent', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Agents, { route: '/agents/nobody' })
    await flushPromises()

    expect(openAgentName(component)).not.toContain('helper')
    // The listing is still rendered — an unknown name must not strand the page
    // on an empty editor — and the URL is corrected rather than left lying.
    expect(component.text()).toContain('helper')
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/agents'))
  })
})
