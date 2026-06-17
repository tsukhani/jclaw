import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Page-level tests for the Logging Levels section of {@code settings.vue}.
 * Covers that persisted per-logger overrides render and that the level
 * dropdown is populated from the backend's valid-level set.
 */
function baseEndpoints() {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
}

describe('Settings page — Logging Levels', () => {
  beforeEach(() => {
    // useFetch caches by URL across mounts; clear so each test's
    // /api/logging/levels stub is the one that resolves.
    clearNuxtData()
  })

  it('renders persisted per-logger overrides', async () => {
    baseEndpoints()
    registerEndpoint('/api/logging/levels', () => ({
      entries: [{ logger: 'com.example.WidgetService', level: 'WARN' }],
      validLevels: ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'],
    }))

    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.text()).toContain('Logging Levels')
    expect(component.text()).toContain('com.example.WidgetService')
  })

  it('populates the level dropdown from the backend valid-level set and shows the empty state', async () => {
    baseEndpoints()
    registerEndpoint('/api/logging/levels', () => ({
      entries: [],
      validLevels: ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'],
    }))

    const component = await mountSuspended(Settings)
    await flushPromises()

    const html = component.html()
    expect(html).toContain('>TRACE<')
    expect(html).toContain('>DEBUG<')
    expect(component.text()).toContain('No overrides')
  })
})
