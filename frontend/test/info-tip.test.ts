import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import InfoTip from '~/components/InfoTip.vue'

// The CSS hover tips it replaced were unreachable by keyboard and could not be dismissed (WCAG 1.4.13).
describe('InfoTip', () => {
  afterEach(() => {
    document.body.replaceChildren()
  })

  const tipText = () => [...document.querySelectorAll('[role="tooltip"]')].map(e => e.textContent).join(' ')

  it('renders a focusable, labelled trigger and no tip content until opened', async () => {
    const c = await mountSuspended(InfoTip, { props: { label: 'About maxToolRounds' }, slots: { default: () => 'Hard cap on tool rounds' } })
    const trigger = c.find('button')
    expect(trigger.attributes('type')).toBe('button')
    expect(trigger.attributes('aria-label')).toBe('About maxToolRounds')
    expect(tipText()).not.toContain('Hard cap on tool rounds')
  })

  it('opens on keyboard focus and closes on Escape', async () => {
    const c = await mountSuspended(InfoTip, { props: { label: 'About maxToolRounds' }, slots: { default: () => 'Hard cap on tool rounds' }, attachTo: document.body })
    const trigger = c.find('button').element as HTMLButtonElement
    trigger.focus()
    trigger.dispatchEvent(new FocusEvent('focus'))
    await flushPromises()
    await nextTick()
    expect(tipText()).toContain('Hard cap on tool rounds')

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    await nextTick()
    expect(tipText()).not.toContain('Hard cap on tool rounds')
  })
})
