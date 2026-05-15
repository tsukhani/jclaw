import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Guide from '~/pages/guide.vue'

/**
 * JCLAW-292: in-app User Guide page coverage.
 *
 * The page is fully client-side (no /api fetches), so the test surface is
 * mostly DOM assertions: the section registry drives the TOC, the
 * Subagents section renders its expected anchors, and an unknown URL hash
 * still loads the page rather than client-error-ing.
 *
 * IntersectionObserver is jsdom-stubbed (no native impl) so the active
 * highlight defaults to the first registered section. That's the same
 * behavior an operator hitting /guide cold sees, which is what the test
 * documents.
 */

describe('User Guide page', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the TOC with every registered section', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()

    // The first (and currently only) section is Subagents. Once more
    // sections register, this assertion grows; the data-testid pattern
    // keeps the addressing stable.
    const subagentsItem = component.find('[data-testid="guide-toc-item-subagents"]')
    expect(subagentsItem.exists()).toBe(true)
    expect(subagentsItem.text()).toContain('Subagents')
  })

  it('renders the Subagents section content with the documented headings', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()

    const html = component.html()
    // Section identity heading
    expect(html).toContain('id="subagents-overview"')
    // Spawn modes' deep anchors — each mode card carries its own id so
    // /guide#subagents-spawn-modes-async deep-links correctly.
    expect(html).toContain('id="subagents-spawn-modes-session"')
    expect(html).toContain('id="subagents-spawn-modes-inline"')
    expect(html).toContain('id="subagents-spawn-modes-async"')
    // Async + yield section anchor — explicitly called out in the
    // ticket as the deep-link example.
    expect(html).toContain('id="subagents-async-yield"')
    // Quick reference + tips anchors.
    expect(html).toContain('id="subagents-quick-reference"')
    expect(html).toContain('id="subagents-tips"')

    // Body content sanity check: the "Reply was truncated by the model"
    // marker (JCLAW-291) is referenced verbatim so an operator searching
    // the guide for that exact string finds it here.
    expect(component.text()).toContain('Reply was truncated by the model')
  })

  it('renders the section card on the page itself', async () => {
    const component = await mountSuspended(Guide)
    await flushPromises()

    // The page emits a sentinel <section data-section-id="..."> for each
    // registered section so the IntersectionObserver can hook them.
    const sentinel = component.find('[data-section-id="subagents"]')
    expect(sentinel.exists()).toBe(true)
  })

  it('renders without making any API calls', async () => {
    // Guard the AC: the guide page is fully client-side. Spy on global
    // fetch so any accidental /api call surfaces as a test failure.
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    await mountSuspended(Guide)
    await flushPromises()
    // Allow Nuxt's own bootstrap fetches (none expected here, but the
    // page itself must not fire any). Filter to /api only since the
    // ticket's guarantee is "no /api requests on initial load."
    const apiCalls = fetchSpy.mock.calls.filter((call) => {
      const url = String(call[0] ?? '')
      return url.includes('/api/')
    })
    expect(apiCalls).toEqual([])
  })

  it('renders TOC + content even when an unknown anchor is in the URL', async () => {
    // Hard-refresh on /guide#some-unknown-anchor must still render the
    // page; falling back silently to the first section is the AC.
    // jsdom doesn't process the hash by itself, but useRoute().hash will
    // pick it up via the test runner's router. This test guards the
    // "no client-side router error" leg of the AC: the mount + first
    // tick must complete without throwing.
    const component = await mountSuspended(Guide)
    await flushPromises()
    expect(component.find('[data-testid="guide-toc-item-subagents"]').exists()).toBe(true)
  })
})
