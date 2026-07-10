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
  registerEndpoint('/api/providers', () => [])
}

/**
 * Mount Settings and open a specific section. The page renders one section at a
 * time (`<component :is>` swap), so tests must activate their section before
 * asserting on its DOM. Setting activeSectionId drives the swap; the double
 * flush settles the freshly-mounted panel's async setup + <Suspense>.
 */
async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
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
      knownLoggers: ['root', 'play', 'com.example.WidgetService'],
    }))

    const component = await mountSettingsSection('logging')

    expect(component.text()).toContain('Logging Levels')
    expect(component.text()).toContain('com.example.WidgetService')
  })

  it('populates the level dropdown from the backend valid-level set and shows the empty state', async () => {
    baseEndpoints()
    registerEndpoint('/api/logging/levels', () => ({
      entries: [],
      validLevels: ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'],
      knownLoggers: ['root', 'play'],
    }))

    const component = await mountSettingsSection('logging')

    const html = component.html()
    expect(html).toContain('>TRACE<')
    expect(html).toContain('>DEBUG<')
    expect(component.text()).toContain('No overrides')
  })

  it('offers known loggers as autocomplete and warns on an unknown name', async () => {
    baseEndpoints()
    registerEndpoint('/api/logging/levels', () => ({
      entries: [],
      validLevels: ['OFF', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'],
      knownLoggers: ['root', 'play', 'controllers.ApiChatController'],
    }))

    const component = await mountSettingsSection('logging')

    // Known loggers are surfaced as <datalist> options for the add field.
    const html = component.html()
    expect(html).toContain('controllers.ApiChatController')

    const input = component.find('input[list="logging-logger-suggestions"]')
    expect(input.exists()).toBe(true)

    // A name not in the corpus triggers the soft typo hint…
    await input.setValue('controlers.Typo')
    await flushPromises()
    expect(component.text()).toContain('has logged yet')

    // …while a known name clears it.
    await input.setValue('play')
    await flushPromises()
    expect(component.text()).not.toContain('has logged yet')
  })
})
