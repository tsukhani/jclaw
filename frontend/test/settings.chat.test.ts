import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Settings from '~/pages/settings.vue'

/**
 * The Chat settings panel's fallbacks must match the backend's own defaults: the panel showed
 * 10 tool rounds where AgentRunner.DEFAULT_MAX_TOOL_ROUNDS seeds 100 (JCLAW-1173), so an
 * unseeded instance displayed — and would have saved — a tenth of the real budget.
 */

/** Mirrors AgentRunner.DEFAULT_MAX_TOOL_ROUNDS, which DefaultConfigJob seeds. */
const BACKEND_MAX_TOOL_ROUNDS = 100

/** Mirrors ConversationService.DEFAULT_HISTORY_LIMIT, which DefaultConfigJob seeds. */
const BACKEND_MAX_CONTEXT_MESSAGES = 1000

type Body = { key?: string, value?: string }

let posted: Body[] = []

function baseEndpoints(entries: Array<{ key: string, value: string }> = []) {
  posted = []
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => ({ entries }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      posted.push(await readBody(event) as Body)
      return { status: 'ok' }
    },
  })
}

async function mountChat() {
  const component = await mountSuspended(Settings)
  ;(component.vm as unknown as { activeSectionId: string }).activeSectionId = 'chat'
  await flushPromises()
  await flushPromises()
  return component
}

describe('Settings page — Chat', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('falls back to the backend default for max tool rounds when the key is unset', async () => {
    baseEndpoints()
    const component = await mountChat()
    expect(component.text()).toContain(`${BACKEND_MAX_TOOL_ROUNDS} rounds`)
  })

  it('shows the stored value when the key is set', async () => {
    baseEndpoints([{ key: 'chat.maxToolRounds', value: '25' }])
    const component = await mountChat()
    expect(component.text()).toContain('25 rounds')
    expect(component.text()).not.toContain(`${BACKEND_MAX_TOOL_ROUNDS} rounds`)
  })

  it('lets the editor enter the default: the input ceiling is above it', async () => {
    baseEndpoints()
    const component = await mountChat()
    const edit = component.findAll('button[title="Edit"]').at(0)
    await edit!.trigger('click')
    await flushPromises()
    const input = component.find('input[aria-label="Max tool rounds"]')
    expect(Number(input.attributes('max'))).toBeGreaterThanOrEqual(BACKEND_MAX_TOOL_ROUNDS)
  })

  it('falls back to the backend default for max context messages when the key is unset', async () => {
    baseEndpoints()
    const component = await mountChat()
    expect(component.text()).toContain(`${BACKEND_MAX_CONTEXT_MESSAGES} messages`)
  })

  it('shows the stored max context messages when the key is set', async () => {
    baseEndpoints([{ key: 'chat.maxContextMessages', value: '50' }])
    const component = await mountChat()
    expect(component.text()).toContain('50 messages')
    expect(component.text()).not.toContain(`${BACKEND_MAX_CONTEXT_MESSAGES} messages`)
  })

  it('lets the editor enter the default context messages: the input ceiling is above it', async () => {
    baseEndpoints()
    const component = await mountChat()
    const edit = component.findAll('button[title="Edit"]').at(1)
    await edit!.trigger('click')
    await flushPromises()
    const input = component.find('input[aria-label="Max context messages"]')
    expect((input.element as HTMLInputElement).value).toBe(String(BACKEND_MAX_CONTEXT_MESSAGES))
    expect(Number(input.attributes('max'))).toBeGreaterThanOrEqual(BACKEND_MAX_CONTEXT_MESSAGES)
  })
})

/** The two context savings ship off (JF-154, JF-155); each row's fallback is the backend's shipped value. */
describe('Settings page — Chat context savings', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  function row(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return component.find(`[data-testid="config-field-${key}"]`)
  }

  function label(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return row(component, key).find('span').text()
  }

  function shown(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return row(component, key).find('span.truncate').text()
  }

  it('shows each control at the backend default when its key is unset', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(label(component, 'chat.compaction.triggerTokens')).toBe('Summarize long chats at (tokens)')
    expect(shown(component, 'chat.compaction.triggerTokens')).toBe('0')
    expect(label(component, 'chat.pruneToolResultsEveryTurns')).toBe('Trim tool results every (turns)')
    expect(shown(component, 'chat.pruneToolResultsEveryTurns')).toBe('1')
  })

  it('shows the stored values when the keys are set', async () => {
    baseEndpoints([
      { key: 'chat.compaction.triggerTokens', value: '150000' },
      { key: 'chat.pruneToolResultsEveryTurns', value: '5' },
    ])
    const component = await mountChat()

    expect(shown(component, 'chat.compaction.triggerTokens')).toBe('150000')
    expect(shown(component, 'chat.pruneToolResultsEveryTurns')).toBe('5')
  })

  it('saves the summarize threshold typed into its editor, which starts from 0 and goes no lower', async () => {
    baseEndpoints()
    const component = await mountChat()
    const trigger = row(component, 'chat.compaction.triggerTokens')

    await trigger.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    const input = trigger.find('input')
    expect((input.element as HTMLInputElement).value).toBe('0')
    expect(input.attributes('min')).toBe('0')
    await input.setValue('150000')
    await trigger.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: 'chat.compaction.triggerTokens', value: '150000' }])
  })

  it('saves the shortening step typed into its editor, which starts from 1 and goes no lower', async () => {
    baseEndpoints()
    const component = await mountChat()
    const step = row(component, 'chat.pruneToolResultsEveryTurns')

    await step.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    const input = step.find('input')
    expect((input.element as HTMLInputElement).value).toBe('1')
    expect(input.attributes('min')).toBe('1')
    await input.setValue('5')
    await step.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([{ key: 'chat.pruneToolResultsEveryTurns', value: '5' }])
  })

  it('cancelling an edit saves nothing', async () => {
    baseEndpoints()
    const component = await mountChat()
    const trigger = row(component, 'chat.compaction.triggerTokens')

    await trigger.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    await trigger.find('input').setValue('150000')
    await trigger.find('button[title="Cancel"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([])
    expect(shown(component, 'chat.compaction.triggerTokens')).toBe('0')
  })
})
