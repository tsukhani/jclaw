import { describe, it, expect } from 'vitest'
import { thinkingHeaderLabel, initCollapsedState, type ThinkingMessage } from '~/utils/thinking'

describe('thinkingHeaderLabel', () => {
  it('formats persisted backend duration to two decimals', () => {
    const msg = { usage: { reasoningDurationMs: 16780 } }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 16.78 seconds')
  })

  it('falls back to live in-flight capture when no persisted duration', () => {
    const msg = { _thinkingDurationMs: 3456 }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 3.46 seconds')
  })

  it('prefers live over persisted when both are present (in-flight stamp wins)', () => {
    // When a message has been stamped live during streaming AND the final
    // usage frame has since arrived with a slightly different number (backend
    // timing is ~network-latency shorter), we keep the value the user saw
    // first rather than retroactively revising it.
    const msg = { usage: { reasoningDurationMs: 1710 }, _thinkingDurationMs: 1840 }
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 1.84 seconds')
  })

  it('falls back to persisted when no live capture exists (reloaded-from-history case)', () => {
    const msg = { usage: { reasoningDurationMs: 5000 } }
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

  it('returns "Thinking" while _thinkingInProgress is true — even if a duration has leaked in', () => {
    // Simulates the backend-provider quirk where a `status` frame carrying
    // usage.reasoningDurationMs arrives before the first content token.
    // Without the in-progress guard, thinkingHeaderLabel would flip early.
    const msg = {
      _thinkingInProgress: true,
      usage: { reasoningDurationMs: 9780 },
    }
    expect(thinkingHeaderLabel(msg)).toBe('Thinking')
  })

  it('returns "Thinking" while _thinkingInProgress is true — even with live duration set', () => {
    const msg = {
      _thinkingInProgress: true,
      _thinkingDurationMs: 5000,
    }
    expect(thinkingHeaderLabel(msg)).toBe('Thinking')
  })

  it('flips to "Thought for …" once _thinkingInProgress flips to false', () => {
    const msg: ThinkingMessage = {
      _thinkingInProgress: true,
      _thinkingDurationMs: 9780,
    }
    expect(thinkingHeaderLabel(msg)).toBe('Thinking')
    msg._thinkingInProgress = false
    expect(thinkingHeaderLabel(msg)).toBe('Thought for 9.78 seconds')
  })
})

describe('initCollapsedState', () => {
  // ThinkingMessage doesn't declare role/content (they live on a different
  // interface); include them via intersection so these fixtures match the
  // actual chat-message shape the helper is wired against.
  type TestMsg = ThinkingMessage & { role?: string, content?: string }

  it('collapses messages carrying persisted reasoning duration', () => {
    const msgs: TestMsg[] = [
      { role: 'user', content: 'Why is the sky blue?' },
      { role: 'assistant', reasoning: 'Rayleigh scattering...', usage: { reasoningDurationMs: 16780 } },
    ]
    initCollapsedState(msgs)
    expect(msgs[0]!.thinkingCollapsed).toBeUndefined()
    expect(msgs[1]!.thinkingCollapsed).toBe(true)
  })

  it('collapses pre-feature messages that carry only reasoning text', () => {
    const msgs: TestMsg[] = [
      { role: 'assistant', reasoning: 'some text' },
    ]
    initCollapsedState(msgs)
    expect(msgs[0]!.thinkingCollapsed).toBe(true)
  })

  it('leaves non-reasoning messages untouched', () => {
    const msgs: TestMsg[] = [
      { role: 'user', content: 'Hello' },
      { role: 'assistant', content: 'Hi there' },
    ]
    initCollapsedState(msgs)
    expect(msgs[0]!.thinkingCollapsed).toBeUndefined()
    expect(msgs[1]!.thinkingCollapsed).toBeUndefined()
  })

  it('is idempotent — calling twice does not re-open already-collapsed bubbles', () => {
    const msgs: TestMsg[] = [
      { role: 'assistant', reasoning: 'text', usage: { reasoningDurationMs: 500 }, thinkingCollapsed: false },
    ]
    initCollapsedState(msgs)
    expect(msgs[0]!.thinkingCollapsed).toBe(true)
    initCollapsedState(msgs)
    expect(msgs[0]!.thinkingCollapsed).toBe(true)
  })
})
