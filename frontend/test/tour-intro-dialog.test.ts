import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import TourIntroDialog from '~/components/TourIntroDialog.vue'

// shadcn Dialog renders its content via reka-ui's <DialogPortal>, which
// teleports to document.body. That puts the title/body/buttons outside
// the mounted wrapper's element tree, so we query document.body directly.
function bodyText(): string {
  return document.body.textContent ?? ''
}

function findButton(label: string): HTMLButtonElement | undefined {
  const buttons = Array.from(document.body.querySelectorAll('button'))
  return buttons.find(b => (b.textContent ?? '').includes(label)) as HTMLButtonElement | undefined
}

afterEach(() => {
  // Clean up any teleported nodes between tests so DOM lookups stay scoped
  // to the current mount.
  document.body.replaceChildren()
})

describe('TourIntroDialog', () => {
  it('renders title and body when open', async () => {
    await mountSuspended(TourIntroDialog, {
      props: { open: true },
    })
    expect(bodyText()).toContain('Welcome to JClaw')
    expect(bodyText()).toContain('Start tour')
    expect(bodyText()).toContain('Skip for now')
  })

  it('emits start when the primary button is clicked', async () => {
    const wrapper = await mountSuspended(TourIntroDialog, {
      props: { open: true },
    })
    const startBtn = findButton('Start tour')
    expect(startBtn).toBeTruthy()
    startBtn!.click()
    expect(wrapper.emitted('start')).toBeTruthy()
    expect(wrapper.emitted('start')!.length).toBe(1)
  })

  it('emits skip when the secondary button is clicked', async () => {
    const wrapper = await mountSuspended(TourIntroDialog, {
      props: { open: true },
    })
    const skipBtn = findButton('Skip for now')
    expect(skipBtn).toBeTruthy()
    skipBtn!.click()
    expect(wrapper.emitted('skip')).toBeTruthy()
  })
})
