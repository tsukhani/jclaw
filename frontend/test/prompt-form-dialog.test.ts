import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import PromptFormDialog from '~/components/prompts/PromptFormDialog.vue'

const CATEGORIES = [{ value: 'CODING', label: 'Coding' }]

const EDITING = {
  id: 1,
  title: 'Code Review',
  content: 'Review this code',
  tags: 'review',
  category: 'CODING',
  categoryLabel: 'Coding',
  createdAt: null,
  updatedAt: null,
}

// The dialog teleports to <body>.
function alertText(): string {
  return document.body.querySelector('[data-testid="api-error"]')?.textContent ?? ''
}

/** JCLAW-1131: form validation renders through the one shared error component. */
describe('PromptFormDialog', () => {
  afterEach(() => {
    document.body.replaceChildren()
    vi.restoreAllMocks()
  })

  it('renders a rejected save with its three actionable parts', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    registerEndpoint('/api/prompts/1', {
      method: 'PUT',
      handler: (event) => {
        setResponseStatus(event, 400)
        return {
          type: 'error',
          code: 'invalid_request',
          message: 'Title is already taken.',
          template: {
            whatBroke: 'The request was not in a form the server could accept.',
            whatToCheck: 'The message above names the field or value that was refused.',
            howToRetry: 'Correct that value and submit again.',
          },
        }
      },
    })

    // Opening is what fills the form from `editing`, so mount closed and then open.
    const c = await mountSuspended(PromptFormDialog, {
      props: { open: false, editing: EDITING, categories: CATEGORIES },
      attachTo: document.body,
    })
    await c.setProps({ open: true })
    await flushPromises()

    // The dialog teleports, so the form is not under the component wrapper.
    document.body.querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    await flushPromises()

    expect(alertText()).toContain('Title is already taken.')
    expect(alertText()).toContain('What to check')
    expect(alertText()).toContain('Correct that value and submit again.')
  })

  it('leaves the dialog clean when nothing has failed', async () => {
    await mountSuspended(PromptFormDialog, {
      props: { open: true, editing: EDITING, categories: CATEGORIES },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="api-error"]')).toBeNull()
  })

  // --- JCLAW-1137: a retry control only where repeating the failed call is safe ---

  function rejectWith(event: Parameters<typeof setResponseStatus>[0]) {
    setResponseStatus(event, 500)
    return { type: 'error', code: 'internal_error', message: 'Server hiccup.',
      template: { whatBroke: 'It failed.', whatToCheck: 'Check the log.', howToRetry: 'Try again.' } }
  }
  const retryButton = () => document.body.querySelector('[data-testid="api-error-retry"]')
  const submit = () => document.body.querySelector('form')
    ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  const fill = (id: string, value: string) => {
    const el = document.body.querySelector(`[data-testid="${id}"]`) as HTMLInputElement | null
    if (!el) throw new Error(`no field ${id}`)
    el.value = value
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
  }

  it('offers a retry when an edit fails — a PUT to a known id cannot duplicate', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    registerEndpoint('/api/prompts/1', { method: 'PUT', handler: rejectWith })
    const c = await mountSuspended(PromptFormDialog, {
      props: { open: false, editing: EDITING, categories: CATEGORIES }, attachTo: document.body })
    await c.setProps({ open: true })
    await flushPromises()
    submit()
    await flushPromises()
    await flushPromises()

    expect(alertText()).toContain('Server hiccup.')
    expect(retryButton(), 'an idempotent edit gets a retry').not.toBeNull()
  })

  /**
   * The story's first AC. A create is a POST: if the first attempt succeeded server-side but its
   * response was lost, a retry makes a second prompt. The same error ref serves both operations,
   * which is why the decision is made per call rather than baked into the component.
   */
  it('offers no retry when a create fails — a POST could duplicate the prompt', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    // Create is two steps — describe, generate, then the form — so drive it the way a person does:
    // generation succeeds and fills the form, and only the save that follows fails.
    registerEndpoint('/api/prompts/generate', { method: 'POST', handler: () =>
      ({ title: 'New prompt', category: 'CODING', content: 'Do the thing', tags: '' }) })
    registerEndpoint('/api/prompts', { method: 'POST', handler: rejectWith })
    const c = await mountSuspended(PromptFormDialog, {
      props: { open: false, editing: null, categories: CATEGORIES }, attachTo: document.body })
    await c.setProps({ open: true })
    await flushPromises()
    fill('prompt-description', 'a prompt that reviews code')
    await flushPromises()
    ;(document.body.querySelector('[data-testid="prompt-generate"]') as HTMLButtonElement).click()
    await flushPromises()
    await flushPromises()
    expect(document.body.querySelector('[data-testid="prompt-title"]'),
      'generation moved the dialog to the form step').not.toBeNull()
    submit()
    await flushPromises()
    await flushPromises()

    expect(alertText(), 'the failure itself is still shown').toContain('Server hiccup.')
    expect(retryButton(), 'a non-idempotent create must not offer a retry').toBeNull()
  })

  /** Second AC: a retry that fails again replaces the panel rather than stacking another. */
  it('does not stack error panels when a retry fails again', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    let calls = 0
    registerEndpoint('/api/prompts/1', {
      method: 'PUT',
      handler: (e) => {
        calls++
        return rejectWith(e)
      },
    })
    const c = await mountSuspended(PromptFormDialog, {
      props: { open: false, editing: EDITING, categories: CATEGORIES }, attachTo: document.body })
    await c.setProps({ open: true })
    await flushPromises()
    submit()
    await flushPromises()
    await flushPromises()

    ;(retryButton() as HTMLButtonElement).click()
    await flushPromises()
    await flushPromises()

    expect(calls, 'the retry actually re-sent the request').toBe(2)
    expect(document.body.querySelectorAll('[data-testid="api-error"]').length,
      'one panel, replaced — not two stacked').toBe(1)
  })
})
