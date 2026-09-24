import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatBrowserSetupBar from '~/components/chat/ChatBrowserSetupBar.vue'
import type { BrowserSetupStatus } from '~/composables/useBrowserSetup'

function status(o: Partial<BrowserSetupStatus> = {}): BrowserSetupStatus {
  return {
    active: true, step: 'Downloading Chrome for Testing 153.0.8010.12', percent: 42, error: null,
    driverSource: 'downloaded', platform: 'mac-arm64', nodeVersion: '24.21.0', chromiumInstalled: false, ...o,
  }
}

describe('ChatBrowserSetupBar', () => {
  it('names the step and shows its percent on the progress bar', async () => {
    const c = await mountSuspended(ChatBrowserSetupBar, { props: { setup: status() } })
    expect(c.text()).toContain('Setting up the browser: Downloading Chrome for Testing 153.0.8010.12')
    expect(c.text()).toContain('42%')
    expect(c.find('[role="progressbar"]').attributes('aria-valuenow')).toBe('42')
  })

  it('reads as preparing, with an empty bar, before the first step starts', async () => {
    const c = await mountSuspended(ChatBrowserSetupBar, { props: { setup: status({ step: null, percent: null }) } })
    expect(c.text()).toContain('Setting up the browser: preparing')
    expect(c.find('[role="progressbar"]').attributes('aria-valuenow')).toBeUndefined()
  })
})
