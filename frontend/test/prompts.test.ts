import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { useConfirm } from '~/composables/useConfirm'
import Prompts from '~/pages/prompts.vue'

/**
 * Prompts Library page (JCLAW-813): rendering, client-side search + category
 * filter, the Run → chat-composer hand-off (?compose=), and delete-behind-confirm.
 */
const { navigateToMock } = vi.hoisted(() => ({ navigateToMock: vi.fn().mockResolvedValue(undefined) }))
mockNuxtImport('navigateTo', () => navigateToMock)

const CATEGORIES = [
  { value: 'CODING', label: 'Coding' },
  { value: 'WRITING', label: 'Writing' },
  { value: 'CUSTOM', label: 'Custom' },
]

function samplePrompts() {
  return [
    { id: 1, title: 'Code Review', content: 'Review this code', tags: 'review, quality',
      category: 'CODING', categoryLabel: 'Coding', createdAt: null, updatedAt: null },
    { id: 2, title: 'Blog Outline', content: 'Outline a blog post', tags: 'writing',
      category: 'WRITING', categoryLabel: 'Writing', createdAt: null, updatedAt: null },
  ]
}

describe('Prompts Library page', () => {
  let prompts = samplePrompts()

  beforeEach(() => {
    clearNuxtData()
    navigateToMock.mockClear()
    prompts = samplePrompts()
    registerEndpoint('/api/prompts', () => prompts)
    registerEndpoint('/api/prompts/categories', () => CATEGORIES)
  })

  afterEach(() => {
    const { _state, _resolve } = useConfirm()
    if (_state.open) _resolve(false)
    // Tear down any teleported dialog + reka-ui's modal body lock so a dialog
    // left open by one test can't inert the page for the next.
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
    document.body.style.pointerEvents = ''
  })

  it('renders a card per prompt with title, category badge, and tag chips', async () => {
    const c = await mountSuspended(Prompts)
    await flushPromises()

    expect(c.find('[data-testid="prompt-card-1"]').exists()).toBe(true)
    expect(c.find('[data-testid="prompt-card-2"]').exists()).toBe(true)
    const text = c.text()
    expect(text).toContain('Code Review')
    expect(text).toContain('Coding')
    expect(text).toContain('#review')
  })

  it('shows the empty state when the library is empty', async () => {
    prompts = []
    const c = await mountSuspended(Prompts)
    await flushPromises()

    expect(c.text()).toContain('Your prompt library is empty')
    expect(c.find('[data-testid^="prompt-card-"]').exists()).toBe(false)
  })

  it('search filters across title, content, and tags', async () => {
    const c = await mountSuspended(Prompts)
    await flushPromises()

    await c.find('[data-testid="prompt-search"]').setValue('blog')
    await flushPromises()

    expect(c.find('[data-testid="prompt-card-2"]').exists()).toBe(true)
    expect(c.find('[data-testid="prompt-card-1"]').exists()).toBe(false)
  })

  it('category filter narrows to one category', async () => {
    const c = await mountSuspended(Prompts)
    await flushPromises()

    await c.find('[data-testid="category-filter-CODING"]').trigger('click')
    await flushPromises()

    expect(c.find('[data-testid="prompt-card-1"]').exists()).toBe(true)
    expect(c.find('[data-testid="prompt-card-2"]').exists()).toBe(false)
  })

  it('Run hands the prompt content to the chat composer via ?compose=', async () => {
    const c = await mountSuspended(Prompts)
    await flushPromises()

    await c.find('[data-testid="prompt-run-1"]').trigger('click')

    expect(navigateToMock).toHaveBeenCalledTimes(1)
    const arg = navigateToMock.mock.calls[0]![0] as { path: string, query: { compose: string } }
    expect(arg.path).toBe('/chat')
    expect(arg.query.compose).toBe('Review this code')
  })

  it('New prompt opens the describe-it step, not the raw form', async () => {
    const c = await mountSuspended(Prompts)
    await flushPromises()

    await c.find('[data-testid="new-prompt-button"]').trigger('click')
    await flushPromises()

    // Create starts on the describe step: a single description box + Generate,
    // and NOT the title/content form (that only appears after generation).
    expect(document.body.querySelector('[data-testid="prompt-description"]')).toBeTruthy()
    expect(document.body.querySelector('[data-testid="prompt-generate"]')).toBeTruthy()
    expect(document.body.querySelector('[data-testid="prompt-title"]')).toBeFalsy()

    // Close so the modal body-lock doesn't leak into the next test.
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => b.textContent?.trim() === 'Cancel')?.click()
    await flushPromises()
  })

  it('delete goes through the confirm dialog and fires DELETE only when confirmed', async () => {
    let deleteCalls = 0
    registerEndpoint('/api/prompts/1', {
      method: 'DELETE',
      handler: () => {
        deleteCalls += 1
        return { status: 'ok', deleted: true }
      },
    })
    const c = await mountSuspended(Prompts)
    await flushPromises()

    // Cancelling the confirm must NOT delete.
    await c.find('[data-testid="prompt-delete-1"]').trigger('click')
    await flushPromises()
    const { _state, _resolve } = useConfirm()
    expect(_state.open).toBe(true)
    _resolve(false)
    await flushPromises()
    expect(deleteCalls).toBe(0)

    // Confirming fires the DELETE.
    await c.find('[data-testid="prompt-delete-1"]').trigger('click')
    await flushPromises()
    expect(_state.open).toBe(true)
    _resolve(true)
    await flushPromises()
    expect(deleteCalls).toBe(1)
  })
})
