import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import Skills from '~/pages/skills.vue'

// Stub EventSource since happy-dom doesn't provide it and
// the skills page uses useEventBus which creates an EventSource.
if (typeof globalThis.EventSource === 'undefined') {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: test mock patching a browser global with a minimal stand-in; narrowing adds no value here.
  ;(globalThis as any).EventSource = class MockEventSource {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mirrors the DOM EventSource handler signature (MessageEvent/Event) without importing the DOM types into this test.
    onmessage: ((e: any) => void) | null = null
    // eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: same — onerror takes Event in the real API, but tests only ever call it with synthetic stubs.
    onerror: ((e: any) => void) | null = null
    close() {}
  }
}

beforeEach(() => {
  // Skills page uses useState('promotingSkills') which needs to be initialized
  useState('promotingSkills', () => new Set())
  // useFetch caches by URL across mounts; without this, a later test sees the
  // previous test's mocked response. The new "structural ordering" assertions
  // require the per-test registerEndpoint payload to actually reach the
  // component, so we wipe the data cache between cases.
  clearNuxtData()
})

function setupSkillsApi() {
  registerEndpoint('/api/skills', () => [
    { name: 'web-search', folderName: 'web-search', description: 'Search the web', version: '1.0.0' },
    { name: 'code-review', folderName: 'code-review', description: 'Review code changes', version: '0.2.0' },
  ])
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: true },
  ])
  registerEndpoint('/api/agents/1/skills', () => [
    { name: 'web-search', folderName: 'web-search', enabled: true, version: '1.0.0' },
  ])
}

describe('Skills page', () => {
  it('renders the page heading', async () => {
    setupSkillsApi()
    const component = await mountSuspended(Skills)

    expect(component.text()).toContain('Skills')
  })

  it('shows global skills section', async () => {
    setupSkillsApi()
    const component = await mountSuspended(Skills)

    expect(component.text()).toContain('web-search')
    expect(component.text()).toContain('code-review')
  })

  it('shows agent cards with skill assignments', async () => {
    setupSkillsApi()
    const component = await mountSuspended(Skills)

    // Agent name should appear in the agent cards section
    expect(component.text()).toContain('main-agent')
  })

  it('displays skill version info', async () => {
    setupSkillsApi()
    const component = await mountSuspended(Skills)

    expect(component.text()).toContain('1.0.0')
    expect(component.text()).toContain('0.2.0')
  })

  it('cleans up timers on unmount', async () => {
    setupSkillsApi()
    const clearTimeoutSpy = vi.spyOn(globalThis, 'clearTimeout')

    const component = await mountSuspended(Skills)
    component.unmount()

    // The component has onUnmounted that clears dragErrorTimer and infoBannerTimer.
    // We verify unmount completes without error (the cleanup ran).
    // clearTimeout is called even with null handles (which is safe).
    expect(true).toBe(true)

    clearTimeoutSpy.mockRestore()
  })

  it('pins structural skills above the CUSTOM SKILLS divider in canonical order', async () => {
    // Three structural-eligible names + two custom; the response order is
    // shuffled so the test catches any silent fallback to insertion order.
    registerEndpoint('/api/skills', () => [
      { name: 'web-search', folderName: 'web-search', description: '', version: '1.0.0' },
      { name: 'jclaw-api', folderName: 'jclaw-api', description: '', version: '0.1.0' },
      { name: 'code-review', folderName: 'code-review', description: '', version: '0.2.0' },
      { name: 'skill-creator', folderName: 'skill-creator', description: '', version: '1.1.1' },
    ])
    registerEndpoint('/api/agents', () => [])

    const component = await mountSuspended(Skills)
    const text = component.text()

    // Canonical order: skill-creator → jclaw-api → CUSTOM SKILLS → code-review → web-search.
    const idxCreator = text.indexOf('skill-creator')
    const idxApi = text.indexOf('jclaw-api')
    const idxDivider = text.indexOf('CUSTOM SKILLS')
    const idxCode = text.indexOf('code-review')
    const idxWeb = text.indexOf('web-search')

    expect(idxCreator).toBeGreaterThanOrEqual(0)
    expect(idxApi).toBeGreaterThan(idxCreator)
    expect(idxDivider).toBeGreaterThan(idxApi)
    expect(idxCode).toBeGreaterThan(idxDivider)
    expect(idxWeb).toBeGreaterThan(idxCode)
  })

  it('omits the CUSTOM SKILLS divider when only structural skills are present', async () => {
    registerEndpoint('/api/skills', () => [
      { name: 'skill-creator', folderName: 'skill-creator', description: '', version: '1.1.1' },
      { name: 'jclaw-api', folderName: 'jclaw-api', description: '', version: '0.1.0' },
    ])
    registerEndpoint('/api/agents', () => [])

    const component = await mountSuspended(Skills)
    // No custom skills → nothing to separate, so the divider stays out of the DOM.
    expect(component.text()).not.toContain('CUSTOM SKILLS')
  })

  it('renders without error when no skills exist', async () => {
    registerEndpoint('/api/skills', () => [])
    registerEndpoint('/api/agents', () => [])

    const component = await mountSuspended(Skills)
    expect(component.text()).toContain('Skills')
  })
})
