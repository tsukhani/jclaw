import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * Tests for the Settings page's TOC + single-section swap shell (JCLAW-680).
 * The 20 per-section suites cover each panel's behavior; this file covers the
 * page-level navigation the swap introduced: rail rendering, the active-item
 * highlight, click-to-swap, and ?section= deep-linking.
 */
function baseEndpoints() {
  registerEndpoint('/api/config', () => ({ entries: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/timezones', () => ({ timezones: ['UTC'], default: 'UTC', appDefault: 'UTC' }))
}

describe('Settings page — TOC navigation + section swap', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders a rail item for every registered section and marks the first active', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // Every section has a rail button.
    for (const id of ['general', 'providers', 'transcription', 'shell', 'unmanaged']) {
      expect(component.find(`[data-testid="settings-toc-item-${id}"]`).exists()).toBe(true)
    }
    // Default active section is the first (general).
    const general = component.find('[data-testid="settings-toc-item-general"]')
    expect(general.attributes('aria-current')).toBe('page')
    // A non-active item carries no aria-current.
    const shell = component.find('[data-testid="settings-toc-item-shell"]')
    expect(shell.attributes('aria-current')).toBeUndefined()
  })

  it('swaps the active section when a rail item is clicked', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // General is active; Shell is not yet.
    expect(component.find('[data-testid="settings-toc-item-general"]').attributes('aria-current')).toBe('page')

    await component.find('[data-testid="settings-toc-item-shell"]').trigger('click')
    await flushPromises()
    await flushPromises()

    // Highlight moved to Shell; General is no longer current.
    expect(component.find('[data-testid="settings-toc-item-shell"]').attributes('aria-current')).toBe('page')
    expect(component.find('[data-testid="settings-toc-item-general"]').attributes('aria-current')).toBeUndefined()
    // The Shell panel is now mounted (its Allowlist control renders).
    expect(component.text()).toContain('Shell Execution')
  })

  it('opens the section named by the ?section query param on load', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings, { route: '/settings?section=malware' })
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-malware"]').attributes('aria-current')).toBe('page')
    expect(component.find('[data-testid="settings-toc-item-general"]').attributes('aria-current')).toBeUndefined()
    expect(component.text()).toContain('Malware Scanners')
  })

  it('falls back to the first section when ?section is unknown', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings, { route: '/settings?section=does-not-exist' })
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-general"]').attributes('aria-current')).toBe('page')
  })
})
