import { flushPromises, type DOMWrapper } from '@vue/test-utils'

/**
 * Drives a SearchableSelect as the operator does: open it, type, pick. Its popover teleports to the
 * document body, so every read after the open goes through the popover this open added, never one an
 * earlier test left open. Not by its id: reka numbers ids per app and each mount is an app, so an
 * earlier test's open popover can carry the same one (12 did in one file).
 */
interface Finder {
  find: (selector: string) => DOMWrapper<Element>
}

export function picker(w: Finder, label: string): DOMWrapper<Element> {
  return w.find(`[role="combobox"][aria-label="${label}"]`)
}

/** What the picker holds, as a native select's value. */
export function pickerValue(w: Finder, label: string): string | undefined {
  return picker(w, label).attributes('data-value')
}

/** Opens the picker and returns its popover. */
export async function openPicker(w: Finder, label: string): Promise<HTMLElement> {
  const trigger = picker(w, label)
  if (!trigger.exists()) throw new Error(`no picker named "${label}"`)
  // A click on an open picker closes it, so close it first to make the next click open one.
  if (trigger.attributes('aria-expanded') === 'true') {
    await trigger.trigger('click')
    await flushPromises()
  }
  const before = new Set(document.querySelectorAll('[data-slot="popover-content"]'))
  await trigger.trigger('click')
  await flushPromises()
  const opened = [...document.querySelectorAll<HTMLElement>('[data-slot="popover-content"]')].filter(e => !before.has(e))
  if (opened.length !== 1) throw new Error(`the "${label}" picker opened ${opened.length} popovers, not one`)
  return opened[0]!
}

export async function typeInPicker(popover: HTMLElement, text: string): Promise<void> {
  const input = popover.querySelector<HTMLInputElement>('[data-slot="command-input"]')!
  input.value = text
  input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

export async function closePicker(popover: HTMLElement): Promise<void> {
  popover.querySelector('[data-slot="command-input"]')!
    .dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  await flushPromises()
}

function optionElements(popover: HTMLElement): HTMLElement[] {
  return [...popover.querySelectorAll<HTMLElement>('[data-slot="command-item"]')]
}

/** Each option shown, as the operator reads it: its label, then its detail when it has one. */
export function pickerOptions(popover: HTMLElement): string[] {
  return optionElements(popover).map(e => [...e.querySelectorAll('span')].map(s => s.textContent!.trim()).join(' · '))
}

export function pickerOptionValues(popover: HTMLElement): string[] {
  return optionElements(popover).map(e => e.dataset.value!)
}

export function pickerOption(popover: HTMLElement, value: string): HTMLElement | undefined {
  return optionElements(popover).find(e => e.dataset.value === value)
}

export interface PickerItem {
  value: string
  text: string
  disabled: boolean
  /** The heading of the group it is listed under, '' outside any. */
  group: string
}

/** Every option shown, in order, with what an operator can tell about it. */
export function pickerItems(popover: HTMLElement): PickerItem[] {
  return optionElements(popover).map(e => ({
    value: e.dataset.value!,
    text: [...e.querySelectorAll('span')].map(s => s.textContent!.trim()).join(' · '),
    disabled: e.hasAttribute('data-disabled'),
    group: e.closest('[data-slot="command-group"]')?.querySelector('[data-slot="command-group-heading"]')?.textContent?.trim() ?? '',
  }))
}

/** The headings of the groups shown, in order, '' for options outside any. */
export function pickerGroups(popover: HTMLElement): string[] {
  return [...new Set(pickerItems(popover).map(i => i.group))]
}

/** The value of every disabled option shown. */
export function disabledPickerValues(popover: HTMLElement): string[] {
  return optionElements(popover).filter(e => e.hasAttribute('data-disabled')).map(e => e.dataset.value!)
}

/** Opens the picker and clicks the option holding `value`; fails when the picker does not offer it. */
export async function pickValue(w: Finder, label: string, value: string): Promise<void> {
  const popover = await openPicker(w, label)
  const option = pickerOption(popover, value)
  if (!option) throw new Error(`"${label}" offers no ${value}; it offers ${pickerOptionValues(popover).join(', ')}`)
  option.click()
  await flushPromises()
}
