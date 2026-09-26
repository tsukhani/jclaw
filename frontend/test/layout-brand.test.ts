import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import DefaultLayout from '~/layouts/default.vue'

registerEndpoint('/api/status', () => ({ status: 'ok', applicationVersion: '0.19.8' }))
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
  it('names the edition beside the logo', async () => {
    layout = await mountSuspended(DefaultLayout)

    const brandRow = layout.find('img[src="/clawdia.webp"]').element.parentElement
    expect(brandRow?.textContent).toContain('JClaw Pro')
  })
})
