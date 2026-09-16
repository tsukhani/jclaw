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
            whatToCheck: 'Check the fields you submitted for missing or malformed values.',
            howToRetry: 'Correct the highlighted fields and submit again.',
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
    expect(alertText()).toContain('Correct the highlighted fields and submit again.')
  })

  it('leaves the dialog clean when nothing has failed', async () => {
    await mountSuspended(PromptFormDialog, {
      props: { open: true, editing: EDITING, categories: CATEGORIES },
      attachTo: document.body,
    })
    await flushPromises()

    expect(document.body.querySelector('[data-testid="api-error"]')).toBeNull()
  })
})
