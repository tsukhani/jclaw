import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * The Alerts settings panel (JCLAW-1279): one alerts.delivery row, written as channel:target,
 * removed to turn alerts off, and the backend's refusal of a destination shown as it came.
 */

let posted: Array<Record<string, unknown>> = []
let deleted = 0

function endpoints(opts: { stored?: string, rejectSave?: string } = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => ({
    entries: opts.stored ? [{ key: 'alerts.delivery', value: opts.stored }] : [],
  }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      posted.push(await readBody(event))
      if (opts.rejectSave) {
        setResponseStatus(event, 403)
        return { type: 'error', code: 'forbidden', message: opts.rejectSave }
      }
      return { status: 'ok' }
    },
  })
  registerEndpoint('/api/config/alerts.delivery', {
    method: 'DELETE',
    handler: () => {
      deleted++
      return { status: 'ok' }
    },
  })
}

async function mountAlerts() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'alerts'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Alerts', () => {
  beforeEach(() => {
    clearNuxtData()
    posted = []
    deleted = 0
  })

  it('says alerts are off, and saves a channel and target as one channel:target value', async () => {
    endpoints()
    const component = await mountAlerts()
    expect(component.find('[data-testid="alerts-status"]').text()).toContain('Off')

    await component.find('#alerts-channel').setValue('telegram')
    expect(component.text()).toContain('Chat ID')
    await component.find('#alerts-target').setValue(' 123456789 ')
    await component.findAll('button').find(b => b.text() === 'Save')!.trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: 'alerts.delivery', value: 'telegram:123456789' }])
  })

  it('shows where alerts go and turns them off by removing the row', async () => {
    endpoints({ stored: 'slack:C0123' })
    const component = await mountAlerts()
    expect(component.find('[data-testid="alerts-status"]').text()).toContain('slack:C0123')
    expect((component.find('#alerts-channel').element as HTMLSelectElement).value).toBe('slack')
    expect((component.find('#alerts-target').element as HTMLInputElement).value).toBe('C0123')

    await component.findAll('button').find(b => b.text() === 'Turn off')!.trigger('click')
    await flushPromises()

    expect(deleted).toBe(1)
    expect(posted).toEqual([])
  })

  it('shows why the backend refused a destination', async () => {
    endpoints({ rejectSave: 'alerts.delivery must be channel:target, with a channel of telegram, slack, whatsapp or web (got \'web:\').' })
    const component = await mountAlerts()
    await component.find('#alerts-channel').setValue('web')
    await component.find('#alerts-target').setValue('abc')
    await component.findAll('button').find(b => b.text() === 'Save')!.trigger('click')

    await vi.waitFor(() => expect(component.find('[role="alert"]').exists()).toBe(true))
    expect(component.find('[role="alert"]').text()).toContain('alerts.delivery must be channel:target')
  })

  it('cannot save without a channel and a target', async () => {
    endpoints()
    const component = await mountAlerts()
    const save = component.findAll('button').find(b => b.text() === 'Save')!
    expect(save.attributes('disabled')).toBeDefined()
    await component.find('#alerts-channel').setValue('whatsapp')
    expect(save.attributes('disabled')).toBeDefined()
  })
})
