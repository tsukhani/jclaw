import { afterEach, describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import SearchableSelect, { type SearchableOption } from '~/components/SearchableSelect.vue'
import {
  closePicker, disabledPickerValues, openPicker, picker, pickerOption, pickerOptions, pickerValue, typeInPicker,
} from './searchable-select-driver'

/** SearchableSelect: a select whose options narrow as the operator types. */
const LABEL = 'Add a model to the Chat list'
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
    props: { options: OPTIONS, placeholder: 'Add a model…', label: LABEL, ...props },
  })
  mounted.push(c)
  return c
}

function groupsShown(popover: HTMLElement): string[] {
  return [...popover.querySelectorAll('[data-slot="command-group"]:not([hidden]) [data-slot="command-group-heading"]')]
    .map(e => e.textContent!.trim())
}

describe('SearchableSelect', () => {
  it('is a combobox named by its label, showing the placeholder while nothing is selected', async () => {
    const c = await mountSelect()
    expect(picker(c, LABEL).text()).toBe('Add a model…')
    expect(pickerValue(c, LABEL)).toBe('')
  })

  it('shows the selected option\'s label as its value', async () => {
    const c = await mountSelect({ modelValue: 'ollama-cloud::kimi-k3' })
    expect(picker(c, LABEL).text()).toBe('ollama-cloud / kimi-k3')
    expect(pickerValue(c, LABEL)).toBe('ollama-cloud::kimi-k3')
  })

  it('shows a stored value no option holds as itself, not as the placeholder', async () => {
    const c = await mountSelect({ modelValue: 'gone::model' })
    expect(picker(c, LABEL).text()).toBe('gone::model')
    expect(pickerValue(c, LABEL)).toBe('gone::model')
  })

  it('lists every option under its group, with the filter empty', async () => {
    const c = await mountSelect()
    const popover = await openPicker(c, LABEL)
    expect(popover.querySelector('[data-slot="command-input"]')!.getAttribute('aria-label')).toBe('Type to filter')
    expect(groupsShown(popover)).toEqual(['Subscription', 'Per-token'])
    expect(pickerOptions(popover)).toEqual(OPTIONS.map(o => o.label))
    expect(disabledPickerValues(popover)).toEqual(['together::DeepSeek-V4.1'])
  })

  it('narrows the options to those whose text holds what is typed, whatever its case', async () => {
    const c = await mountSelect()
    const popover = await openPicker(c, LABEL)
    await typeInPicker(popover, 'GLM')
    expect(pickerOptions(popover)).toEqual(['ollama-cloud / glm-5.3-flash', 'openrouter / z-ai/glm-5.3-flash'])
    await typeInPicker(popover, 'kimi')
    expect(pickerOptions(popover)).toEqual(['ollama-cloud / kimi-k3'])
    expect(groupsShown(popover)).toEqual(['Subscription'])
  })

  it('says so when nothing matches, and lists everything again once the filter is cleared', async () => {
    const c = await mountSelect({ emptyText: 'No matching models.' })
    const popover = await openPicker(c, LABEL)
    await typeInPicker(popover, 'qwen')
    expect(pickerOptions(popover)).toEqual([])
    expect(popover.querySelector('[data-slot="command-empty"]')!.textContent!.trim()).toBe('No matching models.')
    await typeInPicker(popover, '')
    expect(pickerOptions(popover)).toEqual(OPTIONS.map(o => o.label))
    expect(popover.querySelector('[data-slot="command-empty"]')).toBeNull()
  })

  it('shows an option\'s detail beside its label, and matches what is typed against either', async () => {
    const c = await mountSelect({
      options: [
        { value: 'openrouter::z-ai/glm-5.3-flash', label: 'openrouter / Z.ai: GLM 5.3 Flash', detail: 'z-ai/glm-5.3-flash' },
        { value: 'ollama-cloud::kimi-k3', label: 'ollama-cloud / kimi-k3' },
      ],
    })
    const popover = await openPicker(c, LABEL)
    expect(pickerOptions(popover)).toEqual(['openrouter / Z.ai: GLM 5.3 Flash · z-ai/glm-5.3-flash', 'ollama-cloud / kimi-k3'])
    await typeInPicker(popover, 'z-ai/glm')
    expect(pickerOptions(popover)).toEqual(['openrouter / Z.ai: GLM 5.3 Flash · z-ai/glm-5.3-flash'])
    await typeInPicker(popover, 'GLM 5.3')
    expect(pickerOptions(popover)).toEqual(['openrouter / Z.ai: GLM 5.3 Flash · z-ai/glm-5.3-flash'])
  })

  it('emits the value of the option picked, and closes', async () => {
    const c = await mountSelect()
    const popover = await openPicker(c, LABEL)
    await typeInPicker(popover, 'z-ai')
    pickerOption(popover, 'openrouter::z-ai/glm-5.3-flash')!.click()
    await flushPromises()
    expect(c.emitted('update:modelValue')).toEqual([['openrouter::z-ai/glm-5.3-flash']])
    expect(document.body.contains(popover)).toBe(false)
  })

  it('picks the first match on Enter, so typing alone chooses', async () => {
    const c = await mountSelect()
    const popover = await openPicker(c, LABEL)
    await typeInPicker(popover, 'glm')
    popover.querySelector('[data-slot="command-input"]')!
      .dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    await flushPromises()
    expect(c.emitted('update:modelValue')).toEqual([['ollama-cloud::glm-5.3-flash']])
  })

  it('closes on Escape without picking', async () => {
    const c = await mountSelect()
    const popover = await openPicker(c, LABEL)
    await closePicker(popover)
    expect(document.body.contains(popover)).toBe(false)
    expect(c.emitted('update:modelValue')).toBeUndefined()
  })

  it('does not pick a disabled option', async () => {
    const c = await mountSelect()
    const popover = await openPicker(c, LABEL)
    pickerOption(popover, 'together::DeepSeek-V4.1')!.click()
    await flushPromises()
    expect(c.emitted('update:modelValue')).toBeUndefined()
  })

  it('does not open while disabled', async () => {
    const c = await mountSelect({ disabled: true })
    await picker(c, LABEL).trigger('click')
    await flushPromises()
    expect(picker(c, LABEL).attributes('aria-expanded')).toBe('false')
    expect(document.body.querySelector('[data-slot="command-input"]')).toBeNull()
  })
})
