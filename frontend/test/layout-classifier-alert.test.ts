import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type DOMWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import DefaultLayout from '~/layouts/default.vue'
import type { ClassifierAlert } from '~/composables/useClassifierAlert'

const { navigateToMock } = vi.hoisted(() => ({ navigateToMock: vi.fn().mockResolvedValue(undefined) }))
mockNuxtImport('navigateTo', () => navigateToMock)

// The SSE bus is mocked so a test delivers the server's events to the handlers the layout registers.
const bus = vi.hoisted(() => ({
  handlers: [] as Array<{ type: string, handler: (data: unknown, type: string) => void }>,
  openHandlers: [] as Array<() => void>,
}))
vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => {
    const on = (type: string, handler: (data: unknown, type: string) => void) => {
      bus.handlers.push({ type, handler })
    }
    return { on, off: () => {}, onEvent: on, onOpen: (handler: () => void) => bus.openHandlers.push(handler) }
  },
}))

function emitBus(type: string, data: unknown) {
  for (const entry of bus.handlers) {
    if (entry.type === type) entry.handler(data, type)
  }
}

const RETIRED: ClassifierAlert = {
  classifier: 'ollama-cloud/deepseek-v4-flash',
  consecutiveFailures: 3,
  since: '2026-11-13T05:22:26Z',
  lastError: 'HTTP 410 from ollama-cloud: {"error":"retired"}',
}
const MESSAGE = 'The router\'s classifier ollama-cloud/deepseek-v4-flash has failed on 3 turns in a row, so keyword '
  + 'rules are choosing the models: HTTP 410 from ollama-cloud: {"error":"retired"}'

let routerAlert: ClassifierAlert | null = null
let routerStatusCalls = 0
let statusUp = true

registerEndpoint('/api/status', () => {
  if (!statusUp) throw new Error('backend down')
  return { status: 'ok', applicationVersion: '1.0.0', productName: 'JClaw' }
})
registerEndpoint('/api/router/status', () => {
  routerStatusCalls++
  return { available: true, downshiftAt: 0.75, exhaustedAt: 0.95, providers: [], unavailable: {}, classifierAlert: routerAlert }
})
registerEndpoint('/api/system/upgrade', () => ({
  available: true,
  unavailableReason: null,
  currentVersion: '1.0.0',
  latestVersion: '1.0.0',
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

async function mountLayout() {
  layout = await mountSuspended(DefaultLayout)
  await flushPromises()
  return layout
}

function banner() {
  return layout!.findAll('[role="alert"]').find((b: DOMWrapper<Element>) => b.text().includes('classifier'))
}

beforeEach(() => {
  clearNuxtData()
  useState<ClassifierAlert | null>('router:classifierAlert').value = null
  useState<boolean>('auth:authenticated').value = true
  bus.handlers.length = 0
  bus.openHandlers.length = 0
  routerAlert = null
  routerStatusCalls = 0
  statusUp = true
  navigateToMock.mockClear()
})

afterEach(() => {
  layout?.unmount()
  layout = null
})

describe('default layout — router classifier alert', () => {
  it('shows a standing alert from the router status, and its action opens the router settings', async () => {
    routerAlert = RETIRED
    await mountLayout()
    await vi.waitFor(() => expect(banner()?.find('span.flex-1').text()).toBe(MESSAGE))
    await banner()!.find('button').trigger('click')
    expect(navigateToMock).toHaveBeenCalledWith('/settings?section=model-router')
  })

  it('shows no alert while the classifier works', async () => {
    await mountLayout()
    await vi.waitFor(() => expect(routerStatusCalls).toBeGreaterThan(0))
    expect(banner()).toBeUndefined()
  })

  it('raises the alert when the event arrives and clears it when one comes without an alert', async () => {
    await mountLayout()
    emitBus('router.classifier', { classifier: RETIRED.classifier, alert: RETIRED })
    await flushPromises()
    expect(banner()?.find('span.flex-1').text()).toBe(MESSAGE)

    emitBus('router.classifier', { classifier: RETIRED.classifier })
    await flushPromises()
    expect(banner()).toBeUndefined()
  })

  it('reads the status again when the event stream reconnects, since missed events are not replayed', async () => {
    await mountLayout()
    await vi.waitFor(() => expect(routerStatusCalls).toBeGreaterThan(0))
    expect(banner()).toBeUndefined()
    routerAlert = RETIRED
    for (const reopened of bus.openHandlers) reopened()
    await vi.waitFor(() => expect(banner()?.find('span.flex-1').text()).toBe(MESSAGE))
  })

  it('gives way to the offline banner while the backend is down', async () => {
    routerAlert = RETIRED
    statusUp = false
    await mountLayout()
    await vi.waitFor(() => expect(layout!.findAll('[role="alert"]').length).toBe(1))
    expect(banner()).toBeUndefined()
  })

  it('reads nothing while signed out', async () => {
    useState<boolean>('auth:authenticated').value = false
    routerAlert = RETIRED
    await mountLayout()
    expect(routerStatusCalls).toBe(0)
    expect(banner()).toBeUndefined()
  })
})
