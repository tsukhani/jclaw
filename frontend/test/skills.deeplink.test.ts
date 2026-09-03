import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Skills from '~/pages/skills/[[name]].vue'

/**
 * URL-addressable skill detail: /skills lists, /skills/<name> opens that skill.
 *
 * The folder name is the address because it is what the API path already uses, so
 * the URL and the endpoint agree without a lookup table.
 *
 * The route is the source of truth, so two arrival paths have to work and they are
 * not the same code path:
 *
 *   - the name is already in the URL when the page mounts (deep link, reload, or a
 *     link from another page) — only the watcher's `immediate` run sees it;
 *   - the name changes while already on the page — nothing remounts, so only the
 *     watcher proper fires.
 *
 * Agent workspace skills are deliberately absent from this namespace: they are
 * scoped to an agent, so /skills/<name> would be ambiguous for them.
 */

function setupApi() {
  // The global auth middleware probes /api/config on every navigation; without it
  // checkAuth fails and each push lands on /login instead of the page under test.
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/skills', () => [
    { name: 'web-search', folderName: 'web-search', description: 'Search the web', version: '1.0.0' },
    { name: 'code-review', folderName: 'code-review', description: 'Review code changes', version: '0.2.0' },
  ])
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
  ])
  registerEndpoint('/api/agents/1/skills', () => [])
  for (const name of ['web-search', 'code-review']) {
    registerEndpoint(`/api/skills/${name}/files`, () => ({
      files: [{ path: 'SKILL.md', isText: true, size: 12 }],
      tools: [],
      commands: [],
      author: '',
    }))
    registerEndpoint(`/api/skills/${name}/files/SKILL.md`, () => ({ content: `# ${name}` }))
  }
}

// Without this, the previous case's Skills instance stays mounted and alive. Two
// live pages both watch the route; the older one reacts too and the case under
// test races against it.
enableAutoUnmount(afterEach)

beforeEach(async () => {
  // useFetch caches by URL across mounts; clear so each case refetches.
  clearNuxtData()
  // The router is shared across cases in this file, so a case that navigated would
  // otherwise leak its URL into the next one's mount.
  await useRouter().replace('/skills')
})

/** The viewer is the only place the "Back to skills" control renders. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
const viewerOpen = (component: any): boolean => component.text().includes('Back to skills')

describe('Skills page — URL-addressable skill detail', () => {
  // Mounting the component with a `route` proves the page reacts to the param; it
  // does not prove Nuxt maps the URL to this page at all. Without this, a filename
  // that stopped generating the optional-param route would leave every other case
  // here green while /skills/web-search 404s in the browser.
  it('registers /skills and /skills/<name> as routes served by this page', () => {
    const router = useRouter()
    for (const path of ['/skills', '/skills/web-search']) {
      const matched = router.resolve(path).matched
      expect(matched.length, `${path} should match a route`).toBeGreaterThan(0)
    }
    expect(router.resolve('/skills/web-search').matched[0]?.components?.default)
      .toBe(router.resolve('/skills').matched[0]?.components?.default)
  })

  it('opens the skill named in the URL when the page mounts there', async () => {
    setupApi()
    const component = await mountSuspended(Skills, { route: '/skills/web-search' })
    await flushPromises()

    expect(viewerOpen(component)).toBe(true)
    expect(component.text()).toContain('web-search')
  })

  it('matches the name case-insensitively so a hand-typed URL still lands', async () => {
    setupApi()
    const component = await mountSuspended(Skills, { route: '/skills/WEB-SEARCH' })
    await flushPromises()

    expect(viewerOpen(component)).toBe(true)
  })

  it('opens the skill when the URL changes while already on the page', async () => {
    setupApi()
    const component = await mountSuspended(Skills, { route: '/skills' })
    await flushPromises()
    expect(viewerOpen(component)).toBe(false)

    await useRouter().push('/skills/web-search')
    await flushPromises()

    expect(viewerOpen(component)).toBe(true)
  })

  it('returns the URL to /skills when the viewer is closed, so the same skill can be re-picked', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Skills, { route: '/skills/web-search' })
    await flushPromises()
    expect(viewerOpen(component)).toBe(true)

    // Closing has to move the URL too: if it stayed at /skills/web-search with the
    // viewer shut, re-picking that skill would be a same-URL push the watcher never
    // sees.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
    const back = component.findAll('button').find((b: any) => b.text().includes('Back to skills'))
    expect(back, 'the viewer should offer a way back to the listing').toBeTruthy()
    await back!.trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/skills'))
    expect(viewerOpen(component)).toBe(false)

    await router.push('/skills/web-search')
    await flushPromises()
    expect(viewerOpen(component)).toBe(true)
  })

  it('keeps the viewer open when the shared breadcrumb ref is cleared', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Skills, { route: '/skills/web-search' })
    await flushPromises()
    expect(viewerOpen(component)).toBe(true)

    // A global skill closes through the route, not through this ref. Treating the
    // ref going null as "close the viewer" would fight the route watcher and bounce
    // an opening skill straight back to /skills.
    useBreadcrumbExtra().value = null
    await flushPromises()

    expect(viewerOpen(component)).toBe(true)
    expect(router.currentRoute.value.fullPath).toBe('/skills/web-search')
  })

  it('falls back to the listing for a name that matches no skill', async () => {
    setupApi()
    const router = useRouter()
    const component = await mountSuspended(Skills, { route: '/skills/nothing-here' })
    await flushPromises()

    expect(viewerOpen(component)).toBe(false)
    // The listing is still rendered — an unknown name must not strand the page on
    // an empty viewer — and the URL is corrected rather than left lying.
    expect(component.text()).toContain('web-search')
    await vi.waitFor(() => expect(router.currentRoute.value.fullPath).toBe('/skills'))
  })
})
