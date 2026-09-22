import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, mockNuxtImport, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus, type H3Event } from 'h3'
import NewScrape from '~/pages/scrapes/new.vue'

/**
 * JCLAW-1273: the New scrape form — what it starts from, the agent ceilings it shows but does not
 * enforce, and a refusal shown beside the field it is about.
 */

const { navigateToMock } = vi.hoisted(() => ({ navigateToMock: vi.fn().mockResolvedValue(undefined) }))
mockNuxtImport('navigateTo', () => navigateToMock)

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

function config(entries: Record<string, string>) {
  registerEndpoint('/api/config', () => ({ entries: Object.entries(entries).map(([key, value]) => ({ key, value, updatedAt: '2026-09-22T10:00:00Z' })) }))
}

beforeEach(() => {
  clearNuxtData()
  navigateToMock.mockClear()
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', isMain: true, enabled: true },
    { id: 2, name: 'researcher', isMain: false, enabled: true },
  ])
})

describe('New scrape form', () => {
  it('starts from current Settings, and from the defaults for anything unset', async () => {
    config({ 'web_scrape.job.max-pages': '300', 'web_scrape.language': 'ja', 'web_scrape.respect-robots': 'false' })

    const form = await mountSuspended(NewScrape)
    await settle()

    expect((form.find('#scrape-maxPages').element as HTMLInputElement).value).toBe('300')
    expect((form.find('#scrape-maxDepth').element as HTMLInputElement).value).toBe('2')
    expect((form.find('#scrape-maxMinutes').element as HTMLInputElement).value).toBe('60')
    expect((form.find('#scrape-language').element as HTMLInputElement).value).toBe('ja')
    expect((form.find('#scrape-robots').element as HTMLInputElement).checked).toBe(false)
    expect((form.find('#scrape-sitemap').element as HTMLInputElement).checked).toBe(true)
    expect((form.find('#scrape-agent').element as HTMLSelectElement).value).toBe('1')
    expect(form.text()).toContain('An agent may ask for up to 300 pages; you may go higher.')
    form.unmount()
  })

  it('has no field for the proxy or concurrency, and sends values above the agent ceilings', async () => {
    config({})
    let sent: Record<string, unknown> | null = null
    registerEndpoint('/api/scrape-jobs', { method: 'POST', handler: async (event: H3Event) => {
      sent = await readBody(event)
      return { id: 42 }
    } })
    let settingsWritten = false
    registerEndpoint('/api/config', { method: 'POST', handler: () => {
      settingsWritten = true
      return {}
    } })

    const form = await mountSuspended(NewScrape)
    await settle()
    expect(form.text().toLowerCase()).not.toContain('proxy url')
    expect(form.find('input[id*="concurrency"]').exists()).toBe(false)

    await form.find('#scrape-url').setValue('https://docs.example.test/')
    await form.find('#scrape-maxPages').setValue(2000)
    await form.find('#scrape-agent').setValue('2')
    await form.find('form').trigger('submit')
    await settle()

    expect(sent).toMatchObject({ url: 'https://docs.example.test/', maxPages: 2000, agentId: 2, format: 'markdown' })
    expect(Object.keys(sent ?? {}).some(key => /proxy|concurrency/i.test(key))).toBe(false)
    expect(navigateToMock).toHaveBeenCalledWith('/scrapes/42')
    expect(settingsWritten).toBe(false)
    form.unmount()
  })

  it('shows a refusal beside the field it is about and opens nothing', async () => {
    config({})
    registerEndpoint('/api/scrape-jobs', { method: 'POST', handler: (event: H3Event) => {
      setResponseStatus(event, 400)
      return { code: 'invalid_request', message: 'extract field \'price\': Did not find balanced marker at \'[\'', template: null, field: 'extract' }
    } })

    const form = await mountSuspended(NewScrape)
    await settle()
    await form.find('#scrape-url').setValue('https://shop.example.test/')
    await form.findAll('button').find(b => b.text() === 'Add field')!.trigger('click')
    const [name, selector] = form.findAll('input[aria-label$="field 1"]')
    await name!.setValue('price')
    await selector!.setValue('div[[')
    await form.find('form').trigger('submit')
    await settle()

    expect(form.find('[data-testid="scrape-extract-error"]').text()).toContain('extract field \'price\'')
    expect(selector!.attributes('aria-invalid')).toBe('true')
    expect(navigateToMock).not.toHaveBeenCalled()
    form.unmount()
  })

  it('cannot be submitted without a URL', async () => {
    config({})

    const form = await mountSuspended(NewScrape)
    await settle()

    expect(form.findAll('button').find(b => b.text() === 'Start scrape')!.attributes('disabled')).toBeDefined()
    form.unmount()
  })
})
