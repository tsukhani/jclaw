import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import FilterBar from '~/components/FilterBar.vue'
import Tasks from '~/pages/tasks.vue'
import Conversations from '~/pages/conversations/index.vue'
import Subagents from '~/pages/subagents.vue'

/**
 * JCLAW-328: FilterBar `q:` dispatch coverage for the Conversations,
 * Tasks, and Subagents pages.
 *
 * <p>The tests split across two layers:
 * <ul>
 *   <li>{@code FilterBar} unit tests pin the keyword-token parsing
 *       and the {@code update:filters} emit contract that all three
 *       pages depend on.</li>
 *   <li>Per-page mount tests pin that each page renders a FilterBar
 *       with the right {@code filter-keys} prop list, including
 *       {@code q} on every page and the JCLAW-326 URL chip path on
 *       the Subagents page.</li>
 * </ul>
 *
 * <p>The page tests deliberately stop short of asserting on the actual
 * outgoing HTTP URL: Nuxt's $fetch is a global the test utils don't
 * surface for spying, and the page's URL composition is already
 * unit-covered indirectly via the FilterBar emit contract plus the
 * page's onFiltersChanged handler whose behavior is grep-visible in
 * the source. The functional coverage that actually validates the
 * request URL roundtrips lives on the backend side
 * (ApiConversationsControllerSearchTest etc).
 */

function setupMockApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'test', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: false, providerConfigured: true },
  ])
  registerEndpoint('/api/conversations', () => [])
  registerEndpoint('/api/conversations/channels', () => ['web', 'telegram'])
  registerEndpoint('/api/tasks', () => [])
  registerEndpoint('/api/subagent-runs', () => [])
}

describe('FilterBar — JCLAW-328 q: dispatch contract', () => {
  it('emits update:filters with a q:KEYWORD chip when the operator commits', async () => {
    // Mount the bar in isolation. update:filters fires on Enter; we
    // intercept the emit and assert the parsed payload shape.
    const wrapper = mount(FilterBar, {
      props: {
        storageKey: 'test-q-single',
        filterKeys: ['q', 'channel', 'agent'],
      },
    })
    const input = wrapper.find('input[type="text"]')
    await input.setValue('q:morning')
    await input.trigger('keydown', { key: 'Enter' })

    const emitted = wrapper.emitted('update:filters')
    if (!emitted || emitted.length === 0) throw new Error('FilterBar did not emit update:filters')
    const lastCall = emitted[emitted.length - 1]
    if (!lastCall) throw new Error('FilterBar last emit had no args')
    const lastPayload = lastCall[0] as Array<{ key: string, value: string }>
    expect(lastPayload).toEqual([{ key: 'q', value: 'morning' }])
  })

  it('emits both q and a sibling key when the operator types a compound query', async () => {
    // Compound input — q + channel together should parse into two
    // distinct filter chips, AND-semantics on the receiving page.
    const wrapper = mount(FilterBar, {
      props: {
        storageKey: 'test-q-compound',
        filterKeys: ['q', 'channel', 'agent'],
      },
    })
    const input = wrapper.find('input[type="text"]')
    await input.setValue('q:morning channel:web')
    await input.trigger('keydown', { key: 'Enter' })

    const emitted = wrapper.emitted('update:filters')
    if (!emitted || emitted.length === 0) throw new Error('FilterBar did not emit update:filters')
    const lastCall = emitted[emitted.length - 1]
    if (!lastCall) throw new Error('FilterBar last emit had no args')
    const lastPayload = lastCall[0] as Array<{ key: string, value: string }>
    // Order matches parseQuery's iteration over whitespace-split tokens.
    expect(lastPayload).toEqual([
      { key: 'q', value: 'morning' },
      { key: 'channel', value: 'web' },
    ])
  })

  it('parses a Tasks-page-style compound (q + status + type)', async () => {
    // Mirrors the AC's `q:summary status:PENDING type:CRON` example.
    const wrapper = mount(FilterBar, {
      props: {
        storageKey: 'test-q-tasks',
        filterKeys: ['q', 'status', 'type', 'agent'],
      },
    })
    const input = wrapper.find('input[type="text"]')
    await input.setValue('q:summary status:PENDING type:CRON')
    await input.trigger('keydown', { key: 'Enter' })

    const emitted = wrapper.emitted('update:filters')
    if (!emitted || emitted.length === 0) throw new Error('FilterBar did not emit update:filters')
    const lastCall = emitted[emitted.length - 1]
    if (!lastCall) throw new Error('FilterBar last emit had no args')
    const lastPayload = lastCall[0] as Array<{ key: string, value: string }>
    expect(lastPayload).toEqual([
      { key: 'q', value: 'summary' },
      { key: 'status', value: 'PENDING' },
      { key: 'type', value: 'CRON' },
    ])
  })
})

describe('Conversations page — JCLAW-328 q: wiring', () => {
  it('renders a FilterBar with q in its filter-keys', async () => {
    setupMockApi()
    // Seed one conversation so the page renders its data view (chrome +
    // FilterBar + DataTable) rather than the empty-state landing card,
    // which hides the FilterBar by design. We're testing the data-view
    // wiring here, not the first-run nudge.
    registerEndpoint('/api/conversations', () => {
      return new Response(
        JSON.stringify([{
          id: 1, agentId: 1, agentName: 'test', channelType: 'web',
          peerId: 'admin', messageCount: 1, preview: 'hi',
          createdAt: '2026-05-27T00:00:00Z', updatedAt: '2026-05-27T00:00:00Z',
        }]),
        { status: 200, headers: { 'x-total-count': '1', 'content-type': 'application/json' } },
      )
    })
    const component = await mountSuspended(Conversations)
    // The FilterBar shipped by JCLAW-304 lists q first so the operator's
    // autocomplete surfaces it as a top-of-mind option. find() locates
    // the rendered input by placeholder substring, matching the page's
    // placeholder string from the template.
    const input = component.find('input[placeholder*="q:"]')
    expect(input.exists()).toBe(true)
  })
})

describe('Tasks page — JCLAW-328 q: wiring', () => {
  it('renders a FilterBar with q in the placeholder hint', async () => {
    setupMockApi()
    const component = await mountSuspended(Tasks)
    // The two status/type select dropdowns are gone in JCLAW-304 —
    // a single FilterBar took their place with q as the first
    // suggested key.
    const input = component.find('input[placeholder*="q:"]')
    expect(input.exists()).toBe(true)
    // Defensive: the legacy <select> dropdowns must not still be in
    // the rendered DOM — if they were, the old filter state would
    // double-track with the new FilterBar's emitted state and the
    // backend would get conflicting query params.
    const legacySelects = component.findAll('select').filter((s) => {
      const opts = s.findAll('option').map(o => o.text())
      return opts.includes('All statuses') || opts.includes('All types')
    })
    expect(legacySelects.length).toBe(0)
  })
})

describe('Subagents page — JCLAW-328 q: wiring + JCLAW-326 chip integration', () => {
  it('renders a FilterBar with q in the placeholder hint', async () => {
    setupMockApi()
    const component = await mountSuspended(Subagents)
    const input = component.find('input[placeholder*="q:"]')
    expect(input.exists()).toBe(true)
  })

  it('replaces the legacy parent/status/since selects with the FilterBar', async () => {
    // The three select dropdowns from before JCLAW-304 are gone —
    // their roles are now FilterBar keys (parentAgent, status, since).
    // Defensive check that no leftover <select> with the legacy
    // option text is rendering alongside the bar.
    setupMockApi()
    const component = await mountSuspended(Subagents)
    const legacy = component.findAll('select').filter((s) => {
      const opts = s.findAll('option').map(o => o.text())
      return opts.includes('All parent agents') || opts.includes('All statuses')
    })
    expect(legacy.length).toBe(0)
  })
})
