import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { useConfirm } from '~/composables/useConfirm'
import Prompts from '~/pages/prompts.vue'
import type { Prompt } from '~/types/api'

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

// The rows of GET /api/agents the Owner field reads (JF-307).
const AGENTS = [
  { id: 1, name: 'main', isMain: true },
  { id: 7, name: 'research-bot', isMain: false },
]

function samplePrompts(): Prompt[] {
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
    registerEndpoint('/api/agents', () => AGENTS)
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

  it('names the agent that owns an agent\'s prompt and marks none on the operator\'s', async () => {
    prompts = [
      { ...samplePrompts()[0]!, ownerAgentId: 7, ownerAgentName: 'research-bot' },
      { ...samplePrompts()[1]!, ownerAgentId: null, ownerAgentName: null },
    ]
    const c = await mountSuspended(Prompts)
    await flushPromises()

    const owner = c.find('[data-testid="prompt-owner-1"]')
    expect(owner.exists()).toBe(true)
    expect(owner.text()).toBe('Agent: research-bot')
    expect(owner.attributes('title')).toBe('Owned by the agent research-bot')
    expect(c.find('[data-testid="prompt-owner-2"]').exists()).toBe(false)
  })

  it('marks no owner on a prompt whose row carries no owner fields', async () => {
    const c = await mountSuspended(Prompts)
    await flushPromises()

    expect(c.find('[data-testid="prompt-card-1"]').exists()).toBe(true)
    expect(c.find('[data-testid^="prompt-owner-"]').exists()).toBe(false)
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
    registerEndpoint('/api/agents', () => AGENTS)
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
    registerEndpoint('/api/agents', () => AGENTS)
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

describe('Prompts page — the Owner field (JF-307, JF-308)', () => {
  const GENERATED = { title: 'Standup Notes', category: 'WRITING', content: 'Summarize the standup', tags: 'meetings' }
  const OPERATOR = 'You (the operator)'
  const NEW_PROMPT_HINT
    = 'An agent that owns a prompt can read, change and delete it. You can change the owner later, when you edit the prompt.'
  const EDIT_HINT
    = 'An agent that owns a prompt can read, change and delete it. To give this prompt to another owner, pick the owner and click Save.'

  let prompts: Prompt[] = []
  let posted: Record<string, unknown>[] = []
  let put: Record<string, unknown>[] = []
  let agentsDown = false
  let page: VueWrapper | undefined

  beforeEach(() => {
    clearNuxtData()
    prompts = [
      { ...samplePrompts()[0]!, ownerAgentId: 7, ownerAgentName: 'research-bot' },
      { ...samplePrompts()[1]!, ownerAgentId: null, ownerAgentName: null },
    ]
    posted = []
    put = []
    agentsDown = false
    registerEndpoint('/api/prompts/categories', () => CATEGORIES)
    registerEndpoint('/api/agents', async (event) => {
      if (!agentsDown) return AGENTS
      const { setResponseStatus } = await import('h3')
      setResponseStatus(event, 502)
      return '<html><body>Bad Gateway</body></html>'
    })
    registerEndpoint('/api/prompts/generate', { method: 'POST', handler: () => GENERATED })
    registerEndpoint('/api/prompts', {
      handler: async (event) => {
        if (event.method !== 'POST') return prompts
        const { readBody } = await import('h3')
        const body = await readBody(event) as Record<string, unknown>
        posted.push(body)
        // As the server answers: the named agent owns the prompt, and no name leaves it the operator's.
        const owner = AGENTS.find(a => a.id === body.ownerAgentId)
        const saved: Prompt = {
          id: 3, title: String(body.title), content: String(body.content), tags: String(body.tags),
          category: 'WRITING', categoryLabel: 'Writing',
          ownerAgentId: owner?.id ?? null, ownerAgentName: owner?.name ?? null, createdAt: null, updatedAt: null,
        }
        prompts = [...prompts, saved]
        return saved
      },
    })
    for (const id of [1, 2]) {
      registerEndpoint(`/api/prompts/${id}`, {
        method: 'PUT',
        handler: async (event) => {
          const { readBody } = await import('h3')
          const body = await readBody(event) as Record<string, unknown>
          put.push(body)
          // As the server answers: a sent owner replaces the prompt's, null making it the
          // operator's, and a body with no owner leaves the owner as it was.
          prompts = prompts.map((p) => {
            if (p.id !== id) return p
            const edited = { ...p, title: String(body.title) }
            if (!('ownerAgentId' in body)) return edited
            const owner = AGENTS.find(a => a.id === body.ownerAgentId)
            return { ...edited, ownerAgentId: owner?.id ?? null, ownerAgentName: owner?.name ?? null }
          })
          return prompts.find(p => p.id === id)
        },
      })
    }
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

  /** Picks an owner as a person does, by the option's text. */
  async function pickOwner(text: string) {
    const select = control<HTMLSelectElement>('prompt-form-owner')!
    select.selectedIndex = Array.from(select.options).findIndex(o => o.textContent?.trim() === text)
    select.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()
  }

  function ownerOptions(): (string | undefined)[] {
    return Array.from(control<HTMLSelectElement>('prompt-form-owner')!.options).map(o => o.textContent?.trim())
  }

  function selectedOwner(): string | undefined {
    return control<HTMLSelectElement>('prompt-form-owner')!.selectedOptions[0]?.textContent?.trim()
  }

  /** New prompt → describe → Generate, which is the only way to the form for a new prompt. */
  async function openNewPromptForm() {
    page = await mountSuspended(Prompts)
    await flushPromises()
    await page.find('[data-testid="new-prompt-button"]').trigger('click')
    await flushPromises()
    await describeAndGenerate()
  }

  async function describeAndGenerate() {
    await type('prompt-description', 'notes for the daily standup')
    control<HTMLButtonElement>('prompt-generate')!.click()
    await vi.waitFor(() => expect(control('prompt-title')).toBeTruthy())
  }

  async function openEditForm(id: number) {
    page = await mountSuspended(Prompts)
    await flushPromises()
    await page.find(`[data-testid="prompt-edit-${id}"]`).trigger('click')
    await flushPromises()
  }

  it('offers you and each agent as the owner of a new prompt, with you selected at first', async () => {
    await openNewPromptForm()

    expect(document.body.querySelector('label[for="pf-owner"] span')?.textContent?.trim()).toBe('Owner')
    expect(ownerOptions()).toEqual([OPERATOR, 'main', 'research-bot'])
    expect(selectedOwner()).toBe(OPERATOR)
    expect(document.body.querySelector('#pf-owner-hint')?.textContent?.trim())
      .toBe(NEW_PROMPT_HINT)
  })

  it('sends no owner when the owner is left at you, and the new card names no agent', async () => {
    await openNewPromptForm()

    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(posted).toHaveLength(1))

    expect(posted[0]).toEqual({
      title: 'Standup Notes', content: 'Summarize the standup', category: 'WRITING', tags: 'meetings',
    })
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-card-3"]').exists()).toBe(true))
    expect(page!.find('[data-testid="prompt-owner-3"]').exists()).toBe(false)
    expect(control('prompt-title')).toBeNull()
  })

  it('sends the picked agent as ownerAgentId, and the new card names that agent', async () => {
    await openNewPromptForm()

    await pickOwner('research-bot')
    expect(selectedOwner()).toBe('research-bot')
    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(posted).toHaveLength(1))

    expect(posted[0]).toEqual({
      title: 'Standup Notes', content: 'Summarize the standup', category: 'WRITING', tags: 'meetings',
      ownerAgentId: 7,
    })
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-owner-3"]').exists()).toBe(true))
    expect(page!.find('[data-testid="prompt-owner-3"]').text()).toBe('Agent: research-bot')
  })

  it('sends no owner when an agent was picked and then you again', async () => {
    await openNewPromptForm()

    await pickOwner('research-bot')
    await pickOwner(OPERATOR)
    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(posted).toHaveLength(1))

    expect(posted[0]).toEqual({
      title: 'Standup Notes', content: 'Summarize the standup', category: 'WRITING', tags: 'meetings',
    })
  })

  it('starts the owner at you again each time the form opens', async () => {
    await openNewPromptForm()
    await pickOwner('research-bot')
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => b.textContent?.trim() === 'Cancel')!.click()
    await flushPromises()

    await page!.find('[data-testid="new-prompt-button"]').trigger('click')
    await flushPromises()
    await describeAndGenerate()

    expect(selectedOwner()).toBe(OPERATOR)
    expect(posted).toEqual([])
  })

  it('still offers you as the owner, and saves your prompt, when the agents cannot be listed', async () => {
    agentsDown = true
    await openNewPromptForm()

    expect(ownerOptions()).toEqual([OPERATOR])
    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(posted).toHaveLength(1))

    expect(posted[0]).toEqual({
      title: 'Standup Notes', content: 'Summarize the standup', category: 'WRITING', tags: 'meetings',
    })
  })

  // --- the edit form (JF-308) ---

  async function cancel() {
    Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => b.textContent?.trim() === 'Cancel')!.click()
    await flushPromises()
  }

  async function save() {
    control<HTMLButtonElement>('prompt-save')!.click()
    await vi.waitFor(() => expect(put).toHaveLength(1))
  }

  it('sets the owner of an agent\'s prompt being edited to that agent, in the list a new prompt offers', async () => {
    await openEditForm(1)

    expect(document.body.querySelector('label[for="pf-owner"] span')?.textContent?.trim()).toBe('Owner')
    expect(ownerOptions()).toEqual([OPERATOR, 'main', 'research-bot'])
    expect(selectedOwner()).toBe('research-bot')
    expect(document.body.querySelector('#pf-owner-hint')?.textContent?.trim()).toBe(EDIT_HINT)
  })

  it('sets the owner of your own prompt being edited to you', async () => {
    await openEditForm(2)

    expect(ownerOptions()).toEqual([OPERATOR, 'main', 'research-bot'])
    expect(selectedOwner()).toBe(OPERATOR)
  })

  it('sends no owner when an edit leaves the owner alone, and the card still names the agent', async () => {
    await openEditForm(1)

    await type('prompt-title', 'Code Review v2')
    await save()

    expect(put[0]).toEqual({
      title: 'Code Review v2', content: 'Review this code', category: 'CODING', tags: 'review, quality',
    })
    expect(posted).toEqual([])
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-card-1"]').text()).toContain('Code Review v2'))
    expect(page!.find('[data-testid="prompt-owner-1"]').text()).toBe('Agent: research-bot')
    expect(control('prompt-title')).toBeNull()
  })

  it('sends no owner when an edit leaves your own prompt with you', async () => {
    await openEditForm(2)

    await save()

    expect(put[0]).toEqual({
      title: 'Blog Outline', content: 'Outline a blog post', category: 'WRITING', tags: 'writing',
    })
  })

  it('sends the picked agent as ownerAgentId when an edit gives your prompt to an agent, and the card then names it', async () => {
    await openEditForm(2)

    await pickOwner('research-bot')
    expect(selectedOwner()).toBe('research-bot')
    await save()

    expect(put[0]).toEqual({
      title: 'Blog Outline', content: 'Outline a blog post', category: 'WRITING', tags: 'writing',
      ownerAgentId: 7,
    })
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-owner-2"]').exists()).toBe(true))
    expect(page!.find('[data-testid="prompt-owner-2"]').text()).toBe('Agent: research-bot')
  })

  it('sends the picked agent as ownerAgentId when an edit moves an agent\'s prompt to another agent', async () => {
    await openEditForm(1)

    await pickOwner('main')
    await save()

    expect(put[0]).toEqual({
      title: 'Code Review', content: 'Review this code', category: 'CODING', tags: 'review, quality',
      ownerAgentId: 1,
    })
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-owner-1"]').text()).toBe('Agent: main'))
  })

  it('sends a null ownerAgentId when an edit gives an agent\'s prompt to you, and the card then names no agent', async () => {
    await openEditForm(1)

    await pickOwner(OPERATOR)
    expect(selectedOwner()).toBe(OPERATOR)
    await save()

    expect(put[0]).toEqual({
      title: 'Code Review', content: 'Review this code', category: 'CODING', tags: 'review, quality',
      ownerAgentId: null,
    })
    await vi.waitFor(() => expect(page!.find('[data-testid="prompt-owner-1"]').exists()).toBe(false))
  })

  it('sends no owner when an edit picks another owner and then the current one again', async () => {
    await openEditForm(1)

    await pickOwner('main')
    await pickOwner('research-bot')
    await save()

    expect(put[0]).toEqual({
      title: 'Code Review', content: 'Review this code', category: 'CODING', tags: 'review, quality',
    })
  })

  it('keeps an owner the agent list leaves out as the selected owner, and sends none when it is left alone', async () => {
    prompts[0] = { ...prompts[0]!, ownerAgentId: 42, ownerAgentName: 'research-bot-helper' }
    await openEditForm(1)

    expect(ownerOptions()).toEqual([OPERATOR, 'main', 'research-bot', 'research-bot-helper'])
    expect(selectedOwner()).toBe('research-bot-helper')
    await save()

    expect(put[0]).toEqual({
      title: 'Code Review', content: 'Review this code', category: 'CODING', tags: 'review, quality',
    })
  })

  it('keeps the current owner selected when the agents cannot be listed, and can still give the prompt to you', async () => {
    agentsDown = true
    await openEditForm(1)

    expect(ownerOptions()).toEqual([OPERATOR, 'research-bot'])
    expect(selectedOwner()).toBe('research-bot')
    await pickOwner(OPERATOR)
    await save()

    expect(put[0]).toEqual({
      title: 'Code Review', content: 'Review this code', category: 'CODING', tags: 'review, quality',
      ownerAgentId: null,
    })
  })

  it('sets the owner to the prompt\'s current owner again each time the edit form opens', async () => {
    await openEditForm(1)
    await pickOwner('main')
    await cancel()

    await page!.find('[data-testid="prompt-edit-1"]').trigger('click')
    await flushPromises()
    expect(selectedOwner()).toBe('research-bot')
    await cancel()

    await page!.find('[data-testid="prompt-edit-2"]').trigger('click')
    await flushPromises()
    expect(selectedOwner()).toBe(OPERATOR)
    expect(put).toEqual([])
  })
})
