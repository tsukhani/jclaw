import { afterEach, describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import SearchableSelect, { type SearchableOption } from '~/components/SearchableSelect.vue'

/**
 * SearchableSelect: a select whose options narrow as the operator types. The popover teleports to
 * the document body, so its contents are read from there.
 */
const OPTIONS: SearchableOption[] = [
  { value: 'ollama-cloud::glm-5.3-flash', label: 'ollama-cloud / glm-5.3-flash', group: 'Subscription' },
  { value: 'ollama-cloud::kimi-k3', label: 'ollama-cloud / kimi-k3', group: 'Subscription' },
  { value: 'openrouter::z-ai/glm-5.3-flash', label: 'openrouter / z-ai/glm-5.3-flash', group: 'Per-token' },
  { value: 'together::DeepSeek-V4.1', label: 'together / DeepSeek-V4.1', group: 'Per-token', disabled: true },
]

const mounted: VueWrapper[] = []

afterEach(() => {
  while (mounted.length) mounted.pop()!.unmount()
})

async function mountSelect(props: Record<string, unknown> = {}) {
  const c = await mountSuspended(SearchableSelect, {
    props: { options: OPTIONS, placeholder: 'Add a model…', label: 'Add a model to the Chat list', ...props },
  })
  mounted.push(c)
  return c
}

async function openPicker(c: VueWrapper) {
  await c.find('button').trigger('click')
  await flushPromises()
}

function filterInput(): HTMLInputElement {
  return document.body.querySelector<HTMLInputElement>('[data-slot="command-input"]')!
}

async function type(text: string) {
  const input = filterInput()
  input.value = text
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

function shownOptions(): string[] {
  return [...document.body.querySelectorAll('[data-slot="command-item"]')].map(e => e.textContent!.trim())
}

function shownGroups(): string[] {
  return [...document.body.querySelectorAll('[data-slot="command-group"]:not([hidden]) [data-slot="command-group-heading"]')]
    .map(e => e.textContent!.trim())
}

function optionNamed(text: string): HTMLElement {
  return [...document.body.querySelectorAll<HTMLElement>('[data-slot="command-item"]')].find(e => e.textContent!.trim() === text)!
}

describe('SearchableSelect', () => {
  it('shows the placeholder, named by its label, when nothing is selected', async () => {
    const c = await mountSelect()
    expect(c.find('button').text()).toBe('Add a model…')
    expect(c.find('button').attributes('aria-label')).toBe('Add a model to the Chat list')
  })

  it('shows the selected option, and names it with the label', async () => {
    const c = await mountSelect({ modelValue: 'ollama-cloud::kimi-k3' })
    expect(c.find('button').text()).toBe('ollama-cloud / kimi-k3')
    expect(c.find('button').attributes('aria-label')).toBe('Add a model to the Chat list: ollama-cloud / kimi-k3')
  })

  it('lists every option under its group, with the filter empty', async () => {
    const c = await mountSelect()
    await openPicker(c)
    expect(filterInput().getAttribute('aria-label')).toBe('Type to filter')
    expect(shownGroups()).toEqual(['Subscription', 'Per-token'])
    expect(shownOptions()).toEqual(OPTIONS.map(o => o.label))
  })

  it('narrows the options to those whose text holds what is typed, whatever its case', async () => {
    const c = await mountSelect()
    await openPicker(c)
    await type('GLM')
    expect(shownOptions()).toEqual(['ollama-cloud / glm-5.3-flash', 'openrouter / z-ai/glm-5.3-flash'])
    await type('kimi')
    expect(shownOptions()).toEqual(['ollama-cloud / kimi-k3'])
    expect(shownGroups()).toEqual(['Subscription'])
  })

  it('says so when nothing matches, and lists everything again once the filter is cleared', async () => {
    const c = await mountSelect({ emptyText: 'No matching models.' })
    await openPicker(c)
    await type('qwen')
    expect(shownOptions()).toEqual([])
    expect(document.body.querySelector('[data-slot="command-empty"]')!.textContent!.trim()).toBe('No matching models.')
    await type('')
    expect(shownOptions()).toEqual(OPTIONS.map(o => o.label))
    expect(document.body.querySelector('[data-slot="command-empty"]')).toBeNull()
  })

  it('shows an option\'s detail beside its label, and matches what is typed against either', async () => {
    const c = await mountSelect({
      options: [
        { value: 'openrouter::z-ai/glm-5.3-flash', label: 'openrouter / Z.ai: GLM 5.3 Flash', detail: 'z-ai/glm-5.3-flash' },
        { value: 'ollama-cloud::kimi-k3', label: 'ollama-cloud / kimi-k3' },
      ],
    })
    await openPicker(c)
    const parts = () => [...document.body.querySelectorAll('[data-slot="command-item"]')]
      .map(e => [...e.querySelectorAll('span')].map(s => s.textContent!.trim()))
    expect(parts()).toEqual([['openrouter / Z.ai: GLM 5.3 Flash', 'z-ai/glm-5.3-flash'], ['ollama-cloud / kimi-k3']])
    await type('z-ai/glm')
    expect(parts()).toEqual([['openrouter / Z.ai: GLM 5.3 Flash', 'z-ai/glm-5.3-flash']])
    await type('GLM 5.3')
    expect(parts()).toEqual([['openrouter / Z.ai: GLM 5.3 Flash', 'z-ai/glm-5.3-flash']])
  })

  it('emits the value of the option picked, and closes', async () => {
    const c = await mountSelect()
    await openPicker(c)
    await type('z-ai')
    optionNamed('openrouter / z-ai/glm-5.3-flash').click()
    await flushPromises()
    expect(c.emitted('update:modelValue')).toEqual([['openrouter::z-ai/glm-5.3-flash']])
    expect(filterInput()).toBeNull()
  })

  it('picks the first match on Enter, so typing alone chooses', async () => {
    const c = await mountSelect()
    await openPicker(c)
    await type('glm')
    filterInput().dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    expect(c.emitted('update:modelValue')).toEqual([['ollama-cloud::glm-5.3-flash']])
  })

  it('does not pick a disabled option', async () => {
    const c = await mountSelect()
    await openPicker(c)
    optionNamed('together / DeepSeek-V4.1').click()
    await flushPromises()
    expect(c.emitted('update:modelValue')).toBeUndefined()
  })

  it('does not open while disabled', async () => {
    const c = await mountSelect({ disabled: true })
    await openPicker(c)
    expect(filterInput()).toBeNull()
  })
})
