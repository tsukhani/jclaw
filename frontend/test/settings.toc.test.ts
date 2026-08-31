import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'
import { sections } from '~/components/settings/sections'

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

    // EVERY registered section, not a spot-check (JCLAW-1139). This assertion is the merge
    // gate for the section list: the e2e suite that would otherwise catch a drifted TOC is
    // excluded from CI, so JCLAW-1057's Password/Upgrade/Restart -> Maintenance merge shipped
    // green here while five e2e specs silently rotted. Driving off the imported registry means
    // a renamed or removed section fails on the next merge instead.
    expect(sections.length, 'the section registry is empty').toBeGreaterThan(20)
    const missing = sections
      .map(s => s.id)
      .filter(id => !component.find(`[data-testid="settings-toc-item-${id}"]`).exists())
    expect(missing, 'registered sections with no rail item').toEqual([])
    // Default active section is the first (timezone).
    const timezone = component.find('[data-testid="settings-toc-item-timezone"]')
    expect(timezone.attributes('aria-current')).toBe('page')
    // A non-active item carries no aria-current.
    const shell = component.find('[data-testid="settings-toc-item-shell"]')
    expect(shell.attributes('aria-current')).toBeUndefined()
  })

  it('renders the functional group headers in the rail', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    const text = component.text()
    for (const label of ['System', 'Providers', 'Audio', 'Image', 'Video', 'Agents & Automation', 'Security']) {
      expect(text).toContain(label)
    }
  })

  it('swaps the active section when a rail item is clicked', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    // General is active; Shell is not yet.
    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBe('page')

    await component.find('[data-testid="settings-toc-item-shell"]').trigger('click')
    await flushPromises()
    await flushPromises()

    // Highlight moved to Shell; General is no longer current.
    expect(component.find('[data-testid="settings-toc-item-shell"]').attributes('aria-current')).toBe('page')
    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBeUndefined()
    // The Shell panel is now mounted (its Allowlist control renders).
    expect(component.text()).toContain('Shell Execution')
  })

  it('opens the section named by the ?section query param on load', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings, { route: '/settings?section=malware' })
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-malware"]').attributes('aria-current')).toBe('page')
    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBeUndefined()
    expect(component.text()).toContain('Malware Scanners')
  })

  it('falls back to the first section when ?section is unknown', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings, { route: '/settings?section=does-not-exist' })
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current')).toBe('page')
  })

  it('rolls the retired upgrade, restart and password sections into one Maintenance entry', async () => {
    baseEndpoints()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.find('[data-testid="settings-toc-item-maintenance"]').exists()).toBe(true)
    for (const retired of ['upgrade', 'restart', 'password']) {
      expect(
        component.find(`[data-testid="settings-toc-item-${retired}"]`).exists(),
        `${retired} should no longer have its own rail entry`,
      ).toBe(false)
    }
  })

  it('opens Maintenance for a bookmark predating the merge', async () => {
    baseEndpoints()
    // The real guard. An unrecognised id falls back to the FIRST section, so
    // without the retired-id map these shipped links would land on Timezone and
    // read as though deep-linking had simply stopped working.
    for (const retired of ['upgrade', 'restart', 'password']) {
      clearNuxtData()
      const component = await mountSuspended(Settings, { route: `/settings?section=${retired}` })
      await flushPromises()

      expect(
        component.find('[data-testid="settings-toc-item-maintenance"]').attributes('aria-current'),
        `?section=${retired} should open Maintenance`,
      ).toBe('page')
      expect(component.find('[data-testid="settings-toc-item-timezone"]').attributes('aria-current'))
        .toBeUndefined()
    }
  })

  it('shows all three maintenance controls on the section', async () => {
    baseEndpoints()
    registerEndpoint('/api/auth/status', () => ({ authenticated: true, passwordSet: true }))
    registerEndpoint('/api/system/upgrade', () => ({
      available: true, unavailableReason: null, currentVersion: '0.17.73', latestVersion: '0.17.73',
      upgradeAvailable: false, installKind: 'bundle', runningTasks: 0, activeSubagentRuns: 0,
      commit: null,
    }))
    registerEndpoint('/api/system/upgrade/status', () => null)
    registerEndpoint('/api/system/restart', () => ({
      available: true, unavailableReason: null, mode: 'PROD', backendOnly: false,
      rebuildExpected: false, runningTasks: 0, activeSubagentRuns: 0,
    }))

    const component = await mountSuspended(Settings, { route: '/settings?section=maintenance' })
    await flushPromises()
    await flushPromises()

    // The upgrade heading names the restart too: an operator reaching for the
    // upgrade needs to know it takes the instance down at the end.
    expect(component.text()).toContain('Upgrade and restart')
    expect(component.text()).toContain('Stops this instance and starts it again')
    // Password moved here from its own section; without this the merge could drop it
    // and every other assertion would still pass.
    expect(component.text()).toContain('Password')
  })
})
