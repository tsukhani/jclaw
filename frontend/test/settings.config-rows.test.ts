import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/** Rows for DB-backed settings that had no Settings surface: Voice Mode, chat streaming, approval timeout. */

type Body = { key?: string, value?: string }

let posted: Body[] = []
let refusal: string | null = null

function stubEndpoints() {
  posted = []
  refusal = null
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/tool-approvals/summary', () => ({ totalGrants: 0, agentsWithGrants: 0, agents: [] }))
  registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: [] }) })
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      if (refusal) {
        setResponseStatus(event, 403)
        return { type: 'error', code: 'forbidden', message: refusal }
      }
      posted.push(await readBody(event) as Body)
      return { ok: true }
    },
  })
}

async function mountSettingsSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

function row(component: Awaited<ReturnType<typeof mountSettingsSection>>, key: string) {
  return component.find(`[data-testid="config-field-${key}"]`)
}

describe('Settings page — Voice Mode', () => {
  beforeEach(() => {
    clearNuxtData()
    stubEndpoints()
  })

  it('renders every voice setting with the default the backend applies', async () => {
    const component = await mountSettingsSection('voice')

    expect(component.findAll('[data-testid^="config-field-voice."]')).toHaveLength(8)
    expect(row(component, 'voice.endpoint.speechStartMs').text()).toContain('180')
    expect(row(component, 'voice.tts.maxRunOnChars').text()).toContain('220')
  })

  it('turns semantic hold off with one click', async () => {
    const component = await mountSettingsSection('voice')

    const toggle = row(component, 'voice.endpoint.semanticHold').find('button[aria-pressed]')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    await toggle.trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: 'voice.endpoint.semanticHold', value: 'false' }])
  })

  it('shows why a base silence above the maximum is refused', async () => {
    const component = await mountSettingsSection('voice')
    refusal = 'voice.endpoint.baseSilenceMs must not exceed voice.endpoint.maxSilenceMs (1500).'

    const base = row(component, 'voice.endpoint.baseSilenceMs')
    await base.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    await base.find('input').setValue('2000')
    await base.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(base.find('[role="alert"]').text()).toContain('must not exceed')
  })
})

describe('Settings page — rows for other DB-backed settings', () => {
  beforeEach(() => {
    clearNuxtData()
    stubEndpoints()
  })

  it('shows token coalescing on the Performance panel', async () => {
    const component = await mountSettingsSection('performance')

    expect(row(component, 'chat.stream.token_coalesce_chars').text()).toContain('0')
  })

  it('shows the approval prompt timeout on the Tool Approvals panel', async () => {
    const component = await mountSettingsSection('approvals')

    expect(row(component, 'telegram.approval.timeout-seconds').text()).toContain('300')
  })
})
