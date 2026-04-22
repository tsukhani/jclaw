/**
 * Provider/model combinations where reasoning cannot be disabled regardless
 * of the Thinking toggle state. The chat UI surfaces this as a locked pill
 * with an explanatory tooltip instead of silently ignoring the operator's
 * preference.
 *
 * JCLAW-127: Google's OpenAI-compat docs state reasoning cannot be turned
 * off for Gemini 2.5 Pro or Gemini 3 models (thinking_budget is clamped
 * above zero). Ollama Cloud proxies these models directly to Google's API
 * and does not document any passthrough for Google-native fields, so the
 * backend cannot force-suppress reasoning tokens. The same models routed
 * through OpenRouter DO honor the off state (OpenRouter's server-side
 * translation handles it), so the honest guidance is: use OpenRouter if
 * you need on/off control.
 *
 * Keep scope tight: only the specific families Google documents as
 * non-disableable. Gemini 2.5 Flash and Gemini 2.0 still accept
 * thinking_budget=0 and remain unlocked.
 */
export interface ThinkingLock {
  locked: boolean
  reason: string
}

const UNLOCKED: ThinkingLock = { locked: false, reason: '' }

export function resolveThinkingLock(
  providerName: string | null | undefined,
  modelId: string | null | undefined,
): ThinkingLock {
  if (!providerName || !modelId) return UNLOCKED
  const provider = providerName.toLowerCase()
  const model = modelId.toLowerCase()

  if (provider === 'ollama-cloud'
    && (model.startsWith('gemini-3-') || model.startsWith('gemini-2.5-pro'))) {
    return {
      locked: true,
      reason: 'Thinking cannot be disabled on Gemini 2.5 Pro or Gemini 3 via Ollama Cloud (Google API limitation). Use OpenRouter\u2019s Gemini route for full on/off control.',
    }
  }
  return UNLOCKED
}
