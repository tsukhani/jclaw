import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import ConversationDetail from '~/pages/conversations/[id].vue'

/**
 * The conversation detail page: the aggregated usage header, the mid-conversation
 * model-switch divider, and the Markdown export.
 *
 * <p>The stats are asserted with arithmetic that is checkable by hand rather than by
 * re-deriving them from the fixture — a test that recomputes the sum the same way the
 * component does would pass against any consistent bug. The cache-savings figure is the
 * one that matters: it re-prices every input token at the uncached rate, so an error
 * there misreports what caching is worth, and nothing on screen would look wrong.
 */

mockNuxtImport('useRoute', () => () => ({
  params: { id: '7' }, query: {}, path: '/conversations/7', fullPath: '/conversations/7',
  hash: '', name: 'conversations-id', meta: {}, matched: [],
}))

const CONVO = {
  id: 7,
  agentId: 1,
  preview: 'Quarterly report draft',
  channelType: 'telegram',
  agentName: 'main-agent',
  peerId: '12345',
  messageCount: 2,
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-01T10:05:00Z',
}

/**
 * 100k prompt of which 40k was a cache read, 1k completion over 20s.
 *
 *   uncached input  60,000 / 1M × $1.00 = $0.060
 *   cache read      40,000 / 1M × $0.10 = $0.004
 *   output           1,000 / 1M × $2.00 = $0.002   → total $0.066
 *   without caching 100,000 / 1M × $1.00 + $0.002  → $0.102
 *   saved $0.036, i.e. 35%.  Speed: 1,000 tok / 20s = 50.0 tok/s.
 */
const PRICED_USAGE = {
  prompt: 100_000,
  completion: 1_000,
  cached: 40_000,
  cacheCreation: 0,
  durationMs: 20_000,
  promptPrice: 1.0,
  completionPrice: 2.0,
  cachedReadPrice: 0.1,
  modelId: 'kimi-k2.5',
  modelProvider: 'ollama-cloud',
}

function msg(over: Record<string, unknown> = {}) {
  return {
    id: 1,
    role: 'assistant',
    content: 'Here is the draft.',
    createdAt: '2026-08-01T10:05:00Z',
    usage: null,
    ...over,
  }
}

function setupApi(opts: { convo?: unknown, messages?: unknown[], missing?: boolean } = {}) {
  registerEndpoint('/api/conversations/7', () => {
    if (opts.missing) throw new Error('no such conversation')
    return opts.convo ?? CONVO
  })
  registerEndpoint('/api/conversations/7/messages', () => opts.messages ?? [])
}

async function mountPage() {
  const c = await mountSuspended(ConversationDetail)
  await flushPromises()
  return c
}

describe('Conversation detail page', () => {
  beforeEach(() => clearNuxtData())

  it('shows an empty state when the conversation cannot be loaded', async () => {
    setupApi({ missing: true })
    const c = await mountPage()
    expect(c.text()).toContain('Conversation not found')
  })

  it('renders the conversation identity', async () => {
    setupApi()
    const c = await mountPage()
    expect(c.text()).toContain('Quarterly report draft')
    expect(c.text()).toContain('main-agent')
    expect(c.text()).toContain('telegram')
    expect(c.text()).toContain('12345')
  })

  it('renders an assistant reply as chat does, math included, and keeps user input raw', async () => {
    setupApi({ messages: [
      msg({ id: 1, role: 'user', content: '**not bold** and $x_1$' }),
      msg({ id: 2, role: 'assistant', content: '**Proof.** Let $p_1, p_2$ be primes.' }),
    ] })
    const c = await mountPage()
    const rendered = c.findAll('[data-testid="message-body-rendered"]')
    expect(rendered).toHaveLength(1)
    expect(rendered[0]!.html()).toContain('<strong>Proof.</strong>')
    expect(rendered[0]!.find('.katex').exists()).toBe(true)
    expect(c.text()).toContain('**not bold** and $x_1$')
  })

  it('renders a placeholder for a conversation with no peer', async () => {
    setupApi({ convo: { ...CONVO, peerId: null, preview: null } })
    const c = await mountPage()
    expect(c.text()).toContain('Conversation')
    expect(c.text()).toContain('—')
  })

  // ─────── aggregated usage ─────────────────────────────────────────────────

  it('totals tokens, speed, cost and cache savings across the turns', async () => {
    setupApi({
      messages: [
        // A user turn carries no usage and must not perturb any total.
        msg({ id: 1, role: 'user', content: 'Draft the report', usage: null }),
        msg({ id: 2, usage: PRICED_USAGE }),
      ],
    })
    const c = await mountPage()
    const text = c.text()

    expect(text).toContain('100,000')
    expect(text).toContain('40,000')
    expect(text).toContain('1,000')
    expect(text).toContain('50.0')
    expect(text).toContain('$0.0660')
    // The saving is what caching bought: $0.102 unpriced-by-cache minus the $0.066 paid.
    expect(text).toContain('$0.0360')
    expect(text).toContain('(35%)')
  })

  it('sums usage over multiple priced turns', async () => {
    setupApi({ messages: [msg({ id: 1, usage: PRICED_USAGE }), msg({ id: 2, usage: PRICED_USAGE })] })
    const c = await mountPage()
    expect(c.text()).toContain('200,000')
    expect(c.text()).toContain('$0.1320')
  })

  it('omits cost entirely when the provider reported no pricing', async () => {
    setupApi({
      messages: [msg({
        id: 1,
        usage: { prompt: 500, completion: 100, cached: 0, cacheCreation: 0, durationMs: 1_000, modelId: 'local' },
      })],
    })
    const c = await mountPage()
    // A self-hosted model has no price, and showing "$0.0000" would assert it is free
    // rather than unknown.
    expect(c.text()).not.toContain('$')
    expect(c.text()).toContain('500')
  })

  it('reports no speed when no turn recorded a duration', async () => {
    setupApi({
      messages: [msg({
        id: 1,
        usage: { ...PRICED_USAGE, durationMs: 0 },
      })],
    })
    const c = await mountPage()
    expect(c.text()).not.toContain('tok/s')
  })

  // ─────── model-switch divider ─────────────────────────────────────────────

  it('marks the turn where the model changed mid-conversation', async () => {
    setupApi({
      messages: [
        msg({ id: 1, usage: { ...PRICED_USAGE, modelId: 'kimi-k2.5' } }),
        msg({ id: 2, role: 'user', content: 'and again?', usage: null }),
        msg({ id: 3, usage: { ...PRICED_USAGE, modelId: 'qwen3.5' } }),
      ],
    })
    const c = await mountPage()
    expect(c.text()).toContain('Switched to ollama-cloud/qwen3.5')
  })

  it('does not mark the first assistant turn as a switch', async () => {
    setupApi({ messages: [msg({ id: 1, usage: PRICED_USAGE })] })
    const c = await mountPage()
    expect(c.text()).not.toContain('Switched to')
  })

  it('does not mark a repeat of the same model as a switch', async () => {
    setupApi({
      messages: [msg({ id: 1, usage: PRICED_USAGE }), msg({ id: 2, usage: PRICED_USAGE })],
    })
    const c = await mountPage()
    expect(c.text()).not.toContain('Switched to')
  })

  it('treats the same model id from a different provider as a switch', async () => {
    setupApi({
      messages: [
        msg({ id: 1, usage: { ...PRICED_USAGE, modelProvider: 'ollama-local' } }),
        msg({ id: 2, usage: { ...PRICED_USAGE, modelProvider: 'ollama-cloud' } }),
      ],
    })
    // Same weights, different host: the cost and latency change, so it is a real switch.
    const c = await mountPage()
    expect(c.text()).toContain('Switched to ollama-cloud/kimi-k2.5')
  })

  // ─────── export ───────────────────────────────────────────────────────────

  it('exports the conversation as Markdown with its metadata and turns', async () => {
    setupApi({
      messages: [
        msg({ id: 1, role: 'user', content: 'Draft the report' }),
        msg({ id: 2, role: 'assistant', content: 'Here is the draft.' }),
      ],
    })
    const c = await mountPage()

    let captured: Blob | null = null
    const createUrl = vi.fn((b: Blob) => {
      captured = b
      return 'blob:mock'
    })
    vi.stubGlobal('URL', { ...URL, createObjectURL: createUrl, revokeObjectURL: vi.fn() })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    await c.findAll('button').find(b => b.attributes('title') === 'Export conversation as Markdown')!
      .trigger('click')
    await flushPromises()

    expect(createUrl).toHaveBeenCalledOnce()
    const markdown = await captured!.text()
    expect(markdown).toContain('# Quarterly report draft')
    expect(markdown).toContain('- **Channel:** telegram')
    expect(markdown).toContain('- **Agent:** main-agent')
    expect(markdown).toContain('## user')
    expect(markdown).toContain('Draft the report')
    expect(markdown).toContain('## assistant')
    expect(markdown).toContain('Here is the draft.')
    expect(clickSpy).toHaveBeenCalledOnce()

    clickSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('labels a tool call in the export rather than exporting an empty turn', async () => {
    setupApi({ messages: [msg({ id: 1, role: 'assistant', content: '' })] })
    const c = await mountPage()

    let captured: Blob | null = null
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn((b: Blob) => {
        captured = b
        return 'blob:mock'
      }),
      revokeObjectURL: vi.fn(),
    })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    await c.findAll('button').find(b => b.attributes('title') === 'Export conversation as Markdown')!
      .trigger('click')
    await flushPromises()

    expect(await captured!.text()).toContain('(tool call)')

    clickSpy.mockRestore()
    vi.unstubAllGlobals()
  })
})
