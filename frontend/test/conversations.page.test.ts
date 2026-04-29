import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Conversations from '~/pages/conversations/index.vue'

/**
 * Pin the action-icon and row-click contract on the Conversations list:
 *
 *   - Clicking a row navigates to /chat?conversation=:id (open in chat).
 *   - Clicking the eye icon navigates to /conversations/:id (read-only detail).
 *   - The two paths don't fire together — the icon's @click stops propagation
 *     so the row-level handler doesn't also see the event.
 *
 * Vitest can't paint pixels (the icon's eye-vs-chat-bubble appearance is a
 * visual concern), but the DOM tree, click handlers, and resulting navigation
 * targets are all observable. We use mockNuxtImport — Nuxt's officially
 * supported macro for intercepting auto-imported composables — to replace
 * navigateTo with a vi.fn so each click site becomes an assertion point
 * instead of a real router push. Plain vi.stubGlobal doesn't work here:
 * Nuxt resolves `navigateTo` calls through its build-time auto-import
 * pipeline, not via globalThis lookup.
 */

const { navigateToMock } = vi.hoisted(() => ({
  navigateToMock: vi.fn().mockResolvedValue(undefined),
}))

mockNuxtImport('navigateTo', () => navigateToMock)

beforeEach(() => {
  navigateToMock.mockClear()
})

function setupTwoConversations() {
  registerEndpoint('/api/conversations', () => [
    {
      id: 101,
      agentId: 1,
      agentName: 'main',
      channelType: 'web',
      peerId: 'admin',
      messageCount: 2,
      preview: 'Hi',
      createdAt: '2026-04-29T10:45:22Z',
      updatedAt: '2026-04-29T10:45:59Z',
    },
    {
      id: 102,
      agentId: 1,
      agentName: 'main',
      channelType: 'web',
      peerId: 'admin',
      messageCount: 4,
      preview: 'Hi',
      createdAt: '2026-04-29T10:45:00Z',
      updatedAt: '2026-04-29T10:45:22Z',
    },
  ])
}

describe('Conversations page — row + icon navigation', () => {
  it('row click navigates to /chat?conversation=:id', async () => {
    setupTwoConversations()
    const component = await mountSuspended(Conversations)
    await flushPromises()

    // The DataTable renders rows as <tr>. The first body row corresponds
    // to the first conversation in the fixture (id=101). Clicking
    // anywhere on the row body — except a stop-propagating action button
    // — fires @row-click, which after this change goes to /chat rather
    // than to the conversation detail page.
    const rows = component.findAll('tbody tr')
    expect(rows.length).toBeGreaterThanOrEqual(2)
    await rows[0]!.trigger('click')

    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/chat?conversation=101')
  })

  it('eye icon click navigates to /conversations/:id and stops row propagation', async () => {
    setupTwoConversations()
    const component = await mountSuspended(Conversations)
    await flushPromises()

    // Title attribute is the discrimination axis — both the eye and the
    // panel-split "Quick preview" buttons are <button> with an SVG
    // child, but only this one carries title="View details".
    const detailBtn = component.find('button[title="View details"]')
    expect(detailBtn.exists()).toBe(true)
    await detailBtn.trigger('click')

    // Exactly one navigateTo call — the icon's onClick stops propagation
    // so the row-click handler doesn't also fire and try to send us to
    // /chat. If stopPropagation regresses, this assertion catches it
    // (we'd see two calls, with the row-click one going to /chat).
    expect(navigateToMock).toHaveBeenCalledTimes(1)
    expect(navigateToMock).toHaveBeenCalledWith('/conversations/101')
  })

  it('row and icon together produce two distinct navigations across separate clicks', async () => {
    setupTwoConversations()
    const component = await mountSuspended(Conversations)
    await flushPromises()

    // Sequential clicks on different affordances should accumulate two
    // distinct navigateTo calls. Order independent. Sanity-checks that
    // the mock isn't sticky across renders and that the row-click for
    // row #2 (id=102) carries the right id.
    const rows = component.findAll('tbody tr')
    await rows[1]!.trigger('click')
    expect(navigateToMock).toHaveBeenLastCalledWith('/chat?conversation=102')

    const detailBtns = component.findAll('button[title="View details"]')
    expect(detailBtns.length).toBeGreaterThanOrEqual(2)
    await detailBtns[1]!.trigger('click')
    expect(navigateToMock).toHaveBeenLastCalledWith('/conversations/102')

    expect(navigateToMock).toHaveBeenCalledTimes(2)
  })
})
