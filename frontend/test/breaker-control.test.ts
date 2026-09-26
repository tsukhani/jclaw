import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import BreakerControl from '~/components/BreakerControl.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import type { Breaker } from '~/types/api'

/**
 * JCLAW-1301: the one renderer for a breaker, shared by the dashboard, a provider's card and an
 * MCP server's row. Taking a serving target out of rotation is gated on a confirmation;
 * restoring one is not.
 */
function breaker(over: Partial<Breaker> = {}): Breaker {
  return {
    name: 'llm:openai',
    subsystem: 'llm',
    target: 'openai',
    state: 'CLOSED',
    samples: 40,
    failures: 0,
    slowCalls: 0,
    reason: null,
    manual: false,
    ...over,
  }
}

/** With ConfirmDialog mounted, confirm() resolves when its button is clicked. */
function harness(b: Breaker, onChanged: () => void) {
  return defineComponent({
    setup() {
      return () => h('div', [h(BreakerControl, { breaker: b, onChanged }), h(ConfirmDialog)])
    },
  })
}

describe('BreakerControl', () => {
  afterEach(() => {
    document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
  })

  it('describes a serving breaker by its recent calls and names its target on the action', async () => {
    const wrapper = await mountSuspended(BreakerControl, { props: { breaker: breaker({ failures: 3, slowCalls: 2 }) } })

    expect(wrapper.find('[data-testid="breaker-state"]').text()).toBe('CLOSED')
    expect(wrapper.text()).toContain('40 recent calls')
    expect(wrapper.text()).toContain('3/40 failed')
    expect(wrapper.text()).toContain('2 slow')
    const button = wrapper.find('button')
    expect(button.text()).toBe('Isolate')
    expect(button.attributes('aria-label')).toBe('Isolate openai')
  })

  it('counts a single recent call in the singular', async () => {
    const wrapper = await mountSuspended(BreakerControl, { props: { breaker: breaker({ samples: 1 }) } })
    expect(wrapper.text()).toContain('1 recent call')
    expect(wrapper.text()).not.toContain('1 recent calls')
  })

  it('does not isolate until the confirmation resolves', async () => {
    let tripped = false
    registerEndpoint('/api/breakers/trip', {
      method: 'POST',
      handler: () => {
        tripped = true
        return breaker()
      },
    })
    // No <ConfirmDialog> is mounted, so the confirm promise stays pending.
    const wrapper = await mountSuspended(BreakerControl, { props: { breaker: breaker() } })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(tripped).toBe(false)
  })

  it('isolates by registry name once confirmed, then asks its host to refresh', async () => {
    let posted: Record<string, unknown> | null = null
    let changed = 0
    registerEndpoint('/api/breakers/trip', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        posted = await readBody(event) as Record<string, unknown>
        return breaker({ state: 'OPEN', reason: 'MANUAL_TRIP', manual: true })
      },
    })
    const wrapper = await mountSuspended(harness(breaker(), () => changed++))
    await wrapper.find('button[aria-label="Isolate openai"]').trigger('click')
    await flushPromises()

    const confirmButton = Array.from(document.body.querySelectorAll<HTMLButtonElement>('[role="dialog"] button'))
      .find(b => (b.textContent ?? '').trim() === 'Isolate')
    expect(confirmButton, 'the dialog offers Isolate').toBeTruthy()
    confirmButton!.click()

    await vi.waitFor(() => expect(changed).toBe(1))
    expect(posted).toEqual({ name: 'llm:openai' })
  })

  it('restores a breaker that is not serving without asking first', async () => {
    let posted: Record<string, unknown> | null = null
    let changed = 0
    registerEndpoint('/api/breakers/reset', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        posted = await readBody(event) as Record<string, unknown>
        return breaker()
      },
    })
    const wrapper = await mountSuspended(BreakerControl, {
      props: { breaker: breaker({ name: 'mcp:files', subsystem: 'mcp', target: 'files', state: 'HALF_OPEN', reason: 'COOLDOWN_ELAPSED' }), onChanged: () => changed++ },
    })
    expect(wrapper.text()).toContain('probing')

    await wrapper.find('button[aria-label="Restore files"]').trigger('click')
    await vi.waitFor(() => expect(changed).toBe(1))
    expect(posted).toEqual({ name: 'mcp:files' })
  })
})
