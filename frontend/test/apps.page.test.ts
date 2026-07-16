import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Apps from '~/pages/apps.vue'

const { navigateToMock } = vi.hoisted(() => ({ navigateToMock: vi.fn().mockResolvedValue(undefined) }))
mockNuxtImport('navigateTo', () => navigateToMock)

// useLazyFetch caches by URL across mounts; clear it so each test's /api/apps
// mock is the one that renders (JClaw test convention).
function appsEndpoint(apps: unknown[]) {
  registerEndpoint('/api/apps', () => ({ apps }))
}

describe('Apps page', () => {
  beforeEach(() => {
    clearNuxtData()
    navigateToMock.mockClear()
  })

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

  it('create-app affordance assembles an app-creator brief and hands off to Chat', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    // The form is hidden until the affordance is clicked.
    expect(c.find('#new-app-brief').exists()).toBe(false)
    await c.find('[data-testid="create-app-button"]').trigger('click')

    await c.find('#new-app-name').setValue('Proposal Generator')
    await c.find('#new-app-brief').setValue('An RFP + proposal builder.')
    // Trigger the form's submit (a submit-button click doesn't dispatch submit in jsdom).
    await c.find('form').trigger('submit')
    await flushPromises()

    expect(navigateToMock).toHaveBeenCalledTimes(1)
    const arg = navigateToMock.mock.calls[0]![0] as { path: string, query: { compose: string } }
    expect(arg.path).toBe('/chat')
    expect(arg.query.compose).toContain('app-creator skill')
    expect(arg.query.compose).toContain('App name: Proposal Generator')
    expect(arg.query.compose).toContain('What it should do: An RFP + proposal builder.')
  })

  it('does not hand off when the brief is empty (submit stays disabled)', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    // No brief → the submit button is disabled, so no navigation.
    expect(c.find('[data-testid="build-app-submit"]').attributes('disabled')).toBeDefined()
    expect(navigateToMock).not.toHaveBeenCalled()
  })

  it('per-card update affordance hands off a scoped update request with a version bump', async () => {
    appsEndpoint([
      { id: 'proposal-generator', url: '/apps/proposal-generator/', name: 'Proposal Generator',
        version: '1.2.3', creator: 'Tarun', icon: null, price: null, description: null },
    ])
    const c = await mountSuspended(Apps)
    await flushPromises()

    // The update form is hidden until the card's update button is clicked.
    expect(c.find('#update-app-brief').exists()).toBe(false)
    await c.find('[data-testid="update-app-proposal-generator"]').trigger('click')
    expect(c.find('#update-app-brief').exists()).toBe(true)

    await c.find('#update-app-brief').setValue('Add a dark-mode toggle.')
    await c.find('form').trigger('submit')
    await flushPromises()

    expect(navigateToMock).toHaveBeenCalledTimes(1)
    const arg = navigateToMock.mock.calls[0]![0] as { path: string, query: { compose: string } }
    expect(arg.path).toBe('/chat')
    expect(arg.query.compose).toContain('update the existing hosted app "Proposal Generator"')
    expect(arg.query.compose).toContain('public/apps/proposal-generator/')
    expect(arg.query.compose).toContain('Add a dark-mode toggle.')
    expect(arg.query.compose).toContain('bump the version')
  })
})
