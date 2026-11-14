import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody, setResponseStatus } from 'h3'
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
// What ConfigService.setWithSideEffects answers for the next write, as a 403.
let refusal: string | null = null

function baseEndpoints(entries: Array<{ key: string, value: string }> = []) {
  posted = []
  refusal = null
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels', () => [])
  registerEndpoint('/api/ocr/status', () => ({ providers: [] }))
  registerEndpoint('/api/providers', () => [])
  registerEndpoint('/api/config', () => ({ entries }))
  registerEndpoint('/api/config', {
    method: 'POST',
    handler: async (event) => {
      if (refusal) {
        setResponseStatus(event, 403)
        return { type: 'error', code: 'forbidden', message: refusal, template: null }
      }
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
  const BUDGET_PERCENT = 'chat.compaction.summaryBudgetPercent'
  const FACTS = 'chat.compaction.factsBudgetPercent'
  const CEILING = 'chat.compaction.factsCeilingPercent'
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
    expect(label(component, BUDGET_PERCENT)).toBe('Merge summaries past (% of the summary point)')
    expect(shown(component, BUDGET_PERCENT)).toBe('15')
    expect(label(component, BUDGET)).toBe('…or past a fixed size (tokens)')
    expect(shown(component, BUDGET)).toBe('')
    expect(label(component, FACTS)).toBe('Goals and facts may fill (% of that budget)')
    expect(shown(component, FACTS)).toBe('50')
    expect(label(component, CEILING)).toBe('…rewrite the merge past (% of that budget)')
    expect(shown(component, CEILING)).toBe('100')
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
      { key: BUDGET_PERCENT, value: '20' },
      { key: FACTS, value: '0' },
      { key: CEILING, value: '0' },
      { key: TOOL_CHARS, value: '0' },
    ])
    const component = await mountChat()

    expect(shown(component, PERCENT)).toBe('30')
    expect(shown(component, TOKENS)).toBe('0')
    expect(switchedOn(component, ON_ROW_LIMIT)).toBe('false')
    expect(shown(component, STEP)).toBe('5')
    expect(shown(component, BUDGET)).toBe('40000')
    expect(shown(component, BUDGET_PERCENT)).toBe('20')
    expect(shown(component, FACTS)).toBe('0')
    expect(shown(component, CEILING)).toBe('0')
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

  it('saves the summary budget share typed into its editor, which starts from 15 and stays within 1 to 100', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, BUDGET_PERCENT, '25')).toEqual({ value: '15', min: '1', max: '100' })
    expect(posted).toEqual([{ key: BUDGET_PERCENT, value: '25' }])
  })

  it('saves a fixed summary budget typed into its empty editor, which goes no lower than 1000', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, BUDGET, '30000')).toEqual({ value: '', min: '1000', max: undefined })
    expect(posted).toEqual([{ key: BUDGET, value: '30000' }])
  })

  it('saves the goals and facts share typed into its editor, which starts from 50 and stays within 0 to 100', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, FACTS, '0')).toEqual({ value: '50', min: '0', max: '100' })
    expect(posted).toEqual([{ key: FACTS, value: '0' }])
  })

  it('saves the rewrite ceiling typed into its editor, which starts from 100 and stays within 0 to 100', async () => {
    baseEndpoints()
    const component = await mountChat()

    expect(await saveTyped(component, CEILING, '80')).toEqual({ value: '100', min: '0', max: '100' })
    expect(posted).toEqual([{ key: CEILING, value: '80' }])
  })

  it('emptying a set summary budget saves it empty, so the budget follows the window again', async () => {
    baseEndpoints([{ key: BUDGET, value: '30000' }])
    const component = await mountChat()

    expect(await saveTyped(component, BUDGET, '')).toEqual({ value: '30000', min: '1000', max: undefined })
    expect(posted).toEqual([{ key: BUDGET, value: '' }])
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

/** The Advanced box's rows: each fallback is the backend's shipped value. */
describe('Settings page — Chat advanced compaction', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  const KEEP = 'chat.compaction.keepPercent'
  const MIN = 'chat.compaction.minSummaryTokens'
  const FORCED_KEEP = 'chat.compaction.forcedKeepPercent'
  const FORCED_MIN = 'chat.compaction.forcedMinSummaryTokens'

  async function mountAdvanced() {
    const component = await mountChat()
    const toggle = component.findAll('button').find(b => b.text() === 'Advanced — context window & compaction')
    expect(toggle).toBeDefined()
    await toggle!.trigger('click')
    await flushPromises()
    return component
  }

  function row(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return component.find(`[data-testid="config-field-${key}"]`)
  }

  function shown(component: Awaited<ReturnType<typeof mountChat>>, key: string) {
    return row(component, key).find('span.truncate').text()
  }

  it('shows the four rows at the backend defaults when their keys are unset, and no row counts messages', async () => {
    baseEndpoints()
    const component = await mountAdvanced()

    expect([KEEP, MIN, FORCED_KEEP, FORCED_MIN].map(key => row(component, key).find('span').text()))
      .toEqual(['compaction.keepPercent', 'compaction.minSummaryTokens', 'compaction.forcedKeepPercent',
        'compaction.forcedMinSummaryTokens'])
    expect([KEEP, MIN, FORCED_KEEP, FORCED_MIN].map(key => shown(component, key))).toEqual(['30', '2000', '10', '200'])
    expect(component.text()).not.toContain('compactionMinTurns')
    expect(component.text()).not.toContain('compactionKeepMessages')
  })

  it('shows the stored values when the keys are set', async () => {
    baseEndpoints([
      { key: KEEP, value: '40' },
      { key: MIN, value: '0' },
      { key: FORCED_KEEP, value: '5' },
      { key: FORCED_MIN, value: '50' },
    ])
    const component = await mountAdvanced()

    expect([KEEP, MIN, FORCED_KEEP, FORCED_MIN].map(key => shown(component, key))).toEqual(['40', '0', '5', '50'])
  })

  const QUOTE = 'chat.compaction.verbatimUserTokens'
  const CHECK = 'chat.compaction.checkSummary'
  const PREVIEW = 'chat.userMessagePreviewPercent'

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

  it('shows the quote, check and preview rows at the backend defaults when their keys are unset', async () => {
    baseEndpoints()
    const component = await mountAdvanced()

    expect([QUOTE, CHECK, PREVIEW].map(key => row(component, key).find('span').text()))
      .toEqual(['compaction.verbatimUserTokens', 'compaction.checkSummary', 'userMessagePreviewPercent'])
    expect(shown(component, QUOTE)).toBe('5000')
    expect(switchedOn(component, CHECK)).toBe('false')
    expect(shown(component, PREVIEW)).toBe('10')
  })

  it('shows the stored quote, check and preview values when the keys are set', async () => {
    baseEndpoints([
      { key: QUOTE, value: '0' },
      { key: CHECK, value: 'true' },
      { key: PREVIEW, value: '25' },
    ])
    const component = await mountAdvanced()

    expect(shown(component, QUOTE)).toBe('0')
    expect(switchedOn(component, CHECK)).toBe('true')
    expect(shown(component, PREVIEW)).toBe('25')
  })

  it('saves the quote budget typed into its editor, which starts from 5000 and goes no lower than 0', async () => {
    baseEndpoints()
    const component = await mountAdvanced()

    expect(await saveTyped(component, QUOTE, '0')).toEqual({ value: '5000', min: '0', max: undefined })
    expect(posted).toEqual([{ key: QUOTE, value: '0' }])
  })

  it('switching the summary check on saves true, and off again saves false', async () => {
    baseEndpoints()
    const component = await mountAdvanced()
    await row(component, CHECK).find('button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(posted).toEqual([{ key: CHECK, value: 'true' }])

    clearNuxtData()
    baseEndpoints([{ key: CHECK, value: 'true' }])
    const on = await mountAdvanced()
    await row(on, CHECK).find('button[aria-pressed]').trigger('click')
    await flushPromises()
    expect(posted).toEqual([{ key: CHECK, value: 'false' }])
  })

  it('saves the preview share typed into its editor, which starts from 10 and stays within 0 to 100', async () => {
    baseEndpoints()
    const component = await mountAdvanced()

    expect(await saveTyped(component, PREVIEW, '100')).toEqual({ value: '10', min: '0', max: '100' })
    expect(posted).toEqual([{ key: PREVIEW, value: '100' }])
  })

  it('shows why a preview share above 100 or a negative quote budget is refused and stores nothing', async () => {
    baseEndpoints()
    const component = await mountAdvanced()
    refusal = 'chat.userMessagePreviewPercent must be a whole number from 0 to 100; 0 sends every earlier message whole.'
    await saveTyped(component, PREVIEW, '101')
    expect(row(component, PREVIEW).find('[role="alert"]').text()).toContain(refusal)

    refusal = 'chat.compaction.verbatimUserTokens must be a whole number of tokens; 0 quotes none of the user\'s summarized messages.'
    await saveTyped(component, QUOTE, '-1')
    expect(row(component, QUOTE).find('[role="alert"]').text()).toContain(refusal)
    expect(posted).toEqual([])
  })

  it('saves the kept share typed into its editor, which starts from 30 and stays within 0 to 100', async () => {
    baseEndpoints()
    const component = await mountAdvanced()
    const field = row(component, KEEP)
    await field.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    const input = field.find('input')

    expect({ value: (input.element as HTMLInputElement).value, min: input.attributes('min'), max: input.attributes('max') })
      .toEqual({ value: '30', min: '0', max: '100' })
    await input.setValue('20')
    await field.find('button[title="Save"]').trigger('click')
    await flushPromises()
    expect(posted).toEqual([{ key: KEEP, value: '20' }])
  })

  it('saves the /compact summary minimum typed into its editor, which starts from 200 and goes no lower than 0', async () => {
    baseEndpoints()
    const component = await mountAdvanced()
    const field = row(component, FORCED_MIN)
    await field.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    const input = field.find('input')

    expect({ value: (input.element as HTMLInputElement).value, min: input.attributes('min'), max: input.attributes('max') })
      .toEqual({ value: '200', min: '0', max: undefined })
    await input.setValue('500')
    await field.find('button[title="Save"]').trigger('click')
    await flushPromises()
    expect(posted).toEqual([{ key: FORCED_MIN, value: '500' }])
  })

  it('shows why a share above 100 is refused and stores nothing', async () => {
    baseEndpoints()
    const component = await mountAdvanced()
    refusal = 'chat.compaction.keepPercent must be a whole number from 0 to 100; 0 keeps only the latest message and what follows it.'
    const field = row(component, KEEP)
    await field.find('button[title="Edit"]').trigger('click')
    await flushPromises()
    await field.find('input').setValue('101')
    await field.find('button[title="Save"]').trigger('click')
    await flushPromises()

    expect(field.find('[role="alert"]').text()).toContain(refusal)
    expect(posted).toEqual([])
  })
})
