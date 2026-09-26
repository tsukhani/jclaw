import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import BreakerStatusSection from '~/components/BreakerStatusSection.vue'

/**
 * JCLAW-1170: the dashboard's circuit-breaker panel. What it has to get right is that an
 * operator's own isolation never reads as the provider having broken, and that a healthy
 * install shows nothing rather than an empty box. Since JCLAW-1301 it lists only the breakers
 * that are not serving; each one's state and action are covered in breaker-control.test.ts.
 */
function breaker(over: Record<string, unknown> = {}) {
  return {
    name: 'llm:openai',
    subsystem: 'llm',
    target: 'openai',
    state: 'CLOSED',
    samples: 40,
    failures: 0,
    slowCalls: 0,
    failureRate: 0,
    slowCallRate: 0,
    reason: null,
    manual: false,
    ...over,
  }
}

describe('BreakerStatusSection', () => {
  beforeEach(() => clearNuxtData())

  it('renders nothing when no breaker has been minted yet', async () => {
    registerEndpoint('/api/breakers', () => [])
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()
    expect(wrapper.find('[data-testid="breaker-status"]').exists()).toBe(false)
  })

  it('renders nothing while every breaker is serving (JCLAW-1301)', async () => {
    registerEndpoint('/api/breakers', () => [
      breaker(),
      breaker({ name: 'mcp:files', subsystem: 'mcp', target: 'files' }),
    ])
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()
    expect(wrapper.find('[data-testid="breaker-status"]').exists()).toBe(false)
  })

  it('lists only the breakers that are not serving and offers to restore each', async () => {
    registerEndpoint('/api/breakers', () => [
      breaker(),
      breaker({ name: 'llm:ollama', target: 'ollama', state: 'OPEN', samples: 20, failures: 14, reason: 'FAILURE_RATE' }),
      breaker({ name: 'mcp:files', subsystem: 'mcp', target: 'files', state: 'HALF_OPEN', reason: 'COOLDOWN_ELAPSED' }),
    ])
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Circuit Breakers')
    expect(text).toContain('2 not serving')
    expect(text).toContain('tripped on failure rate')
    expect(text).toContain('14/20 failed')
    expect(text).toContain('probing')
    // A serving breaker lives beside its provider or server, not here.
    expect(wrapper.find('[data-testid="breaker-row-llm:openai"]').exists()).toBe(false)
    expect(text).not.toContain('Isolate')
    expect(wrapper.findAll('button').filter(b => b.text() === 'Restore')).toHaveLength(2)
  })

  it('groups providers before servers and puts open breakers before probing ones', async () => {
    registerEndpoint('/api/breakers', () => [
      breaker({ name: 'mcp:alpha', subsystem: 'mcp', target: 'alpha', state: 'HALF_OPEN', samples: 0, reason: 'COOLDOWN_ELAPSED' }),
      breaker({ name: 'mcp:zulu', subsystem: 'mcp', target: 'zulu', state: 'OPEN', samples: 10, failures: 6, reason: 'FAILURE_RATE' }),
      breaker({ name: 'llm:openai', target: 'openai', state: 'OPEN', reason: 'FAILURE_RATE' }),
      breaker({ name: 'llm:anthropic', target: 'anthropic', state: 'HALF_OPEN', reason: 'COOLDOWN_ELAPSED' }),
      breaker({ name: 'llm:groq', target: 'groq' }),
    ])
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()

    const groups = wrapper.findAll('section')
    expect(groups.map(g => g.find('h3').text())).toEqual(['LLM providers', 'MCP servers'])
    const rowsOf = (g: typeof groups[number]) => g.findAll('[data-testid^="breaker-row-"]').map(r => r.attributes('data-testid'))
    expect(rowsOf(groups[0]!)).toEqual(['breaker-row-llm:openai', 'breaker-row-llm:anthropic'])
    expect(rowsOf(groups[1]!)).toEqual(['breaker-row-mcp:zulu', 'breaker-row-mcp:alpha'])
    // The group heading carries the subsystem; the rows do not repeat it.
    expect(groups[0]!.find('[data-testid="breaker-row-llm:openai"]').text()).not.toMatch(/\bllm\b/)
  })

  it('links each breaker to where its provider or server is configured', async () => {
    registerEndpoint('/api/breakers', () => [
      breaker({ state: 'OPEN', reason: 'FAILURE_RATE' }),
      breaker({ name: 'mcp:files', subsystem: 'mcp', target: 'files', state: 'OPEN', reason: 'FAILURE_RATE' }),
    ])
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()

    const home = (name: string) => wrapper.find(`[data-testid="breaker-row-${name}"] [data-testid="breaker-home"]`)
    expect(home('llm:openai').attributes('href')).toBe('/settings?section=providers')
    expect(home('llm:openai').text()).toBe('openai')
    expect(home('mcp:files').attributes('href')).toBe('/mcp-servers')
  })

  it('reports an operator isolation as a decision, not as a provider fault', async () => {
    registerEndpoint('/api/breakers', () => [
      breaker({ state: 'OPEN', samples: 0, failures: 0, reason: 'MANUAL_TRIP', manual: true }),
    ])
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()

    expect(wrapper.text()).toContain('isolated by you')
    expect(wrapper.text()).not.toContain('tripped on')
  })

  it('restores a breaker by its registry name, with no confirmation step', async () => {
    let posted: Record<string, unknown> | null = null
    registerEndpoint('/api/breakers', () => [
      breaker({ state: 'OPEN', reason: 'FAILURE_RATE' }),
    ])
    registerEndpoint('/api/breakers/reset', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        posted = await readBody(event) as Record<string, unknown>
        return breaker()
      },
    })
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()

    await wrapper.findAll('button').filter(b => b.text() === 'Restore')[0]!.trigger('click')
    await vi.waitUntil(() => posted !== null)
    // The colon-bearing registry name is what identifies a breaker, not the display target.
    expect(posted).toEqual({ name: 'llm:openai' })
  })
})

describe('BreakerStatusSection — a failed restore (JCLAW-1221)', () => {
  it('says why instead of doing nothing', async () => {
    registerEndpoint('/api/breakers', () => [breaker({ state: 'OPEN', reason: 'FAILURE_RATE' })])
    const off = registerEndpoint('/api/breakers/reset', {
      method: 'POST',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const wrapper = await mountSuspended(BreakerStatusSection)
      await flushPromises()
      await wrapper.findAll('button').filter(b => b.text() === 'Restore')[0]!.trigger('click')
      await vi.waitFor(() => expect(wrapper.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(wrapper.find('[data-testid="api-error"]').text()).toContain('/api/breakers/reset')
    }
    finally {
      off()
    }
  })
})
