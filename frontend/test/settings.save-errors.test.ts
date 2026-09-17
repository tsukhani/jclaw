import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus, type H3Event } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

// A save refused by the backend (JCLAW-1221): an application.conf ceiling or a validation rule answers 403
// with the reason in the error envelope, and the section must show that reason beside the open editor.
const REFUSAL = 'Refused: application.conf caps this setting.'

const ENTRIES: Array<{ key: string, value: string }> = [
  { key: 'provider.openai.baseUrl', value: 'https://api.openai.com/v1' },
  { key: 'provider.openai.apiKey', value: 'sk-****' },
  { key: 'provider.openai.models', value: '[{"id":"gpt-4","name":"GPT-4"}]' },
  { key: 'provider.bfl.apiKey', value: 'bfl-****' },
  { key: 'search.exa.enabled', value: 'true' },
  { key: 'search.exa.apiKey', value: '' },
  { key: 'search.exa.priority', value: '0' },
  { key: 'scanner.virustotal.enabled', value: 'true' },
  { key: 'scanner.virustotal.apiKey', value: '' },
  { key: 'shell.allowlist', value: 'ls,cat' },
  { key: 'shell.defaultTimeoutSeconds', value: '30' },
  { key: 'chat.maxToolRounds', value: '10' },
  { key: 'chat.maxContextMessages', value: '50' },
  { key: 'dispatcher.llm.maxRequestsPerHost', value: '64' },
  { key: 'dispatcher.llm.maxRequests', value: '128' },
  { key: 'upload.maxImageBytes', value: String(20 * 1024 * 1024) },
  { key: 'upload.maxFiles', value: '5' },
  { key: 'tasks.retentionDays', value: '30' },
  { key: 'app.timezone', value: 'UTC' },
  { key: 'subagent.maxDepth', value: '1' },
  { key: 'skillsPromotion.provider', value: 'openai' },
  { key: 'skillsPromotion.timeoutSeconds', value: '300' },
  { key: 'subagent.acp.command', value: 'npx some-acp-agent' },
  { key: 'memory.core.maxCount', value: '20' },
  { key: 'memory.recall.limit', value: '10' },
]

function refuse(event: H3Event) {
  setResponseStatus(event, 403)
  return { type: 'error', code: 'forbidden', message: REFUSAL }
}

function setupApi(extra: Array<{ key: string, value: string }> = []) {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'openai', modelId: 'gpt-4', enabled: true, isMain: true, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/providers', () => [
    { name: 'openai', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0, supportedModalities: ['PER_TOKEN'] },
  ])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/transcription/state', () => ({ provider: '', localModel: 'small.en', ffmpegAvailable: true, ffmpegReason: 'available', models: [] }))
  registerEndpoint('/api/config', { method: 'GET', handler: () => ({ entries: [...ENTRIES, ...extra].map(e => ({ ...e, updatedAt: '2026-09-17T10:00:00Z' })) }) })
  registerEndpoint('/api/config', { method: 'POST', handler: refuse })
}

async function mountSection(sectionId: string) {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = sectionId
  await flushPromises()
  await flushPromises()
  return component
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Reason: mountSuspended returns a proxy wrapper.
async function expectRefusalShown(component: any) {
  await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
  expect(component.find('[data-testid="api-error"]').text()).toContain(REFUSAL)
}

describe('Settings — a refused save explains itself beside the open editor (JCLAW-1221)', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it.each([
    'shell',
    'chat',
    'performance',
    'uploads',
    'tasks',
    'timezone',
    'subagents',
    'skills',
    'coding',
    'providers',
    'search',
    'malware',
  ])('%s', async (sectionId) => {
    setupApi()
    const component = await mountSection(sectionId)
    const edit = component.find('button[title="Edit"]')
    expect(edit.exists(), `${sectionId} should offer an Edit button`).toBe(true)
    await edit.trigger('click')
    await flushPromises()

    await component.find('button[title="Save"]').trigger('click')
    await expectRefusalShown(component)
    expect(component.find('button[title="Save"]').exists(), 'the editor stays open after a refusal').toBe(true)
  })

  it('memory-limits', async () => {
    setupApi()
    const component = await mountSection('memory-limits')
    await component.find('[data-testid="memory-core-max-count"]').setValue('25')
    await component.find('[data-testid="memory-limits-save"]').trigger('click')
    await expectRefusalShown(component)
  })

  it('image-generation', async () => {
    setupApi([{ key: 'imagegen.provider', value: 'bfl' }])
    const component = await mountSection('image-generation')
    await component.find('button[aria-label="Edit Black Forest Labs API key"]').trigger('click')
    await flushPromises()

    await component.find('button[title="Save"]').trigger('click')
    await expectRefusalShown(component)
  })
})
