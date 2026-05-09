import { describe, it, expect } from 'vitest'
import { resolveThinkingLock } from '~/utils/thinking-lock'

describe('resolveThinkingLock', () => {
  describe('ollama-cloud + Gemini 2.5 Pro / Gemini 3 (JCLAW-127)', () => {
    // Google documents these as non-disableable. Ollama Cloud has no
    // passthrough for Google-native fields, so the backend can't force it off.

    it('locks gemini-3-flash-preview on ollama-cloud', () => {
      const r = resolveThinkingLock('ollama-cloud', 'gemini-3-flash-preview')
      expect(r.locked).toBe(true)
      expect(r.reason).toMatch(/cannot be disabled/i)
      expect(r.reason).toMatch(/openrouter/i)
    })

    it('locks gemini-3-pro-preview on ollama-cloud', () => {
      expect(resolveThinkingLock('ollama-cloud', 'gemini-3-pro-preview').locked).toBe(true)
    })

    it('locks gemini-2.5-pro on ollama-cloud', () => {
      expect(resolveThinkingLock('ollama-cloud', 'gemini-2.5-pro').locked).toBe(true)
    })

    it('locks gemini-2.5-pro-latest on ollama-cloud (suffix variants)', () => {
      expect(resolveThinkingLock('ollama-cloud', 'gemini-2.5-pro-latest').locked).toBe(true)
    })

    it('is case-insensitive on provider and model', () => {
      expect(resolveThinkingLock('Ollama-Cloud', 'Gemini-3-Flash-Preview').locked).toBe(true)
    })
  })

  describe('ollama-cloud + Gemini families that DO accept thinking_budget=0', () => {
    // Google's API accepts thinking_budget=0 for these; the backend's
    // reasoning_effort=none signal is expected to work for them when/if
    // Ollama's shim starts forwarding. No UI-lock needed.

    it('does not lock gemini-2.5-flash (non-Pro 2.5)', () => {
      expect(resolveThinkingLock('ollama-cloud', 'gemini-2.5-flash').locked).toBe(false)
    })

    it('does not lock gemini-2.5-flash-latest', () => {
      expect(resolveThinkingLock('ollama-cloud', 'gemini-2.5-flash-latest').locked).toBe(false)
    })

    it('does not lock gemini-2.0-flash-exp', () => {
      expect(resolveThinkingLock('ollama-cloud', 'gemini-2.0-flash-exp').locked).toBe(false)
    })
  })

  describe('other providers / models', () => {
    it('does not lock kimi-k2.5 on ollama-cloud', () => {
      // Regression guard: the lock is Gemini-specific. Non-Gemini Ollama
      // Cloud models honor reasoning_effort=none normally.
      expect(resolveThinkingLock('ollama-cloud', 'kimi-k2.5').locked).toBe(false)
    })

    it('does not lock gemini-3-flash-preview on openrouter', () => {
      // OpenRouter honors the off state for Gemini 3 (server-side handling),
      // so the UI should leave the toggle free.
      expect(resolveThinkingLock('openrouter', 'google/gemini-3-flash-preview').locked).toBe(false)
    })

    it('does not lock gemini-2.5-pro on openrouter', () => {
      expect(resolveThinkingLock('openrouter', 'google/gemini-2.5-pro').locked).toBe(false)
    })

    it('does not lock openai/gpt-5 on any provider', () => {
      expect(resolveThinkingLock('openai', 'gpt-5').locked).toBe(false)
      expect(resolveThinkingLock('openrouter', 'openai/gpt-5').locked).toBe(false)
    })
  })

  describe('alwaysThinks pure reasoners (architecture lock)', () => {
    // Pure reasoners (o1/o3, DeepSeek-R1, QwQ) accept the "off" value at the
    // API layer but think anyway. The lock is intrinsic to the model and
    // applies regardless of how it's routed.

    it('locks any model with alwaysThinks=true regardless of provider', () => {
      const model = { id: 'o1', alwaysThinks: true, supportsThinking: true }
      const r = resolveThinkingLock('openai', 'o1', model)
      expect(r.locked).toBe(true)
      expect(r.reason).toMatch(/always thinks/i)
    })

    it('locks alwaysThinks model on openrouter too', () => {
      const model = { id: 'deepseek/deepseek-r1', alwaysThinks: true, supportsThinking: true }
      expect(resolveThinkingLock('openrouter', 'deepseek/deepseek-r1', model).locked).toBe(true)
    })

    it('takes precedence over provider-integration lock', () => {
      // An alwaysThinks model on a provider that ALSO has a routing lock —
      // the architecture-level reason wins because it's the more fundamental
      // explanation.
      const model = { id: 'gemini-2.5-pro', alwaysThinks: true, supportsThinking: true }
      const r = resolveThinkingLock('ollama-cloud', 'gemini-2.5-pro', model)
      expect(r.locked).toBe(true)
      expect(r.reason).toMatch(/always thinks/i)
    })

    it('does not lock when alwaysThinks is false', () => {
      const model = { id: 'kimi-k2.5', alwaysThinks: false, supportsThinking: true }
      expect(resolveThinkingLock('openrouter', 'kimi-k2.5', model).locked).toBe(false)
    })

    it('does not lock when alwaysThinks is undefined (default)', () => {
      const model = { id: 'kimi-k2.5', supportsThinking: true }
      expect(resolveThinkingLock('openrouter', 'kimi-k2.5', model).locked).toBe(false)
    })

    it('does not lock when model arg is omitted', () => {
      // Backwards-compat: callers that don't pass a model fall through to
      // the provider-integration check only.
      expect(resolveThinkingLock('openai', 'o1').locked).toBe(false)
    })
  })

  describe('degenerate inputs', () => {
    it('returns unlocked for null provider', () => {
      expect(resolveThinkingLock(null, 'gemini-3-flash-preview').locked).toBe(false)
    })

    it('returns unlocked for null model', () => {
      expect(resolveThinkingLock('ollama-cloud', null).locked).toBe(false)
    })

    it('returns unlocked for empty strings', () => {
      expect(resolveThinkingLock('', '').locked).toBe(false)
    })

    it('returns unlocked for undefined inputs', () => {
      expect(resolveThinkingLock(undefined, undefined).locked).toBe(false)
    })
  })
})
