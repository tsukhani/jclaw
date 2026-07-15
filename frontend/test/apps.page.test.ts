import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Apps from '~/pages/apps.vue'

// useLazyFetch caches by URL across mounts; clear it so each test's /api/apps
// mock is the one that renders (JClaw test convention).
function appsEndpoint(apps: unknown[]) {
  registerEndpoint('/api/apps', () => ({ apps }))
}

describe('Apps page', () => {
  beforeEach(() => clearNuxtData())

  it('renders a card per app with name/version/creator/price and a new-tab launch link', async () => {
    appsEndpoint([
      { id: 'proposal-generator', url: '/apps/proposal-generator/', name: 'Proposal Generator',
        version: '1.2.3', creator: 'Tarun', icon: '/apps/proposal-generator/icon.png', price: '$9/mo', description: null },
    ])
    const c = await mountSuspended(Apps)
    await flushPromises()

    const card = c.find('[data-testid="app-card-proposal-generator"]')
    expect(card.exists()).toBe(true)
    expect(card.attributes('href')).toBe('/apps/proposal-generator/')
    expect(card.attributes('target')).toBe('_blank')
    expect(card.attributes('rel')).toContain('noopener')

    const text = c.text()
    expect(text).toContain('Proposal Generator')
    expect(text).toContain('v1.2.3')
    expect(text).toContain('Tarun')
    expect(text).toContain('$9/mo')
    expect(card.find('img').attributes('src')).toBe('/apps/proposal-generator/icon.png')
  })

  it('shows the empty state when no apps exist', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    expect(c.text()).toContain('No apps yet')
    expect(c.find('[data-testid^="app-card-"]').exists()).toBe(false)
  })

  it('falls back to a placeholder tile when an app has no icon', async () => {
    appsEndpoint([
      { id: 'noicon', url: '/apps/noicon/', name: 'No Icon', version: '1.0.0',
        creator: null, icon: null, price: null, description: null },
    ])
    const c = await mountSuspended(Apps)
    await flushPromises()

    const card = c.find('[data-testid="app-card-noicon"]')
    expect(card.exists()).toBe(true)
    expect(card.find('img').exists()).toBe(false) // no <img> — placeholder div instead
    expect(card.find('svg').exists()).toBe(true) // the Squares2X2Icon placeholder
    expect(card.text()).not.toContain('$') // no price label
  })
})
