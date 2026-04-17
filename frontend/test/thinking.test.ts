import { describe, it, expect } from 'vitest'
import { thinkingHeaderLabel, initCollapsedState } from '~/utils/thinking'

describe('thinkingHeaderLabel', () => {
  it('formats persisted backend duration to two decimals', () => {
    const msg = { usage: { reasoningDurationMs: 16780 } }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 16.78 seconds')
  })

  it('falls back to live in-flight capture when no persisted duration', () => {
    const msg = { _thinkingDurationMs: 3456 }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 3.46 seconds')
  })

  it('prefers persisted over live when both are present', () => {
    const msg = { usage: { reasoningDurationMs: 5000 }, _thinkingDurationMs: 999 }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 5.00 seconds')
  })

  it('degrades to "Thinking" for pre-feature history (no duration)', () => {
    const msg = { reasoning: 'some text', usage: { reasoningDurationMs: 0 } }
    expect(thinkingHeaderLabel(msg)).toBe('Thinking')
  })

  it('degrades to "Thinking" when usage is missing entirely', () => {
    const msg = { reasoning: 'some text' }
    expect(thinkingHeaderLabel(msg)).toBe('Thinking')
  })

  it('handles sub-second durations correctly', () => {
    const msg = { usage: { reasoningDurationMs: 42 } }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 0.04 seconds')
  })
})

describe('initCollapsedState', () => {
  it('collapses messages carrying persisted reasoning duration', () => {
    const msgs: any[] = [
      { role: 'user', content: 'Why is the sky blue?' },
      { role: 'assistant', reasoning: 'Rayleigh scattering...', usage: { reasoningDurationMs: 16780 } },
    ]
    initCollapsedState(msgs)
    expect(msgs[0].thinkingCollapsed).toBeUndefined()
    expect(msgs[1].thinkingCollapsed).toBe(true)
  })

  it('collapses pre-feature messages that carry only reasoning text', () => {
    const msgs: any[] = [
      { role: 'assistant', reasoning: 'some text' },
    ]
    initCollapsedState(msgs)
    expect(msgs[0].thinkingCollapsed).toBe(true)
  })

  it('leaves non-reasoning messages untouched', () => {
    const msgs: any[] = [
      { role: 'user', content: 'Hello' },
      { role: 'assistant', content: 'Hi there' },
    ]
    initCollapsedState(msgs)
    expect(msgs[0].thinkingCollapsed).toBeUndefined()
    expect(msgs[1].thinkingCollapsed).toBeUndefined()
  })

  it('is idempotent — calling twice does not re-open already-collapsed bubbles', () => {
    const msgs: any[] = [
      { role: 'assistant', reasoning: 'text', usage: { reasoningDurationMs: 500 }, thinkingCollapsed: false },
    ]
    initCollapsedState(msgs)
    expect(msgs[0].thinkingCollapsed).toBe(true)
    initCollapsedState(msgs)
    expect(msgs[0].thinkingCollapsed).toBe(true)
  })
})
