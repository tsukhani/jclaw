import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import CodingRunMonitor from '~/components/CodingRunMonitor.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * JCLAW-663 — components/CodingRunMonitor.vue live-monitor contract.
 *
 * The monitor stitches two data sources into one seq-ordered step list:
 *   1. a mount-time catch-up GET /api/subagent-runs/{runId}/steps replay, and
 *   2. live `codingrun.step` / `codingrun.done` events off the SSE bus,
 * routed to the component only when the event's runId matches this monitor's.
 * A Kill control routes through confirm() → POST /api/subagent-runs/{id}/kill.
 *
 * This spec pins the operator-visible contract:
 *   - catch-up replay renders on mount;
 *   - run-id routing (a matching-run step appends, a foreign-run step drops);
 *   - the Kill control fires the kill endpoint after confirmation.
 *
 * The SSE bus is mocked (mockNuxtImport('useEventBus')) so the test drives the
 * component's registered handler directly rather than through a live stream —
 * mirroring the guidance in test/setup.ts's EventSource stub. The mock captures
 * the (type, handler) pairs the component registers and replays them through an
 * `emitBus` helper that reproduces useEventBus's exact-match + single-level
 * wildcard dispatch, so the component's own `codingrun.*` subscription and its
 * runId filter are what's under test.
 */

const { busHandlers, emitBus, resetBus } = vi.hoisted(() => {
  const busHandlers: Array<{ type: string, handler: (data: unknown, type: string) => void }> = []
  // Reproduce useEventBus.dispatch: deliver to exact-match subscribers plus any
  // single-level wildcard ("<ns>.*") subscriber for the same namespace.
  function emitBus(type: string, data: unknown) {
    const dot = type.indexOf('.')
    const wildcard = dot > 0 ? `${type.slice(0, dot)}.*` : null
    for (const h of busHandlers) {
      if (h.type === type || (wildcard !== null && h.type === wildcard)) h.handler(data, type)
    }
  }
  function resetBus() {
    busHandlers.length = 0
  }
  return { busHandlers, emitBus, resetBus }
})

mockNuxtImport('useEventBus', () => {
  const on = (type: string, handler: (data: unknown, type: string) => void) => {
    busHandlers.push({ type, handler })
  }
  const off = (type: string, handler: (data: unknown, type: string) => void) => {
    const i = busHandlers.findIndex(h => h.type === type && h.handler === handler)
    if (i >= 0) busHandlers.splice(i, 1)
  }
  // onEvent mirrors the real composable's auto-cleanup registration; the test
  // resets busHandlers between cases so the unmount hook is unnecessary here.
  return () => ({ on, off, onEvent: on })
})

/** Mount the monitor beside a ConfirmDialog so killRun()'s confirm() dialog
 *  renders (ConfirmDialog reads useConfirm()'s module-singleton state; in prod
 *  it lives once at the app root). runId is fixed for the kill flow. */
const KillHarness = defineComponent({
  setup() {
    return () => h('div', [h(CodingRunMonitor, { runId: 7 }), h(ConfirmDialog)])
  },
})

beforeEach(() => {
  resetBus()
  // $fetch isn't cached, but keep parity with the sibling flow specs.
  clearNuxtData()
})

afterEach(() => {
  // Resolve any dialog left open and strip teleported dialog nodes so the
  // useConfirm() singleton doesn't bleed state into the next case.
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
  document.body.querySelectorAll('[role="dialog"]').forEach(el => el.remove())
})

describe('CodingRunMonitor — catch-up transcript on mount', () => {
  it('fetches and renders the recorded steps for its runId', async () => {
    let stepsUrlHit = false
    registerEndpoint('/api/subagent-runs/7/steps', () => {
      stepsUrlHit = true
      return [
        { seq: 1, kind: 'text', text: 'Cloning the repository' },
        { seq: 2, kind: 'tool_call', tool: 'bash', text: 'git status' },
        { seq: 3, kind: 'diff', diff: '--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new' },
      ]
    })

    const component = await mountSuspended(CodingRunMonitor, { props: { runId: 7 } })
    await flushPromises()

    // The catch-up GET fired against the runId-specific URL.
    expect(stepsUrlHit).toBe(true)

    // Each replayed step is rendered: message text, the tool_call tool name,
    // and the diff body line.
    const text = component.text()
    expect(text).toContain('Cloning the repository')
    expect(text).toContain('git status')
    expect(text).toContain('bash')
    expect(text).toContain('+new')
    // The empty-state placeholder is gone once steps exist.
    expect(text).not.toContain('Waiting for the harness')
  })
})

describe('CodingRunMonitor — live codingrun.step routing by runId', () => {
  it('appends a step delivered on the bus for the same runId', async () => {
    registerEndpoint('/api/subagent-runs/7/steps', () => [])

    const component = await mountSuspended(CodingRunMonitor, { props: { runId: 7 } })
    await flushPromises()
    // Empty catch-up → the running placeholder is shown.
    expect(component.text()).toContain('Waiting for the harness')

    emitBus('codingrun.step', { runId: 7, seq: 10, kind: 'text', text: 'Streaming token one' })
    await flushPromises()

    expect(component.text()).toContain('Streaming token one')
    expect(component.text()).not.toContain('Waiting for the harness')
  })

  it('drops a step for a different runId while still accepting the matching one', async () => {
    registerEndpoint('/api/subagent-runs/7/steps', () => [])

    const component = await mountSuspended(CodingRunMonitor, { props: { runId: 7 } })
    await flushPromises()

    // A step addressed to a different run must not render here (run-id routing).
    emitBus('codingrun.step', { runId: 42, seq: 11, kind: 'text', text: 'Belongs to another run' })
    await flushPromises()
    expect(component.text()).not.toContain('Belongs to another run')
    // Still empty — the placeholder proves the foreign step was dropped, not the
    // bus being dead.
    expect(component.text()).toContain('Waiting for the harness')

    // A matching-run step on the same bus does render, proving the handler is
    // live and only the runId filter rejected the foreign event.
    emitBus('codingrun.step', { runId: 7, seq: 12, kind: 'text', text: 'Belongs to this run' })
    await flushPromises()
    expect(component.text()).toContain('Belongs to this run')
    expect(component.text()).not.toContain('Belongs to another run')
  })
})

describe('CodingRunMonitor — Kill control', () => {
  it('POSTs the kill endpoint after the operator confirms', async () => {
    let killPosted = false
    const captured: { body: { reason?: string } | null } = { body: null }
    registerEndpoint('/api/subagent-runs/7/steps', () => [])
    registerEndpoint('/api/subagent-runs/7/kill', {
      method: 'POST',
      handler: async (event) => {
        const { readBody } = await import('h3')
        captured.body = await readBody(event)
        killPosted = true
        return { killed: true, status: 'killed', message: 'Run killed' }
      },
    })

    const component = await mountSuspended(KillHarness)
    await flushPromises()

    // The header Kill control is visible while the run is running.
    const killBtn = component.findAll('button').find(b => b.text().trim() === 'Kill')
    expect(killBtn).toBeTruthy()
    await killBtn!.trigger('click')
    // killRun() awaits confirm(), which opens the teleported ConfirmDialog.
    await flushPromises()

    // Confirm the danger dialog — its confirm button carries the confirmText.
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Kill run')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()

    // The POST resolves through Nitro's multi-microtask chain; poll for it.
    await vi.waitFor(() => expect(killPosted).toBe(true))
    // The mutation carries an operator-reason body.
    expect(typeof captured.body?.reason).toBe('string')
    expect(captured.body?.reason?.length ?? 0).toBeGreaterThan(0)
  })
})
