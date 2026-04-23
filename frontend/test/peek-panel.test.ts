import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { nextTick } from 'vue'
import PeekPanel from '~/components/PeekPanel.vue'

afterEach(() => {
  document.body.querySelectorAll('[data-slot="sheet-content"]').forEach(el => el.remove())
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

describe('PeekPanel', () => {
  it('renders sheet content when open', async () => {
    await mountSuspended(PeekPanel, {
      props: { open: true, title: 'Test Panel' },
    })
    await nextTick()
    const content = document.body.querySelector('[data-slot="sheet-content"]')
    expect(content).not.toBeNull()
    expect(document.body.textContent).toContain('Test Panel')
  })

  it('does not render when closed', async () => {
    await mountSuspended(PeekPanel, {
      props: { open: false, title: 'Hidden' },
    })
    await nextTick()
    const content = document.body.querySelector('[data-slot="sheet-content"]')
    expect(content).toBeNull()
  })

  it('renders description when provided', async () => {
    await mountSuspended(PeekPanel, {
      props: { open: true, title: 'Details', description: 'Some context' },
    })
    await nextTick()
    expect(document.body.textContent).toContain('Some context')
  })

  it('has a resize handle', async () => {
    await mountSuspended(PeekPanel, {
      props: { open: true, title: 'Resize Test' },
    })
    await nextTick()
    const handle = document.body.querySelector('.cursor-col-resize')
    expect(handle).not.toBeNull()
  })

  it('renders slot content', async () => {
    await mountSuspended(PeekPanel, {
      props: { open: true, title: 'Slot Test' },
      slots: { default: () => 'Custom slot content here' },
    })
    await nextTick()
    expect(document.body.textContent).toContain('Custom slot content here')
  })
})
