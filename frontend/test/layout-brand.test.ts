import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import DefaultLayout from '~/layouts/default.vue'

let statusCalls = 0
let statusUp = true

registerEndpoint('/api/status', () => {
  statusCalls++
  if (!statusUp) throw new Error('backend down')
  return { status: 'ok', applicationVersion: '0.19.8', productName: 'Name From Backend' }
})
registerEndpoint('/api/system/upgrade', () => ({
  available: true,
  unavailableReason: null,
  currentVersion: '0.19.8',
  latestVersion: '0.19.8',
  upgradeAvailable: false,
  installKind: 'release',
  runningTasks: 0,
  activeSubagentRuns: 0,
  commit: null,
}))
registerEndpoint('/api/auth/status', () => ({ passwordSet: true }))
registerEndpoint('/api/onboarding/tour-status', () => ({ completed: true, step: 0 }))
registerEndpoint('/api/config', () => ({}))

let layout: Awaited<ReturnType<typeof mountSuspended>> | null = null

beforeEach(() => {
  clearNuxtData()
  statusCalls = 0
  statusUp = true
  // setup.ts's matchMedia matches nothing — the mobile layout, where the sidebar starts closed.
  vi.spyOn(globalThis, 'matchMedia').mockImplementation(query => ({
    matches: query === '(min-width: 1024px)',
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(() => false),
  }) as unknown as MediaQueryList)
})

afterEach(() => {
  layout?.unmount()
  layout = null
  vi.restoreAllMocks()
})

describe('default layout — brand line', () => {
  it('renders the name the status endpoint supplies beside the logo', async () => {
    const mounted = await mountSuspended(DefaultLayout)
    layout = mounted

    await vi.waitFor(() => expect(mounted.find('[data-testid="brand-name"]').text()).toBe('Name From Backend'))
    expect(mounted.find('img[src="/clawdia.webp"]').attributes('alt')).toBe('Name From Backend')
  })

  it('shows only the product name in the open sidebar\'s brand row, with no release-stage pill', async () => {
    const mounted = await mountSuspended(DefaultLayout)
    layout = mounted

    await vi.waitFor(() => expect(mounted.find('[data-testid="brand-name"]').text()).toBe('Name From Backend'))
    expect(mounted.find('[data-testid="brand-row"]').text()).toBe('Control Name From Backend')
  })

  it('holds no name of its own while the status endpoint cannot answer', async () => {
    statusUp = false
    const mounted = await mountSuspended(DefaultLayout)
    layout = mounted

    await vi.waitFor(() => expect(statusCalls).toBeGreaterThan(0))
    await flushPromises()

    expect(mounted.find('[data-testid="brand-name"]').text()).toBe('')
    expect(mounted.find('img[src="/clawdia.webp"]').attributes('alt')).toBe('')
  })
})
