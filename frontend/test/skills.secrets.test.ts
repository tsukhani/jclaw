import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import Skills from '~/pages/skills/[[name]].vue'

/**
 * JF-348: a skill that still needs a secret is flagged under each agent that has it, and its viewer says it is not
 * runnable.
 */

if (typeof globalThis.EventSource === 'undefined') {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test mock patching a browser global with a minimal stand-in
  ;(globalThis as any).EventSource = class MockEventSource {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mirrors the DOM EventSource handler signature
    onmessage: ((e: any) => void) | null = null
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mirrors the DOM EventSource handler signature
    onerror: ((e: any) => void) | null = null
    close() {}
  }
}

// A page left mounted shares the router, so it would follow the next case's URL too.
enableAutoUnmount(afterEach)

beforeEach(async () => {
  useState('promotingSkills', () => new Set())
  clearNuxtData()
  await useRouter().replace('/skills')
})

describe('Skills page: skills that need secrets', () => {
  it('flags the skill under its agent and marks its viewer not runnable', async () => {
    registerEndpoint('/api/skills', () => [])
    registerEndpoint('/api/agents', () => [
      { id: 4, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: false },
    ])
    registerEndpoint('/api/skills/by-agent', () => ({
      4: [
        { name: 'nas', folderName: 'nas', enabled: true, version: '1.0.0', needsValues: ['NAS_PASS', 'PROXY_PASS'] },
        { name: 'plain', folderName: 'plain', enabled: true, version: '1.0.0', needsValues: [] },
      ],
    }))
    registerEndpoint('/api/agents/4/skills/nas/files', () => ({ files: [], tools: [], commands: [], author: '' }))
    registerEndpoint('/api/agents/4/skills/nas/secrets', () => ({
      secrets: [],
      expected: [{ name: 'NAS_PASS', description: 'The password', exampleHosts: [], optional: false, set: false, setIn: null }],
      missing: ['NAS_PASS', 'PROXY_PASS'],
    }))

    const page = await mountSuspended(Skills)
    await flushPromises()

    const flag = page.find('[data-testid="needs-secrets-4-nas"]')
    expect(flag.text()).toBe('Needs values')
    expect(flag.attributes('title')).toBe('Not runnable: needs NAS_PASS, PROXY_PASS')
    expect(page.find('[data-testid="needs-secrets-4-plain"]').exists()).toBe(false)

    await flag.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(page.find('[data-testid="viewer-not-runnable"]').text()).toBe('Not runnable')
    expect(page.find('[data-testid="secrets-not-runnable"]').exists()).toBe(true)
  })
})

/** The agent and copy the chat's withheld-skills link names, and the page that opens from it. */
function setupNasAgent() {
  // The auth middleware probes /api/config on every navigation; without it the page's own replace lands on /login.
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/skills', () => [])
  registerEndpoint('/api/agents', () => [
    { id: 4, name: 'helper', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: false },
  ])
  registerEndpoint('/api/skills/by-agent', () => ({
    4: [{ name: 'nas', folderName: 'nas', enabled: true, version: '1.0.0', needsValues: ['NAS_PASS'], heldBySwitchedOff: {} }],
  }))
  registerEndpoint('/api/agents/4/skills/nas/files', () => ({ files: [], tools: [], commands: [], author: '' }))
  registerEndpoint('/api/agents/4/skills/nas/secrets', () => ({
    secrets: [],
    expected: [{ name: 'NAS_PASS', description: 'The password', exampleHosts: [], optional: false, set: false, setIn: null }],
    missing: ['NAS_PASS'],
  }))
}

describe('Skills page: /skills?agent=<id>&skill=<folder> (JF-327)', () => {
  const scrollIntoView = vi.fn()
  beforeEach(() => {
    scrollIntoView.mockClear()
    Element.prototype.scrollIntoView = scrollIntoView
  })
  afterEach(() => {
    delete (Element.prototype as Partial<Element>).scrollIntoView
  })

  it('opens that agent\'s copy with its Secrets panel in view, and leaves the URL at /skills', async () => {
    setupNasAgent()
    const page = await mountSuspended(Skills, { route: '/skills?agent=4&skill=nas' })
    await flushPromises()

    const panel = page.find('[data-testid="skill-secrets"]')
    await vi.waitFor(() => expect(scrollIntoView).toHaveBeenCalledTimes(1))
    expect(panel.exists()).toBe(true)
    expect(scrollIntoView.mock.contexts[0]).toBe(panel.element)
    expect(page.find('[data-testid="viewer-not-runnable"]').text()).toBe('Not runnable')
    await vi.waitFor(() => expect(useRouter().currentRoute.value.fullPath).toBe('/skills'))
  })

  for (const [what, url] of [
    ['an unknown agent', '/skills?agent=99&skill=nas'],
    ['an unknown skill', '/skills?agent=4&skill=absent'],
    ['an agent id that is no number', '/skills?agent=helper&skill=nas'],
  ] as const) {
    it(`opens the page as usual, with no panel, for ${what}`, async () => {
      setupNasAgent()
      const page = await mountSuspended(Skills, { route: url })
      await flushPromises()

      expect(page.find('[data-testid="skill-secrets"]').exists()).toBe(false)
      expect(page.find('[data-testid="needs-secrets-4-nas"]').exists()).toBe(true)
      expect(scrollIntoView).not.toHaveBeenCalled()
      await vi.waitFor(() => expect(useRouter().currentRoute.value.fullPath).toBe('/skills'))
    })
  }
})
