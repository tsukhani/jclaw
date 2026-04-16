import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import StatusBanner from '~/components/StatusBanner.vue'

describe('StatusBanner', () => {
  it('renders with role="alert" and aria-live', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'API offline' },
    })
    const alert = component.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.attributes('aria-live')).toBe('assertive')
  })

  it('displays the message', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Provider unreachable' },
    })
    expect(component.text()).toContain('Provider unreachable')
  })

  it('renders action button when actionText is provided', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Offline', actionText: 'Retry' },
    })
    expect(component.text()).toContain('Retry')
  })

  it('emits action event on action button click', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Offline', actionText: 'Retry' },
    })
    const btn = component.findAll('button').find(b => b.text() === 'Retry')
    await btn!.trigger('click')
    expect(component.emitted('action')).toBeTruthy()
  })

  it('does not show action button when actionText is empty', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Info message' },
    })
    const buttons = component.findAll('button')
    expect(buttons.length).toBe(0)
  })

  it('shows dismiss button only when dismissable', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Transient error', dismissable: true },
    })
    const dismissBtn = component.find('[aria-label="Dismiss"]')
    expect(dismissBtn.exists()).toBe(true)
  })

  it('does not show dismiss button when not dismissable', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Persistent error', dismissable: false },
    })
    const dismissBtn = component.find('[aria-label="Dismiss"]')
    expect(dismissBtn.exists()).toBe(false)
  })

  it('hides banner after dismiss click', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Dismissable', dismissable: true },
    })
    await component.find('[aria-label="Dismiss"]').trigger('click')
    expect(component.find('[role="alert"]').exists()).toBe(false)
    expect(component.emitted('dismiss')).toBeTruthy()
  })

  it('applies warning variant styles by default', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Warning' },
    })
    const alert = component.find('[role="alert"]')
    expect(alert.classes()).toContain('bg-amber-50')
  })

  it('applies error variant styles', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Error', variant: 'error' },
    })
    const alert = component.find('[role="alert"]')
    expect(alert.classes()).toContain('bg-red-50')
  })

  it('applies info variant styles', async () => {
    const component = await mountSuspended(StatusBanner, {
      props: { message: 'Info', variant: 'info' },
    })
    const alert = component.find('[role="alert"]')
    expect(alert.classes()).toContain('bg-blue-50')
  })
})

describe('Alert component exports', () => {
  it('exports all alert subcomponents', async () => {
    const alert = await import('~/components/ui/alert')
    expect(alert.Alert).toBeDefined()
    expect(alert.AlertTitle).toBeDefined()
    expect(alert.AlertDescription).toBeDefined()
  })
})
