import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import FilterBar from '~/components/FilterBar.vue'

describe('FilterBar', () => {
  it('renders with role="search"', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test' },
    })
    expect(component.find('[role="search"]').exists()).toBe(true)
  })

  it('renders input with placeholder', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test', placeholder: 'Search here...' },
    })
    const input = component.find('input[type="text"]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('placeholder')).toBe('Search here...')
  })

  it('renders export button', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test' },
    })
    const exportBtn = component.find('[title="Export"]')
    expect(exportBtn.exists()).toBe(true)
  })

  it('emits export event on button click', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test' },
    })
    await component.find('[title="Export"]').trigger('click')
    expect(component.emitted('export')).toBeTruthy()
  })

  it('parses key:value query and renders chips on Enter', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test-chips' },
    })
    const input = component.find('input[type="text"]')
    await input.setValue('agent:main channel:web')
    await input.trigger('keydown', { key: 'Enter' })
    // Should render two chips
    expect(component.text()).toContain('agent:')
    expect(component.text()).toContain('main')
    expect(component.text()).toContain('channel:')
    expect(component.text()).toContain('web')
  })

  it('emits update:filters with parsed filters on Enter', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test-emit' },
    })
    const input = component.find('input[type="text"]')
    await input.setValue('agent:main')
    await input.trigger('keydown', { key: 'Enter' })
    const emitted = component.emitted('update:filters')
    expect(emitted).toBeTruthy()
    expect(emitted![0][0]).toEqual([{ key: 'agent', value: 'main' }])
  })

  it('treats bare words as name filter', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test-bare' },
    })
    const input = component.find('input[type="text"]')
    await input.setValue('hello')
    await input.trigger('keydown', { key: 'Enter' })
    const emitted = component.emitted('update:filters')
    expect(emitted![0][0]).toEqual([{ key: 'name', value: 'hello' }])
  })

  it('removes a chip when × is clicked', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test-remove' },
    })
    const input = component.find('input[type="text"]')
    await input.setValue('agent:main channel:web')
    await input.trigger('keydown', { key: 'Enter' })
    // Find and click the first × button
    const removeButtons = component.findAll('[aria-label^="Remove filter"]')
    expect(removeButtons.length).toBe(2)
    await removeButtons[0].trigger('click')
    // Should have emitted with only one filter remaining
    const emitted = component.emitted('update:filters')
    const lastEmit = emitted![emitted!.length - 1][0] as any[]
    expect(lastEmit.length).toBe(1)
    expect(lastEmit[0].key).toBe('channel')
  })

  it('renders saved views button', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test-saved' },
    })
    const savedBtn = component.find('[title="Saved views"]')
    expect(savedBtn.exists()).toBe(true)
  })

  it('chips have aria-label attributes', async () => {
    const component = await mountSuspended(FilterBar, {
      props: { storageKey: 'test-aria' },
    })
    const input = component.find('input[type="text"]')
    await input.setValue('agent:main')
    await input.trigger('keydown', { key: 'Enter' })
    const chip = component.find('[aria-label="Filter: agent is main"]')
    expect(chip.exists()).toBe(true)
  })
})
