/**
 * Provider/model combinations where reasoning cannot be disabled regardless
 * of the Thinking toggle state. The chat UI surfaces this as a locked pill
 * with an explanatory tooltip instead of silently ignoring the operator's
 * preference.
 *
 * Two distinct causes converge on the same UX:
 *
 * 1. Model architecture ({@code model.alwaysThinks}). Pure reasoning models
 *    like OpenAI o1/o3, DeepSeek-R1, and Qwen QwQ have no non-thinking
 *    mode \u2014 the provider API accepts a "reasoning off" value but the model
 *    thinks anyway. The flag is operator-set in Settings (or seeded by
 *    discovery in the future); resolveThinkingLock checks it first because
 *    it's intrinsic to the model regardless of how it's routed.
 *
 * 2. Provider integration limit (JCLAW-127). Google's OpenAI-compat docs
 *    state reasoning cannot be turned off for Gemini 2.5 Pro or Gemini 3
 *    (thinking_budget is clamped above zero). Ollama Cloud proxies these
 *    models directly to Google's API and does not expose Google-native
 *    fields, so the backend cannot force-suppress reasoning tokens. The
 *    same models routed through OpenRouter DO honor the off state
 *    (OpenRouter's server-side translation handles it), so the honest
 *    guidance is: use OpenRouter if you need on/off control.
 *
 * Keep provider-lock scope tight: only the specific families the upstream
 * documents as non-disableable. Gemini 2.5 Flash and Gemini 2.0 still
 * accept thinking_budget=0 and remain unlocked.
 */
import type { ProviderModel } from '~/composables/useProviders'

export interface ThinkingLock {
  locked: boolean
  reason: string
}

const UNLOCKED: ThinkingLock = { locked: false, reason: '' }

export function resolveThinkingLock(
  providerName: string | null | undefined,
  modelId: string | null | undefined,
  model?: ProviderModel | null,
): ThinkingLock {
  // Model-architecture lock takes precedence over provider routing \u2014 a pure
  // reasoner is locked-on no matter where it's served from.
  if (model?.alwaysThinks) {
    return {
      locked: true,
      reason: 'This model always thinks \u2014 its architecture has no non-thinking mode. The toggle is fixed on.',
    }
  }

  if (!providerName || !modelId) return UNLOCKED
  const provider = providerName.toLowerCase()
  const id = modelId.toLowerCase()

  if (provider === 'ollama-cloud'
    && (id.startsWith('gemini-3-') || id.startsWith('gemini-2.5-pro'))) {
    return {
      locked: true,
      reason: 'Thinking cannot be disabled on Gemini 2.5 Pro or Gemini 3 via Ollama Cloud (Google API limitation). Use OpenRouter\u2019s Gemini route for full on/off control.',
    }
  }
  return UNLOCKED
}
