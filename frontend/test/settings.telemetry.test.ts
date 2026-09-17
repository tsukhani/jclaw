import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * The Telemetry settings panel (JCLAW-34). Its logic is what it sends and what it
 * relays: the otel.* keys go out as ordinary /api/config writes, and the runtime's
 * verdict (/api/telemetry, /api/telemetry/test) is shown rather than assumed.
 */

let posted: Array<Record<string, unknown>> = []

function baseEndpoints(opts: { entries?: Array<{ key: string, value: string }>, rejectSave?: string, proxyPage?: boolean } = {}) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => ({ entries: opts.entries ?? [] }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      posted.push(await readBody(event))
      if (opts.proxyPage) {
        // A reverse proxy answering in place of the backend: an error page, not the API's envelope.
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      }
      if (opts.rejectSave) {
        // The envelope ApiResponses.error sends; an h3 createError body carries no message the panel reads.
        setResponseStatus(event, 403)
        return { type: 'error', code: 'forbidden', message: opts.rejectSave }
      }
      return { status: 'ok' }
    },
  })
  registerEndpoint('/api/telemetry', () => ({
    initialized: true,
    enabled: false,
    exporting: false,
    endpoint: 'http://localhost:4318',
    protocol: 'http/protobuf',
    serviceName: 'jclaw',
    samplerRatio: 1,
    agentAttached: false,
    lastExportError: null,
  }))
  registerEndpoint('/api/telemetry/test', {
    method: 'POST',
    handler: () => ({ delivered: false, traceId: 'abc123', error: 'export is off — enable it first' }),
  })
}

async function mountTelemetry() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'telemetry'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Telemetry', () => {
  beforeEach(() => {
    clearNuxtData()
    posted = []
  })

  it('shows the stored collector address and that export is off', async () => {
    baseEndpoints({ entries: [{ key: 'otel.exporter.endpoint', value: 'http://collector:4318' }] })
    const component = await mountTelemetry()
    const text = component.text()
    expect(text).toContain('Telemetry')
    expect(text).toContain('http://collector:4318')
    expect(text).toContain('Off')
    expect(text).toContain('not exporting')
  })

  it('turning export on is one otel.enabled write', async () => {
    baseEndpoints()
    const component = await mountTelemetry()
    await component.find('input[aria-label="Export telemetry"]').setValue(true)
    await flushPromises()
    expect(posted).toEqual([{ key: 'otel.enabled', value: 'true' }])
  })

  it('relays the runtime\'s verdict on a test span instead of claiming delivery', async () => {
    baseEndpoints()
    const component = await mountTelemetry()
    const button = component.findAll('button').find(b => b.text().includes('Send test span'))
    expect(button).toBeDefined()
    await button!.trigger('click')
    await flushPromises()
    expect(component.text()).toContain('Not delivered: export is off')
  })

  it('shows the write-time rejection for a bad endpoint', async () => {
    baseEndpoints({ rejectSave: 'otel.exporter.endpoint must be an absolute http(s) URL such as http://localhost:4318.' })
    const component = await mountTelemetry()
    await component.find('button[title="Edit exporter.endpoint"]').trigger('click')
    await component.find('input[aria-label="exporter.endpoint"]').setValue('collector:4318')
    await component.find('button[title="Save"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[role="alert"]').exists()).toBe(true))
    expect(component.find('[role="alert"]').text()).toContain('must be an absolute http(s) URL')
    expect(posted).toEqual([{ key: 'otel.exporter.endpoint', value: 'collector:4318' }])
    // The editor only closes on success; a refused write leaves it open with the alert beside it.
    expect(component.find('input[aria-label="exporter.endpoint"]').exists()).toBe(true)
  })

  it('names the request when a field save meets a proxy page instead of the error envelope (JCLAW-1221)', async () => {
    baseEndpoints({ proxyPage: true })
    const component = await mountTelemetry()
    await component.find('button[title="Edit exporter.endpoint"]').trigger('click')
    await component.find('input[aria-label="exporter.endpoint"]').setValue('http://collector:4318')
    await component.find('button[title="Save"]').trigger('click')
    await vi.waitFor(() => expect(component.find('[role="alert"]').exists()).toBe(true))

    const shown = component.find('[role="alert"]').text()
    expect(shown).toContain('/api/config')
    expect(shown).toContain('502')
    expect(shown).not.toContain('Save failed')
  })

  it('names the request when the export toggle meets a proxy page instead of the error envelope (JCLAW-1221)', async () => {
    baseEndpoints({ proxyPage: true })
    const component = await mountTelemetry()
    await component.find('input[aria-label="Export telemetry"]').setValue(true)
    await vi.waitFor(() => expect(component.find('[role="alert"]').exists()).toBe(true))

    const shown = component.find('[role="alert"]').text()
    expect(shown).toContain('/api/config')
    expect(shown).toContain('502')
    expect(shown).not.toContain('Save failed')
  })
})
