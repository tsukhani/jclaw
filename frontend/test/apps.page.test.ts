import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import { useConfirm } from '~/composables/useConfirm'
import Apps from '~/pages/apps.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

const { navigateToMock } = vi.hoisted(() => ({ navigateToMock: vi.fn().mockResolvedValue(undefined) }))
mockNuxtImport('navigateTo', () => navigateToMock)

// The ConfirmDialog is normally mounted once at the app root; mount it beside
// Apps so the delete-confirm flow's dialog renders (mirrors agents.flows.test).
const AppsHarness = defineComponent({
  setup: () => () => h('div', [h(Apps), h(ConfirmDialog)]),
})

// useLazyFetch caches by URL across mounts; clear it so each test's /api/apps
// mock is the one that renders (JClaw test convention).
function appsEndpoint(apps: unknown[]) {
  registerEndpoint('/api/apps', () => ({ apps }))
}

// Agents feed the JCLAW-763 designation picker. A mutable mock read by a single
// registered handler, so tests set it before mount without re-registering.
let agentsMock: { id: number, name: string }[] = []
function agentsEndpoint(agents: { id: number, name: string }[]) {
  agentsMock = agents
}

describe('Apps page', () => {
  beforeEach(() => {
    clearNuxtData()
    navigateToMock.mockClear()
    agentsMock = []
    registerEndpoint('/api/agents', () => agentsMock)
  })

  afterEach(() => {
    // Drain any ConfirmDialog left open by a delete test so its module-singleton
    // state doesn't leak into the next case.
    const { _state, _resolve } = useConfirm()
    if (_state.open) _resolve(false)
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
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

  it('create form shows an agent selector populated from /api/agents plus a none option', async () => {
    appsEndpoint([])
    agentsEndpoint([{ id: 1, name: 'Alpha' }, { id: 2, name: 'Beta' }])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    const select = c.find('#new-app-agent')
    expect(select.exists()).toBe(true)
    const opts = select.findAll('option')
    expect(opts.some(o => o.attributes('value') === '')).toBe(true) // "none"
    expect(opts.some(o => o.attributes('value') === '1' && o.text() === 'Alpha')).toBe(true)
    expect(opts.some(o => o.attributes('value') === '2' && o.text() === 'Beta')).toBe(true)
  })

  it('create brief includes the designated agent id when one is chosen', async () => {
    appsEndpoint([])
    agentsEndpoint([{ id: 7, name: 'Researcher' }])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    await c.find('#new-app-brief').setValue('An RFP tool.')
    await c.find('#new-app-agent').setValue('7')
    await c.find('form').trigger('submit')
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('app.json "agent" field')
    expect(arg.query.compose).toContain('7')
  })

  it('create brief includes the author as the app.json creator when provided', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    await c.find('#new-app-name').setValue('Widget')
    await c.find('#new-app-author').setValue('Ada Lovelace')
    await c.find('#new-app-brief').setValue('A widget.')
    await c.find('form').trigger('submit')
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('Author (write it as the app.json "creator" field): Ada Lovelace')
  })

  it('update form prefills the designated agent and surfaces a stale id as a removable option', async () => {
    appsEndpoint([
      { id: 'a1', url: '/apps/a1/', name: 'A1', version: '1.0.0',
        creator: null, icon: null, price: null, description: null, agent: '99' },
    ])
    agentsEndpoint([{ id: 1, name: 'Alpha' }]) // 99 is not among current agents -> stale
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="update-app-a1"]').trigger('click')
    const select = c.find('[data-testid="update-app-agent"]')
    expect(select.exists()).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('99') // prefilled from app.json
    expect(select.findAll('option').some(o => o.text().includes('Unknown agent'))).toBe(true)
  })

  it('update brief carries a new designated agent even with no text change', async () => {
    appsEndpoint([
      { id: 'a1', url: '/apps/a1/', name: 'A1', version: '2.0.0',
        creator: null, icon: null, price: null, description: null, agent: null },
    ])
    agentsEndpoint([{ id: 5, name: 'Gamma' }])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="update-app-a1"]').trigger('click')
    await c.find('[data-testid="update-app-agent"]').setValue('5')
    // the agent change alone enables submit (no text brief required)
    expect(c.find('[data-testid="update-app-submit"]').attributes('disabled')).toBeUndefined()
    await c.find('form').trigger('submit')
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('app.json "agent" = 5')
  })

  it('create brief assembles a subscription pricing label with billing period', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    await c.find('#new-app-brief').setValue('A tool.')
    await c.find('[data-testid="cost-type"]').setValue('subscription')
    await c.find('[data-testid="cost-amount"]').setValue('9') // USD ($) + monthly are the defaults
    await c.find('form').trigger('submit')
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('app.json "price" field): $9/mo')
  })

  it('create brief reflects the chosen currency for a fixed price', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    await c.find('#new-app-brief').setValue('A tool.')
    await c.find('[data-testid="cost-type"]').setValue('fixed')
    await c.find('[data-testid="cost-currency"]').setValue('EUR')
    await c.find('[data-testid="cost-amount"]').setValue('20')
    await c.find('form').trigger('submit')
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('app.json "price" field): €20')
    expect(arg.query.compose).not.toContain('/mo') // fixed price has no billing period
  })

  it('create brief defaults to a Free pricing label', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="create-app-button"]').trigger('click')
    await c.find('#new-app-brief').setValue('A tool.')
    await c.find('form').trigger('submit') // cost untouched -> defaults to Free
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('app.json "price" field): Free')
  })

  it('update brief carries a new pricing label with no text or agent change', async () => {
    appsEndpoint([
      { id: 'a1', url: '/apps/a1/', name: 'A1', version: '2.0.0',
        creator: null, icon: null, price: '$1/mo', description: null, agent: null },
    ])
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('[data-testid="update-app-a1"]').trigger('click')
    // "keep current pricing" is the default, so submit is disabled until something changes
    expect(c.find('[data-testid="update-app-submit"]').attributes('disabled')).toBeDefined()
    await c.find('[data-testid="cost-type"]').setValue('fixed')
    await c.find('[data-testid="cost-amount"]').setValue('5')
    expect(c.find('[data-testid="update-app-submit"]').attributes('disabled')).toBeUndefined()
    await c.find('form').trigger('submit')
    await flushPromises()

    const arg = navigateToMock.mock.calls[0]![0] as { query: { compose: string } }
    expect(arg.query.compose).toContain('app.json "price" = "$5"')
  })

  it('renders a delete button per app card', async () => {
    appsEndpoint([
      { id: 'to-remove', url: '/apps/to-remove/', name: 'To Remove', version: '1.0.0',
        creator: null, icon: null, price: null, description: null, agent: null },
    ])
    const c = await mountSuspended(Apps)
    await flushPromises()

    const del = c.find('[data-testid="delete-app-to-remove"]')
    expect(del.exists()).toBe(true)
    expect(del.attributes('aria-label')).toBe('Delete this app')
  })

  it('confirming the delete dialog fires DELETE /api/apps/:slug', async () => {
    let deletedSlug: string | null = null
    registerEndpoint('/api/apps/to-remove', {
      method: 'DELETE',
      handler: () => {
        deletedSlug = 'to-remove'
        return { deleted: true, slug: 'to-remove' }
      },
    })
    appsEndpoint([
      { id: 'to-remove', url: '/apps/to-remove/', name: 'To Remove', version: '1.0.0',
        creator: null, icon: null, price: null, description: null, agent: null },
    ])
    const c = await mountSuspended(AppsHarness)
    await flushPromises()

    await c.find('[data-testid="delete-app-to-remove"]').trigger('click')
    await flushPromises()

    // Click the danger confirm button (label 'Delete') on the teleported dialog.
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn, 'Delete confirm button should exist on the dialog').toBeTruthy()
    confirmBtn!.click()
    await vi.waitFor(() => expect(deletedSlug).toBe('to-remove'))
  })

  it('cancelling the delete dialog skips the DELETE', async () => {
    let deleted = false
    registerEndpoint('/api/apps/keep-me', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { deleted: true, slug: 'keep-me' }
      },
    })
    appsEndpoint([
      { id: 'keep-me', url: '/apps/keep-me/', name: 'Keep Me', version: '1.0.0',
        creator: null, icon: null, price: null, description: null, agent: null },
    ])
    const c = await mountSuspended(AppsHarness)
    await flushPromises()

    await c.find('[data-testid="delete-app-keep-me"]').trigger('click')
    await flushPromises()

    const cancelBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Cancel')
    expect(cancelBtn).toBeTruthy()
    cancelBtn!.click()
    await flushPromises()
    await flushPromises()

    expect(deleted).toBe(false)
  })

  const twoApps = () => [
    { id: 'calc', url: '/apps/calc/', name: 'Calculator', version: '1.0.0',
      creator: null, icon: null, price: null, description: null, agent: null },
    { id: 'dice', url: '/apps/dice/', name: 'Dice Roller', version: '1.0.0',
      creator: null, icon: null, price: null, description: null, agent: null },
  ]

  it('the floating search bar filters the grid by name in real time (case-insensitive)', async () => {
    appsEndpoint(twoApps())
    const c = await mountSuspended(Apps)
    await flushPromises()

    // Both cards render before any query.
    expect(c.find('[data-testid="app-card-calc"]').exists()).toBe(true)
    expect(c.find('[data-testid="app-card-dice"]').exists()).toBe(true)

    await c.find('#apps-search').setValue('DICE') // uppercase → still matches "Dice Roller"
    expect(c.find('[data-testid="app-card-calc"]').exists()).toBe(false)
    expect(c.find('[data-testid="app-card-dice"]').exists()).toBe(true)
  })

  it('shows a no-match message when the query matches no app', async () => {
    appsEndpoint(twoApps())
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('#apps-search').setValue('nonexistent')
    expect(c.find('[data-testid="app-card-calc"]').exists()).toBe(false)
    expect(c.find('[data-testid="app-card-dice"]').exists()).toBe(false)
    expect(c.text()).toContain('No apps match')
  })

  it('the clear button resets the query and restores the full grid', async () => {
    appsEndpoint(twoApps())
    const c = await mountSuspended(Apps)
    await flushPromises()

    await c.find('#apps-search').setValue('dice')
    expect(c.find('[data-testid="app-card-calc"]').exists()).toBe(false)

    // The clear (X) button appears only once there's a query.
    await c.find('[aria-label="Clear search"]').trigger('click')
    expect(c.find('[data-testid="app-card-calc"]').exists()).toBe(true)
    expect(c.find('[data-testid="app-card-dice"]').exists()).toBe(true)
  })

  it('hides the search bar when there are no apps to search', async () => {
    appsEndpoint([])
    const c = await mountSuspended(Apps)
    await flushPromises()

    expect(c.find('#apps-search').exists()).toBe(false)
  })
})
