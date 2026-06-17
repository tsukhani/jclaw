import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import ChatCostSection from '~/components/ChatCostSection.vue'
import type { Agent } from '~/types/api'

/**
 * Component tests for the JCLAW-28 Chat Cost section.
 *
 * <p>The component wraps three concerns: the /api/metrics/cost fetch (with
 * since-driven refetch), client-side filter aggregation via
 * {@code computeFleetCost}, and the table/chart/CSV view modes. The
 * computeFleetCost math is exercised in {@code usage-cost.test.ts}; these
 * tests focus on the wire-up: filter changes producing correct DOM,
 * empty/loading states rendering, and CSV affordance gating.
 */

const STUB_AGENTS: Agent[] = [
  { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
    enabled: true, isMain: true, providerConfigured: true } as unknown as Agent,
  { id: 2, name: 'helper', modelProvider: 'openai', modelId: 'gpt-4.1',
    enabled: true, isMain: false, providerConfigured: true } as unknown as Agent,
]

interface UsagePartial {
  prompt?: number
  completion?: number
  modelId?: string
  modelProvider?: string
  promptPrice?: number
  completionPrice?: number
}

function makeUsage(partial: UsagePartial = {}): string {
  return JSON.stringify({
    prompt: partial.prompt ?? 100,
    completion: partial.completion ?? 50,
    total: (partial.prompt ?? 100) + (partial.completion ?? 50),
    reasoning: 0,
    cached: 0,
    durationMs: 100,
    promptPrice: partial.promptPrice ?? 0.5,
    completionPrice: partial.completionPrice ?? 1.0,
    modelId: partial.modelId ?? 'gpt-4.1',
    modelProvider: partial.modelProvider ?? 'openai',
  })
}

describe('ChatCostSection (JCLAW-28)', () => {
  it('renders empty state when no rows are returned', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('No conversations match the current filter')
  })

  it('renders the section title and filter dropdowns', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Chat Cost')
    expect(wrapper.find('select[aria-label="Filter by agent"]').exists()).toBe(true)
    expect(wrapper.find('select[aria-label="Filter by channel"]').exists()).toBe(true)
    expect(wrapper.find('select[aria-label="Filter by time window"]').exists()).toBe(true)
  })

  it('renders aggregated totals when rows are present', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 100, completion: 50 }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 200, completion: 100 }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const text = wrapper.text()
    // Total turns and tokens visible in the summary row.
    expect(text).toContain('Turns')
    expect(text).toContain('300') // 100 + 200 prompt tokens
    expect(text).toContain('150') // 50 + 100 completion tokens
  })

  it('lists only channels present in the loaded data as filter options', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage() },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'telegram',
          usageJson: makeUsage() },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const channelSelect = wrapper.find<HTMLSelectElement>('select[aria-label="Filter by channel"]')
    const optionValues = channelSelect.findAll('option').map(o => o.attributes('value'))
    // Vue serializes null v-model values to empty string; the rest are channel kinds.
    expect(optionValues).toContain('web')
    expect(optionValues).toContain('telegram')
  })

  it('renders per-model breakdown table by default', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'gpt-4.1', modelProvider: 'openai' }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 2, channelType: 'web',
          usageJson: makeUsage({ modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud' }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.find('table').exists()).toBe(true)
    const text = wrapper.text()
    expect(text).toContain('gpt-4.1')
    expect(text).toContain('kimi-k2.5')
  })

  it('does not render a reset button (cost is durable)', async () => {
    // Regression guard for the AC: Chat Performance has a reset; Chat Cost
    // explicitly does not, since durable persisted data shouldn't carry the
    // ephemeral-reset affordance.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [{ timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
        usageJson: makeUsage() }],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const buttons = wrapper.findAll('button')
    for (const btn of buttons) {
      const title = btn.attributes('title') ?? ''
      expect(title.toLowerCase()).not.toContain('reset')
    }
  })

  it('disables the CSV button when there is no data', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const csvBtn = wrapper.findAll('button').find(b => b.attributes('title')?.includes('CSV'))
    expect(csvBtn).toBeDefined()
    expect(csvBtn!.attributes('disabled')).toBeDefined()
  })

  it('sorts table by Cost descending by default', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 50, completion: 25,
            modelId: 'cheap-model', modelProvider: 'p',
            promptPrice: 0.1, completionPrice: 0.2 }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 1000, completion: 500,
            modelId: 'expensive-model', modelProvider: 'p',
            promptPrice: 5.0, completionPrice: 10.0 }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    // expensive-model has the higher cost — should be first row by default.
    expect(rows[0]!.text()).toContain('expensive-model')
    expect(rows[1]!.text()).toContain('cheap-model')
  })

  it('flips direction when the same column header is clicked twice', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 100, modelId: 'a-model' }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 200, modelId: 'b-model' }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const promptHeader = wrapper.findAll('thead button').find(b => b.text().includes('Prompt'))!
    // First click: sort by Prompt descending (numeric default).
    await promptHeader.trigger('click')
    await flushPromises()
    let rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('b-model') // 200 prompt tokens
    expect(rows[1]!.text()).toContain('a-model') // 100 prompt tokens
    // Second click: flip to ascending.
    await promptHeader.trigger('click')
    await flushPromises()
    rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('a-model')
    expect(rows[1]!.text()).toContain('b-model')
  })

  it('sorts the Model column alphabetically ascending by default', async () => {
    // Strings default to ascending — alphabetical order reads naturally
    // for the operator scanning model names.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'zeta-model' }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'alpha-model' }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const modelHeader = wrapper.findAll('thead button').find(b => b.text().includes('Model'))!
    await modelHeader.trigger('click')
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('alpha-model')
    expect(rows[1]!.text()).toContain('zeta-model')
  })

  it('shows a chevron indicator on the active sort column', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage() },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    // Default active column is Cost (descending). Cost button should carry
    // a chevron-down svg; Model button should not.
    const costHeader = wrapper.findAll('thead button').find(b => b.text().includes('Cost'))!
    const modelHeader = wrapper.findAll('thead button').find(b => b.text().includes('Model'))!
    expect(costHeader.find('svg').exists()).toBe(true)
    expect(modelHeader.find('svg').exists()).toBe(false)
  })

  it('excludes zero-cost models from the per-model table', async () => {
    // Operator's question is "which models cost what" — free-tier rows
    // contribute nothing to that answer and are visual noise.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        // Paid: should appear
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'gpt-4.1', modelProvider: 'openai',
            promptPrice: 3, completionPrice: 15 }) },
        // Free-tier: should be filtered from the table
        { timestamp: '2026-05-09T13:00:00Z', agentId: 2, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 50, total: 150, reasoning: 0,
            cached: 0, durationMs: 100,
            modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
            // No promptPrice / completionPrice — computeUsageCostBreakdown
            // returns null, so total cost contribution is 0.
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('gpt-4.1')
    expect(text).not.toContain('kimi-k2.5')
  })

  it('keeps free-tier turn count and tokens in the summary row', async () => {
    // Filter is display-layer only — the summary row above the breakdown
    // must answer "what happened in this window" comprehensively, including
    // free-tier activity. Operator looking at "Turns: 6" with only 1
    // billed model should see 6, not 1.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 100, completion: 50,
            promptPrice: 3, completionPrice: 15 }) },
        // Five free-tier turns
        ...Array.from({ length: 5 }, (_, i) => ({
          timestamp: `2026-05-09T1${i}:00:00Z`,
          agentId: 2,
          channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 200, completion: 100, total: 300, reasoning: 0,
            cached: 0, durationMs: 100,
            modelId: 'kimi-k2.5',
          }),
        })),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const text = wrapper.text()
    // 6 total turns (1 paid + 5 free-tier) — summary row honors all.
    expect(text).toContain('Turns')
    expect(text).toMatch(/Turns[\s\S]*?6/)
    // 100 + 5×200 = 1100 prompt tokens — token totals span free-tier too.
    expect(text).toContain('1,100')
  })

  it('shows the all-free-tier empty state when no models contributed cost', async () => {
    // Distinguished from "no data at all" — turns did happen, but the
    // entire summary strip is suppressed (the section is about cost
    // attribution; aggregating free-tier turns/tokens under "Total cost
    // $0.00" was misleading). Only the empty-state message renders.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 50, total: 150, reasoning: 0,
            cached: 0, durationMs: 100, modelId: 'kimi-k2.5',
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('All turns in this window were on free-tier models')
    // Summary strip suppressed: no Total cost / Turns / Prompt tokens labels.
    expect(wrapper.text()).not.toContain('Total cost')
    expect(wrapper.text()).not.toContain('Prompt tokens')
    // No per-model breakdown table either.
    expect(wrapper.find('tbody tr').exists()).toBe(false)
  })

  it('disables CSV export when all data is free-tier', async () => {
    // Same gating as the no-data state — there's nothing meaningful to
    // export when no models contributed cost. Different from the
    // already-tested no-data case (which has zero rows entirely).
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 50, total: 150, reasoning: 0,
            cached: 0, durationMs: 100, modelId: 'kimi-k2.5',
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const csvBtn = wrapper.findAll('button').find(b => b.attributes('title')?.includes('CSV'))
    expect(csvBtn).toBeDefined()
    expect(csvBtn!.attributes('disabled')).toBeDefined()
  })

  it('exposes refresh via defineExpose', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    // The component exposes refresh so a parent could trigger reload after
    // a config change. Just assert the API surface exists.
    expect(typeof (wrapper.vm as { refresh?: () => void }).refresh).toBe('function')
  })

  // ==================== Subscription allocation ====================
  //
  // The Subscription subsection allocates each provider's flat monthly
  // bill across model rows proportionally to total tokens
  // (prompt + completion + reasoning; cached is a subset of prompt, not
  // added on top). Per-provider rows that
  // used to sit in the table footer are gone; the bill breakdown lives
  // in the section subtitle, and any subscription with zero usage this
  // window shows up as an "unallocated" footnote.

  // clearNuxtData() between tests so useFetch's per-URL cache doesn't
  // leak a prior test's /api/providers or /api/metrics/cost payload
  // into the next mount. Same gotcha as the OCR section in
  // settings.page.test.ts.
  beforeEach(() => {
    clearNuxtData()
  })

  /** Fixture: makes both /api/metrics/cost and /api/providers stubs at
   *  once so each test reads as "given this providers config and these
   *  rows, expect this rendered output". */
  function stubSubscriptionFixture(opts: {
    providers: { name: string, paymentModality: 'PER_TOKEN' | 'SUBSCRIPTION', subscriptionMonthlyUsd: number }[]
    rows: { agentId: number, channelType: string, usage: UsagePartial }[]
  }) {
    registerEndpoint('/api/providers', () => opts.providers.map(p => ({
      name: p.name,
      paymentModality: p.paymentModality,
      subscriptionMonthlyUsd: p.subscriptionMonthlyUsd,
      supportedModalities: [p.paymentModality],
    })))
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: opts.rows.map((r, i) => ({
        timestamp: `2026-05-09T12:0${i}:00Z`,
        agentId: r.agentId,
        channelType: r.channelType,
        usageJson: makeUsage(r.usage),
      })),
    }))
  }

  it('allocates a subscription bill across model rows by total tokens', async () => {
    // Ollama Cloud at $100/month, 30d window → $100 prorated. Two models
    // share the bill: 75% tokens / 25% tokens. Expected: $75 / $25.
    // promptPrice=0 keeps these rows in the SUBSCRIPTION partition (the
    // modality comes from the providers endpoint, not the row prices).
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
      ],
      rows: [
        // Model A: 750 total tokens (75% of provider's 1000)
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 500, completion: 250, promptPrice: 0, completionPrice: 0,
        } },
        // Model B: 250 total tokens (25%)
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gemini-3-flash', modelProvider: 'ollama-cloud',
          prompt: 200, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    // 30d default window → full $100 monthly fee is the prorated bill.
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    // Both models render with their allocated share, not "—".
    // formatStatCurrency drops trailing .00 on whole-dollar values.
    expect(text).toContain('$75')
    expect(text).toContain('$25')
    // Section subtitle carries the provider bill breakdown (the data
    // that used to be a row inside the table footer).
    expect(text).toContain('Ollama Cloud')
    expect(text).toContain('$100')
  })

  it('collapses the cost section to just the provider chips when no usage exists', async () => {
    // Pre-fix the section rendered a per-model table with a $0 TOTAL row
    // PLUS a Combined Total row showing the unused subscription bill,
    // even on weeks with no chat activity. The per-token block already
    // hid itself in that case, so the section was lopsided. New
    // behaviour: when no provider has any usage, the section collapses
    // to just the (dimmed) provider chip strip up top — both the
    // per-model table AND the combined-total footer go away. The chips
    // alone carry the "you're paying these subscriptions" message.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    // Provider chips still render — they carry the bill on zero-usage
    // weeks via their own labels, dimmed to signal "no activity yet."
    expect(text).toContain('Ollama Cloud')
    expect(text).toContain('$100')
    expect(text).toContain('OpenAI')
    expect(text).toContain('$20')
    // Per-model table is gone — no <thead> anywhere in the section.
    expect(wrapper.findAll('thead').length).toBe(0)
    // Combined-total footer is gone — no "Combined total" row label.
    expect(text).not.toContain('Combined total')
    // No isolated $0 anywhere; the chips' $20 / $100 are non-zero so
    // a literal "$0" appearing would mean a zeroed sub-total leaked in.
    expect(text).not.toContain('$0')
  })

  it('dims the provider chip when a subscription provider has zero usage', async () => {
    // OpenAI subscribed at $20/month but never used. Ollama Cloud
    // subscribed at $100/month with one model in use. The model row gets
    // $100 allocated; OpenAI shows up as a dimmed (opacity-50) provider
    // chip rather than a footnote line — the visual de-emphasis IS the
    // "no usage this window" signal, replacing the previous explicit
    // footnote text. Asserts (a) both chips render, (b) only the unused
    // one is dimmed, (c) the removed footnote phrasing is gone.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    // The allocated row + both provider amounts on the chips.
    expect(text).toContain('$100')
    expect(text).toContain('$20')

    // Both provider chips render. Find them by their displayName label.
    const chips = wrapper.findAll('button[aria-pressed]')
    const openaiChip = chips.find(c => c.text().includes('OpenAI'))
    const ollamaChip = chips.find(c => c.text().includes('Ollama Cloud'))
    expect(openaiChip).toBeDefined()
    expect(ollamaChip).toBeDefined()
    // Unused provider's chip is visually dimmed AND non-clickable.
    expect(openaiChip!.classes()).toContain('opacity-50')
    expect((openaiChip!.element as HTMLButtonElement).disabled).toBe(true)
    // Provider with usage stays at full opacity, clickable.
    expect(ollamaChip!.classes()).not.toContain('opacity-50')
    expect((ollamaChip!.element as HTMLButtonElement).disabled).toBe(false)

    // The previous "$X unallocated — Provider has no usage this window"
    // phrasing has been removed entirely (the dimmed chip carries that
    // signal now). The methodology disclaimer text DOES still live in
    // the DOM — but inside a hidden hover-tooltip span on the Cost
    // column header — so we don't assert against its presence here.
    expect(text).not.toContain('unallocated')
    expect(text).not.toContain('has no usage this window')
  })

  it('reasoning counts toward the allocation key but cached is not double-counted', async () => {
    // cached ⊂ prompt (it's the cache-read subset of the total input), so
    // the allocation key is prompt + completion + reasoning — cached must
    // NOT be added on top.
    // Model A: prompt 100                      → key 100
    // Model B: prompt 50 (of which cached 50) + reasoning 50 → key 100
    // Equal key → $50/$50 split of a $100 bill. If the code wrongly added
    // cached, model B's key would be 150 → a 60/40 split and "$50" would
    // not appear twice.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
      ],
      rows: [],
    })
    // Raw payloads — makeUsage() can't express cached/reasoning, and we
    // need model B's cached to sit *inside* its prompt count.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 0, total: 100, reasoning: 0, cached: 0,
            durationMs: 100, promptPrice: 0, completionPrice: 0,
            modelId: 'model-a', modelProvider: 'ollama-cloud',
          }) },
        { timestamp: '2026-05-09T12:01:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 50, completion: 0, total: 50, reasoning: 50, cached: 50,
            durationMs: 100, promptPrice: 0, completionPrice: 0,
            modelId: 'model-b', modelProvider: 'ollama-cloud',
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    // 50/50 split — "$50" appears once per row. formatStatCurrency drops
    // trailing .00 on whole-dollar values; the tight match avoids colliding
    // with substrings of larger numbers like "$500".
    const fiftyMatches = text.match(/\$50(?!\d)/g) ?? []
    expect(fiftyMatches.length).toBeGreaterThanOrEqual(2)
  })

  // ==================== Chip-click filter ====================
  //
  // Each provider chip in the Subscription header acts as a toggle
  // filter: clicking scopes the per-model table + tfoot Total to that
  // provider; clicking again clears. Chips for providers with no usage
  // are disabled so the operator can't trap themselves in an empty view.

  it('filters subscription table to one provider when its chip is clicked', async () => {
    // Two providers configured ($100 + $20 = $120 total bill). Both used.
    // Click Ollama Cloud → only its models render, tfoot Total = $100.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gpt-5', modelProvider: 'openai',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    // Before filter: both models render, Total = $120.
    expect(wrapper.text()).toContain('kimi-k2.5')
    expect(wrapper.text()).toContain('gpt-5')
    expect(wrapper.text()).toContain('$120')

    // Click the Ollama Cloud chip.
    const ollamaChip = wrapper.findAll('button')
      .find(b => b.attributes('aria-pressed') !== undefined
        && b.text().includes('Ollama Cloud'))!
    expect(ollamaChip).toBeDefined()
    await ollamaChip.trigger('click')
    await flushPromises()

    const filteredText = wrapper.text()
    // After filter: only Ollama Cloud's model renders, gpt-5 is hidden.
    expect(filteredText).toContain('kimi-k2.5')
    expect(filteredText).not.toContain('gpt-5')
    // Chip itself reports its pressed state to assistive tech.
    expect(ollamaChip.attributes('aria-pressed')).toBe('true')
  })

  it('disables click on chips for providers with no usage this window', async () => {
    // OpenAI configured at $20/mo but with no rows — its chip is muted
    // and the disabled attribute is set so the operator can't select an
    // empty view.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()

    const openaiChip = wrapper.findAll('button')
      .find(b => b.attributes('aria-pressed') !== undefined
        && b.text().includes('OpenAI'))!
    expect(openaiChip).toBeDefined()
    expect(openaiChip.attributes('disabled')).toBeDefined()
  })

  it('clears the filter when the active chip is clicked a second time', async () => {
    // Toggle semantics — click once to filter, click again to clear.
    // After the second click both models reappear and aria-pressed is
    // back to "false".
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gpt-5', modelProvider: 'openai',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()

    const ollamaChip = wrapper.findAll('button')
      .find(b => b.attributes('aria-pressed') !== undefined
        && b.text().includes('Ollama Cloud'))!
    await ollamaChip.trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('gpt-5')

    await ollamaChip.trigger('click')
    await flushPromises()
    // Both models back in view; chip aria-pressed flips to false.
    expect(wrapper.text()).toContain('kimi-k2.5')
    expect(wrapper.text()).toContain('gpt-5')
    expect(ollamaChip.attributes('aria-pressed')).toBe('false')
  })

  it('renders subscription bars when view is switched to chart', async () => {
    // Chart view should show a horizontal bar per subscription model
    // with usage, just like the per-token chart. Two models split a
    // $100 bill 75/25 → both labels visible, both with allocated cost
    // formatted to the chart's $0.0000 precision.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 500, completion: 250, promptPrice: 0, completionPrice: 0,
        } },
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gemini-3-flash', modelProvider: 'ollama-cloud',
          prompt: 200, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    // Flip to chart view. The toggle is in the header; the bar-chart
    // button has title="Bar chart view".
    const chartBtn = wrapper.findAll('button')
      .find(b => b.attributes('title') === 'Bar chart view')!
    expect(chartBtn).toBeDefined()
    await chartBtn.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    // Both subscription models render as chart rows.
    expect(text).toContain('kimi-k2.5')
    expect(text).toContain('gemini-3-flash')
    // No tfoot Total row should be present in chart view (which would
    // surface the "TOTAL" string from the table tbody/tfoot).
    expect(wrapper.findAll('tfoot').length).toBe(0)
  })

  it('color-keys subscription chart bars by provider via a leading swatch', async () => {
    // Two different providers → their swatch elements pick up two
    // different bg-* classes from the palette. We assert distinctness
    // rather than exact color, so the palette can be reordered without
    // breaking the test.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 500, completion: 250, promptPrice: 0, completionPrice: 0,
        } },
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gpt-5', modelProvider: 'openai',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()
    const chartBtn = wrapper.findAll('button')
      .find(b => b.attributes('title') === 'Bar chart view')!
    await chartBtn.trigger('click')
    await flushPromises()

    // Each swatch div carries title=<provider-name>; we use that to
    // pick out the two swatch divs and read their class attributes.
    // The two providers should resolve to two different bg-* tokens
    // from the palette.
    const swatches = wrapper.findAll('div')
      .filter(d => d.attributes('title') === 'ollama-cloud'
        || d.attributes('title') === 'openai')
    expect(swatches.length).toBeGreaterThanOrEqual(2)
    const classByProvider = new Map<string, string>()
    for (const s of swatches) {
      const provider = s.attributes('title')!
      const klass = s.attributes('class') ?? ''
      const bgToken = klass.split(/\s+/).find(t => t.startsWith('bg-'))
      if (bgToken) classByProvider.set(provider, bgToken)
    }
    expect(classByProvider.get('ollama-cloud')).toBeDefined()
    expect(classByProvider.get('openai')).toBeDefined()
    expect(classByProvider.get('ollama-cloud')).not.toBe(classByProvider.get('openai'))
  })

  it('chip cards share the same swatch color as their chart bars', async () => {
    // For each provider, the chip's leading swatch band and the
    // corresponding chart bar's swatch overlay should resolve to the
    // exact same bg-* token, so operators can match chip ↔ bar by
    // color. Asserted in chart view because that's where the bars
    // are rendered.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 500, completion: 250, promptPrice: 0, completionPrice: 0,
        } },
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gpt-5', modelProvider: 'openai',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()
    const chartBtn = wrapper.findAll('button')
      .find(b => b.attributes('title') === 'Bar chart view')!
    await chartBtn.trigger('click')
    await flushPromises()

    function extractBg(klass: string | undefined): string | undefined {
      return klass?.split(/\s+/).find(t => t.startsWith('bg-'))
    }

    // Chip's leading swatch is the first child div of its button.
    const ollamaChip = wrapper.findAll('button')
      .find(b => b.attributes('aria-pressed') !== undefined
        && b.text().includes('Ollama Cloud'))!
    const openaiChip = wrapper.findAll('button')
      .find(b => b.attributes('aria-pressed') !== undefined
        && b.text().includes('OpenAI'))!
    const ollamaChipColor = extractBg(ollamaChip.find('div').attributes('class'))
    const openaiChipColor = extractBg(openaiChip.find('div').attributes('class'))

    // Bar swatches are findable by title=<provider name>.
    const ollamaBarSwatch = wrapper.findAll('div')
      .find(d => d.attributes('title') === 'ollama-cloud')!
    const openaiBarSwatch = wrapper.findAll('div')
      .find(d => d.attributes('title') === 'openai')!
    const ollamaBarColor = extractBg(ollamaBarSwatch.attributes('class'))
    const openaiBarColor = extractBg(openaiBarSwatch.attributes('class'))

    expect(ollamaChipColor).toBeDefined()
    expect(openaiChipColor).toBeDefined()
    expect(ollamaChipColor).toBe(ollamaBarColor)
    expect(openaiChipColor).toBe(openaiBarColor)
  })

  it('renders the per-provider swatch on subscription table rows too', async () => {
    // The swatch should appear in the model name cell in both table
    // and chart views so the operator's color memory carries over
    // when they flip between them. Asserted in table view (the
    // default) because that's the case the user flagged as missing.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 500, completion: 250, promptPrice: 0, completionPrice: 0,
        } },
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'gpt-5', modelProvider: 'openai',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    // Each provider should have at least one swatch element in the
    // rendered DOM whose title attribute points to it — the swatch in
    // the table row's model cell. (Chip swatches don't carry a title
    // since the chip label already names the provider next to them.)
    const ollamaSwatches = wrapper.findAll('span, div')
      .filter(el => el.attributes('title') === 'ollama-cloud')
    const openaiSwatches = wrapper.findAll('span, div')
      .filter(el => el.attributes('title') === 'openai')
    expect(ollamaSwatches.length).toBeGreaterThanOrEqual(1)
    expect(openaiSwatches.length).toBeGreaterThanOrEqual(1)
  })

  it('selected chip border matches its swatch color', async () => {
    // Selection cue is the provider's own color, used as the chip
    // border — so the chip and its swatch share one color identity in
    // both states (the swatch is always shown; the border switches
    // from neutral to that color when active). Assertion: for a given
    // provider, the chip's border-<color>-500 token shares its color
    // stem with the swatch's bg-<color>-500 token.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 100, completion: 50, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()

    const ollamaChip = wrapper.findAll('button')
      .find(b => b.attributes('aria-pressed') !== undefined
        && b.text().includes('Ollama Cloud'))!
    await ollamaChip.trigger('click')
    await flushPromises()

    // After click, the chip carries the active provider's border token.
    const chipClasses = (ollamaChip.attributes('class') ?? '').split(/\s+/)
    const borderToken = chipClasses.find(t => /^border-[a-z]+-500$/.test(t))
    expect(borderToken).toBeDefined()

    // Same color stem as the swatch on the table row's model cell.
    const swatchEl = wrapper.findAll('span, div')
      .find(el => el.attributes('title') === 'ollama-cloud')!
    const swatchClasses = (swatchEl.attributes('class') ?? '').split(/\s+/)
    const swatchToken = swatchClasses.find(t => /^bg-[a-z]+-500$/.test(t))
    expect(swatchToken).toBeDefined()

    const borderStem = borderToken!.replace(/^border-/, '').replace(/-500$/, '')
    const swatchStem = swatchToken!.replace(/^bg-/, '').replace(/-500$/, '')
    expect(borderStem).toBe(swatchStem)
  })

  // ==================== Effective $/1M + break-even (scope C) ====================
  //
  // Each subscription card re-expresses the flat fee as a per-token rate
  // (fee ÷ tokens served × 1M) so it can be eyeballed against per-token
  // providers, and shows the monthly token volume at which the
  // subscription starts beating the operator's realized per-token rate.

  it('shows the effective $/1M rate and break-even on a subscription card', async () => {
    // Ollama Cloud $100/mo serving 10M tokens → effective $10.00/1M.
    // A per-token provider (OpenRouter) bills $1.00 for 1M tokens →
    // realized reference rate $1.00/1M. Break-even = $100 ÷ $1.00/1M ×
    // 1M = 100M tokens/month.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openrouter', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0 },
      ],
      rows: [
        // Subscription side: 10M tokens, zero per-token price keeps it in
        // the SUBSCRIPTION partition.
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 10_000_000, completion: 0, promptPrice: 0, completionPrice: 0,
        } },
        // Per-token side: $1.00 over 1M tokens → $1.00/1M reference rate.
        { agentId: 2, channelType: 'web', usage: {
          modelId: 'gpt-4.1', modelProvider: 'openrouter',
          prompt: 1_000_000, completion: 0, promptPrice: 1.0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('$10.00/1M')
    expect(text).toContain('break-even 100.0M/mo')
  })

  it('does not double-count cached tokens in the break-even reference rate', async () => {
    // Regression for the cached double-count: cached ⊂ prompt, so the
    // realized per-token rate divides cost by prompt + completion +
    // reasoning, NOT + cached.
    // Per-token: prompt 1,000,000 (cached 600,000, free reads) → uncached
    // 400,000 × $1.0/1M = $0.40 over 1,000,000 throughput tokens →
    // $0.40/1M reference. Break-even = $100 ÷ $0.40/1M = 250.0M tok/mo.
    // If cached were wrongly added, the denominator would be 1,600,000 →
    // $0.25/1M → 400.0M, which this asserts against.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openrouter', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0 },
      ],
      rows: [],
    })
    // Raw payloads — UsagePartial can't express cached / cachedReadPrice.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 10_000_000, completion: 0, total: 10_000_000, reasoning: 0, cached: 0,
            durationMs: 100, promptPrice: 0, completionPrice: 0,
            modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          }) },
        { timestamp: '2026-05-09T12:01:00Z', agentId: 2, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 1_000_000, completion: 0, total: 1_000_000, reasoning: 0, cached: 600_000,
            durationMs: 100, promptPrice: 1.0, completionPrice: 0, cachedReadPrice: 0,
            modelId: 'gpt-4.1', modelProvider: 'openrouter',
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('break-even 250.0M/mo')
    expect(text).not.toContain('break-even 400.0M/mo')
  })

  it('omits break-even when there is no per-token activity to compare against', async () => {
    // Effective rate still renders (it needs only the fee and the
    // subscription's own tokens), but with no per-token spend there's no
    // reference rate, so the break-even line is suppressed rather than
    // compared against nothing.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 10_000_000, completion: 0, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('$10.00/1M')
    expect(text).not.toContain('break-even')
  })

  it('shows just the fee on a subscription card with zero usage this window', async () => {
    // OpenAI subscribed but unused → its card carries the bare fee with no
    // derived figures (effective rate is undefined with zero tokens). The
    // used Ollama Cloud card does carry the effective rate.
    stubSubscriptionFixture({
      providers: [
        { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
        { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
          prompt: 10_000_000, completion: 0, promptPrice: 0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const chips = wrapper.findAll('button[aria-pressed]')
    const openaiChip = chips.find(c => c.text().includes('OpenAI'))!
    const ollamaChip = chips.find(c => c.text().includes('Ollama Cloud'))!
    // Unused provider's card: no per-token rate line.
    expect(openaiChip.text()).not.toContain('/1M')
    // Used provider's card: effective rate present.
    expect(ollamaChip.text()).toContain('$10.00/1M')
  })

  // ==================== Per-token provider cards ====================
  //
  // The per-token section gets the same card treatment as subscription:
  // a per-provider rollup chip showing total spend + average $/1M, and
  // clicking it scopes the per-token table/chart/totals to that provider.

  it('renders a per-token provider card with total cost and average $/1M', async () => {
    // OpenRouter billing $1.00 over 1M throughput tokens → $1.00/1M avg.
    stubSubscriptionFixture({
      providers: [
        { name: 'openrouter', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.6', modelProvider: 'openrouter',
          prompt: 1_000_000, completion: 0, promptPrice: 1.0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    const card = wrapper.findAll('button[aria-pressed]').find(c => c.text().includes('OpenRouter'))
    expect(card).toBeDefined()
    expect(card!.text()).toContain('$1.00/1M')
  })

  it('scopes the per-token table to one provider on card click, and clears on re-click', async () => {
    // Two per-token providers, each with one paid model. Click OpenRouter →
    // only its model stays in the table; click again → OpenAI's returns.
    stubSubscriptionFixture({
      providers: [
        { name: 'openrouter', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0 },
        { name: 'openai', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0 },
      ],
      rows: [
        { agentId: 1, channelType: 'web', usage: {
          modelId: 'kimi-k2.6', modelProvider: 'openrouter',
          prompt: 1_000_000, completion: 0, promptPrice: 1.0, completionPrice: 0,
        } },
        { agentId: 2, channelType: 'web', usage: {
          modelId: 'gpt-4.1', modelProvider: 'openai',
          prompt: 1_000_000, completion: 0, promptPrice: 2.0, completionPrice: 0,
        } },
      ],
    })
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    await wrapper.find<HTMLSelectElement>('select[aria-label="Filter by time window"]').setValue('30d')
    await flushPromises()

    // Both per-token models render to start.
    expect(wrapper.text()).toContain('kimi-k2.6')
    expect(wrapper.text()).toContain('gpt-4.1')

    const orChip = wrapper.findAll('button[aria-pressed]').find(c => c.text().includes('OpenRouter'))!
    await orChip.trigger('click')
    await flushPromises()
    // Table scoped to OpenRouter — the OpenAI model row (gpt-4.1) is gone.
    // OpenAI's *card* still renders, but it carries no model id.
    expect(wrapper.text()).toContain('kimi-k2.6')
    expect(wrapper.text()).not.toContain('gpt-4.1')
    expect(orChip.attributes('aria-pressed')).toBe('true')

    await orChip.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('gpt-4.1')
    expect(orChip.attributes('aria-pressed')).toBe('false')
  })
})
