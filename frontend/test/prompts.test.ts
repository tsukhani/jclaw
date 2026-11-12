import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { useConfirm } from '~/composables/useConfirm'
import Prompts from '~/pages/prompts.vue'
import type { Prompt } from '~/types/api'

/**
 * Prompts Library page (JCLAW-813): rendering, client-side search + category
 * filter, the Run → chat-composer hand-off (?compose=), delete-behind-confirm, and what a save sends.
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

describe('Prompts page — a failed delete (JCLAW-1221)', () => {
  beforeEach(() => {
    clearNuxtData()
    registerEndpoint('/api/prompts', () => samplePrompts())
    registerEndpoint('/api/prompts/categories', () => CATEGORIES)
  })

  afterEach(() => {
    const { _state, _resolve } = useConfirm()
    if (_state.open) _resolve(false)
  })

  it('says why the prompt was not removed', async () => {
    const off = registerEndpoint('/api/prompts/1', {
      method: 'DELETE',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const c = await mountSuspended(Prompts)
      await vi.waitFor(() => expect(c.find('[data-testid="prompt-delete-1"]').exists()).toBe(true))
      await c.find('[data-testid="prompt-delete-1"]').trigger('click')
      await flushPromises()
      useConfirm()._resolve(true)
      await vi.waitFor(() => expect(c.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(c.find('[data-testid="api-error"]').text()).toContain('/api/prompts/1')
    }
    finally {
      off()
    }
  })
})

describe('Prompts page — a failed import (JCLAW-1221)', () => {
  beforeEach(() => {
    clearNuxtData()
    registerEndpoint('/api/prompts', () => samplePrompts())
    registerEndpoint('/api/prompts/categories', () => CATEGORIES)
  })

  it('says why and keeps the mode picker so the import can be retried', async () => {
    const off = registerEndpoint('/api/prompts/import', {
      method: 'POST',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const c = await mountSuspended(Prompts)
      await flushPromises()
      ;(c.vm as unknown as { pendingImport: { prompts: unknown[] } | null }).pendingImport = { prompts: [{ title: 'X', content: 'y' }] }
      await flushPromises()
      await c.find('[data-testid="import-mode-picker"]').findAll('button').find(b => b.text().includes('Merge'))!.trigger('click')
      await vi.waitFor(() => expect(c.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(c.find('[data-testid="api-error"]').text()).toContain('/api/prompts/import')
      expect(c.find('[data-testid="import-mode-picker"]').exists()).toBe(true)
    }
    finally {
      off()
    }
  })
})

describe('Prompts page — what a save sends (JF-314)', () => {
  const GENERATED = { title: 'Standup Notes', category: 'WRITING', content: 'Summarize the standup', tags: 'meetings' }

  let prompts: Prompt[] = []
  let posted: Record<string, unknown>[] = []
  let put: Record<string, unknown>[] = []
  let page: VueWrapper | undefined

  beforeEach(() => {
    clearNuxtData()
    prompts = samplePrompts()
    posted = []
    put = []
    registerEndpoint('/api/prompts/categories', () => CATEGORIES)
    registerEndpoint('/api/prompts/generate', { method: 'POST', handler: () => GENERATED })
    registerEndpoint('/api/prompts', {
      handler: async (event) => {
        if (event.method !== 'POST') return prompts
        const { readBody } = await import('h3')
        const body = await readBody(event) as Record<string, unknown>
        posted.push(body)
        const saved: Prompt = {
          id: 3, title: String(body.title), content: String(body.content), tags: String(body.tags),
          category: 'WRITING', categoryLabel: 'Writing', createdAt: null, updatedAt: null,
        }
        prompts = [...prompts, saved]
        return saved
      },
    })
    registerEndpoint('/api/prompts/1', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        const body = await readBody(event) as Record<string, unknown>
        put.push(body)
        prompts = prompts.map(p => (p.id === 1 ? { ...p, title: String(body.title) } : p))
        return prompts.find(p => p.id === 1)
      },
    })
  })

  afterEach(() => {
    page?.unmount()
    page = undefined
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
    document.body.style.pointerEvents = ''
  })

  /** A control of the prompt dialog, which is teleported to the body. */
  function control<T extends HTMLElement>(testid: string): T | null {
    return document.body.querySelector<T>(`[data-testid="${testid}"]`)
  }

  async function type(testid: string, value: string) {
    const el = control<HTMLInputElement | HTMLTextAreaElement>(testid)!
    el.value = value
    el.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
  }

  it('creates a prompt with its title, text, category and tags only, from a form with no Owner field', async () => {
    page = await mountSuspended(Prompts)
    await flushPromises()
    await page.find('[data-testid="new-prompt-button"]').trigger('click')
    await flushPromises()
    await type('prompt-description', 'notes for the daily standup')
    control<HTMLButtonElement>('prompt-generate')!.click()
    await vi.waitFor(() => expect(control('prompt-title')).toBeTruthy())

    expect(control('prompt-form-owner')).toBeNull()
    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(posted).toHaveLength(1))

    expect(posted[0]).toEqual(GENERATED)
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-card-3"]').exists()).toBe(true))
  })

  it('saves an edit with the fields of the prompt only', async () => {
    page = await mountSuspended(Prompts)
    await flushPromises()
    await page.find('[data-testid="prompt-edit-1"]').trigger('click')
    await flushPromises()

    expect(control('prompt-form-owner')).toBeNull()
    await type('prompt-title', 'Code Review v2')
    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(put).toHaveLength(1))

    expect(put[0]).toEqual({
      title: 'Code Review v2', content: 'Review this code', category: 'CODING', tags: 'review, quality',
    })
    expect(posted).toEqual([])
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-card-1"]').text()).toContain('Code Review v2'))
  })
})
