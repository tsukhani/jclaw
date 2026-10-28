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

/** Each Context savings row's fallback is the backend's shipped value (JF-154, JF-155, JF-159, JF-161). */
describe('Settings page — Chat context savings', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  const PERCENT = 'chat.compaction.triggerPercent'
  const TOKENS = 'chat.compaction.triggerTokens'
  const ON_ROW_LIMIT = 'chat.compaction.onRowLimit'
  const STEP = 'chat.pruneToolResultsEveryTurns'
  const BUDGET = 'chat.compaction.summaryBudgetTokens'
  const TOOL_CHARS = 'chat.compaction.toolResultChars'

  function row(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return component.find(`[data-testid="config-field-${key}"]`)
  }

  function label(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return row(component, key).find('span').text()
  }

  function shown(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return row(component, key).find('span.truncate').text()
  }

  function switchedOn(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return row(component, key).find('button[aria-pressed]').attributes('aria-pressed')
  }

  async function saveTyped(component: Awaited<ReturnType<typeof mountChat>>, key: string, typed: string) {
    const field = row(component, key)
    await field.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    const input = field.find('input')
    const start = { value: (input.element as HTMLInputElement).value, min: input.attributes('min'), max: input.attributes('max') }
    await input.setValue(typed)
    await field.find('button[title="Save"]').trigger('click')
    await flushPromises()
    return start
  }

  it('shows each control at the backend default when its key is unset', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(label(component, PERCENT)).toBe('Summarize at (% of free context)')
    expect(shown(component, PERCENT)).toBe('50')
    expect(label(component, TOKENS)).toBe('…but no later than (tokens)')
    expect(shown(component, TOKENS)).toBe('150000')
    expect(label(component, ON_ROW_LIMIT)).toBe('Summarize instead of dropping messages')
    expect(switchedOn(component, ON_ROW_LIMIT)).toBe('true')
    expect(label(component, STEP)).toBe('Trim tool results every (turns)')
    expect(shown(component, STEP)).toBe('1')
    expect(label(component, BUDGET)).toBe('Merge summaries past (tokens)')
    expect(shown(component, BUDGET)).toBe('20000')
    expect(label(component, TOOL_CHARS)).toBe('Tool results in a catch-up (characters)')
    expect(shown(component, TOOL_CHARS)).toBe('600')
  })

  it('shows the stored values when the keys are set', async () => {
    baseEndpoints([
      { key: PERCENT, value: '30' },
      { key: TOKENS, value: '0' },
      { key: ON_ROW_LIMIT, value: 'false' },
      { key: STEP, value: '5' },
      { key: BUDGET, value: '40000' },
      { key: TOOL_CHARS, value: '0' },
    ])
    const component = await mountChat()

    expect(shown(component, PERCENT)).toBe('30')
    expect(shown(component, TOKENS)).toBe('0')
    expect(switchedOn(component, ON_ROW_LIMIT)).toBe('false')
    expect(shown(component, STEP)).toBe('5')
    expect(shown(component, BUDGET)).toBe('40000')
    expect(shown(component, TOOL_CHARS)).toBe('0')
  })

  it('saves the share of the window typed into its editor, which starts from 50 and stays within 0 to 100', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, PERCENT, '40')).toEqual({ value: '50', min: '0', max: '100' })
    expect(posted).toEqual([{ key: PERCENT, value: '40' }])
  })

  it('saves the token cap typed into its editor, which starts from 150000 and goes no lower than 0', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, TOKENS, '100000')).toEqual({ value: '150000', min: '0', max: undefined })
    expect(posted).toEqual([{ key: TOKENS, value: '100000' }])
  })

  it('switching summarizing at the history limit off saves false, and on again saves true', async () => {
    baseEndpoints()
    const component = await mountChat()
    await row(component, ON_ROW_LIMIT).find('button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(posted).toEqual([{ key: ON_ROW_LIMIT, value: 'false' }])

    clearNuxtData()
    baseEndpoints([{ key: ON_ROW_LIMIT, value: 'false' }])
    const off = await mountChat()
    await row(off, ON_ROW_LIMIT).find('button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(posted).toEqual([{ key: ON_ROW_LIMIT, value: 'true' }])
  })

  it('saves the shortening step typed into its editor, which starts from 1 and goes no lower', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, STEP, '5')).toEqual({ value: '1', min: '1', max: undefined })
    expect(posted).toEqual([{ key: STEP, value: '5' }])
  })

  it('saves the summary budget typed into its editor, which starts from 20000 and goes no lower than 1000', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, BUDGET, '30000')).toEqual({ value: '20000', min: '1000', max: undefined })
    expect(posted).toEqual([{ key: BUDGET, value: '30000' }])
  })

  it('saves the catch-up tool result length typed into its editor, which starts from 600 and goes no lower than 0', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, TOOL_CHARS, '1200')).toEqual({ value: '600', min: '0', max: undefined })
    expect(posted).toEqual([{ key: TOOL_CHARS, value: '1200' }])
  })

  it('cancelling an edit saves nothing', async () => {
    baseEndpoints()
    const component = await mountChat()
    const trigger = row(component, PERCENT)

    await trigger.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    await trigger.find('input').setValue('10')
    await trigger.find('button[title="Cancel"]').trigger('click')
    await flushPromises()

    expect(posted).toEqual([])
    expect(shown(component, PERCENT)).toBe('50')
  })
})
