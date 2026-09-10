import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import BreakerStatusSection from '~/components/BreakerStatusSection.vue'

/**
 * JCLAW-1170: the dashboard's circuit-breaker panel. What it has to get right is that an
 * operator's own isolation never reads as the provider having broken, and that a healthy
 * install shows nothing rather than an empty box.
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

  it('counts the breakers that are not serving and offers to restore them', async () => {
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
    // A closed breaker offers isolation; the other two offer the way back.
    expect(wrapper.text()).toContain('Isolate')
    expect(wrapper.findAll('button').filter(b => b.text() === 'Restore')).toHaveLength(2)
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

  it('does not isolate a serving provider until the confirmation resolves', async () => {
    let tripped = false
    registerEndpoint('/api/breakers', () => [breaker()])
    registerEndpoint('/api/breakers/trip', {
      method: 'POST',
      handler: () => {
        tripped = true
        return breaker()
      },
    })
    const wrapper = await mountSuspended(BreakerStatusSection)
    await flushPromises()

    // No <ConfirmDialog> is mounted here, so the confirm promise stays pending — which is
    // exactly the assertion: taking a healthy provider out of rotation is gated, restoring
    // one is not.
    await wrapper.findAll('button').filter(b => b.text() === 'Isolate')[0]!.trigger('click')
    await flushPromises()
    expect(tripped).toBe(false)
  })
})
