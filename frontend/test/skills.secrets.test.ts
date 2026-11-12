import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
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

beforeEach(() => {
  useState('promotingSkills', () => new Set())
  clearNuxtData()
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
    expect(flag.text()).toBe('Needs secrets')
    expect(flag.attributes('title')).toBe('Not runnable: needs NAS_PASS, PROXY_PASS')
    expect(page.find('[data-testid="needs-secrets-4-plain"]').exists()).toBe(false)

    await flag.trigger('click')
    await flushPromises()
    await flushPromises()
    expect(page.find('[data-testid="viewer-not-runnable"]').text()).toBe('Not runnable')
    expect(page.find('[data-testid="secrets-not-runnable"]').exists()).toBe(true)
  })
})
