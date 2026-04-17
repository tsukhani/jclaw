import { describe, it, expect } from 'vitest'
import { shouldDisplayMessage } from '~/utils/display-message-filter'

describe('shouldDisplayMessage (JCLAW-75 regression — reasoning must render live)', () => {
  it('keeps a streaming assistant message that has only reasoning (no content yet)', () => {
    // Exactly the in-flight case the bug report captured: reasoning has started
    // streaming but content hasn't. Before the fix this returned false, hiding
    // the live bubble until content eventually arrived.
    const msg = {
      role: 'assistant',
      _key: 'abc',
      id: null,
      content: '',
      reasoning: 'Considering the question...',
    }
    expect(shouldDisplayMessage(msg, true)).toBe(true)
  })

  it('filters a streaming placeholder that has neither content nor reasoning', () => {
    const msg = {
      role: 'assistant',
      _key: 'abc',
      id: null,
      content: '',
    }
    expect(shouldDisplayMessage(msg, true)).toBe(false)
  })

  it('keeps a streaming message once content arrives (unchanged pre-fix)', () => {
    const msg = {
      role: 'assistant',
      _key: 'abc',
      id: null,
      content: 'Hello',
    }
    expect(shouldDisplayMessage(msg, true)).toBe(true)
  })

  it('always drops tool messages regardless of streaming state', () => {
    const msg = { role: 'tool', content: 'tool result' }
    expect(shouldDisplayMessage(msg, true)).toBe(false)
    expect(shouldDisplayMessage(msg, false)).toBe(false)
  })

  it('keeps a persisted (id set) message even with empty content when not streaming', () => {
    // After streaming ends, genuinely-empty responses should still render so
    // the user sees the failure mode rather than a silently-dropped message.
    const msg = {
      role: 'assistant',
      _key: 'abc',
      id: 123,
      content: '',
      usage: { total: 5 },
    }
    expect(shouldDisplayMessage(msg, false)).toBe(true)
  })

  it('keeps a user message', () => {
    const msg = { role: 'user', content: 'Hi' }
    expect(shouldDisplayMessage(msg, false)).toBe(true)
  })

  it('filters a message with nothing renderable', () => {
    const msg = { role: 'assistant' }
    expect(shouldDisplayMessage(msg, false)).toBe(false)
  })

  it('usage-only messages render (stream-end summary)', () => {
    const msg = {
      role: 'assistant',
      _key: 'abc',
      id: 123,
      usage: { total: 100 },
    }
    expect(shouldDisplayMessage(msg, false)).toBe(true)
  })
})
