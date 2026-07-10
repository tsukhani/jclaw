import { computed, ref, watch, type ComputedRef, type Ref } from 'vue'
import { computeConversationCost, formatConversationCost, formatConversationCostTooltip, type MessageUsage } from '~/utils/usage-cost'
import type { Message } from '~/types/api'

/**
 * Token-usage + cost meter for the chat header (JCLAW-690 stage 5f; behaviour
 * extracted verbatim from pages/chat.vue). Derives the ChatContextMeter's
 * numbers (latest-turn usage, cumulative tokens, running cost) and the
 * per-row "switched model" divider predicate (JCLAW-108) off displayMessages.
 * Pure read-derivation — the cost summary is recomputed only when streaming is
 * idle, so a long turn doesn't re-walk the message list per token.
 */
export interface UseChatUsageMeter {
  shouldShowModelSwitchIndicator: (idx: number) => boolean
  latestAssistantUsage: ComputedRef<MessageUsage | null>
  conversationCumulativeTokens: ComputedRef<number>
  conversationCostSummary: Ref<{ label: string, tooltip: string, turnCount: number } | null>
}

export function useChatUsageMeter(
  displayMessages: Ref<Message[]>,
  streaming: Ref<boolean>,
): UseChatUsageMeter {
  /**
   * Walk displayMessages backwards from idx - 1 to find the most recent PRIOR
   * assistant message with usage — i.e. the model that ran the preceding turn.
   * Returns null when no prior assistant turn exists (the conversation is on
   * its first assistant message, or earlier rows predate JCLAW-107).
   */
  function previousAssistantUsage(idx: number): MessageUsage | null {
    for (let i = idx - 1; i >= 0; i--) {
      const prev = displayMessages.value[i]
      if (prev && prev.role === 'assistant' && prev.usage) return prev.usage
    }
    return null
  }

  /**
   * True when message at idx is an assistant turn whose modelProvider/modelId
   * differs from the previous assistant turn. Drives the "Switched to X"
   * divider between mid-conversation model changes.
   */
  function shouldShowModelSwitchIndicator(idx: number): boolean {
    const msg = displayMessages.value[idx]
    if (!msg || msg.role !== 'assistant' || !msg.usage?.modelId) return false
    const prior = previousAssistantUsage(idx)
    if (!prior || !prior.modelId) return false
    return prior.modelId !== msg.usage.modelId || prior.modelProvider !== msg.usage.modelProvider
  }

  /**
   * Latest assistant turn's usage block, or null when the conversation has none
   * yet. Drives the top-right context meter's numbers — we show the prompt/
   * completion from the most recent turn because that represents the context
   * about to be resent on the next request.
   */
  const latestAssistantUsage = computed<MessageUsage | null>(() => {
    const msgs = displayMessages.value ?? []
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i]
      if (m && m.role === 'assistant' && m.usage) return m.usage
    }
    return null
  })

  /**
   * Cumulative tokens billed across the whole conversation: sum of every
   * assistant turn's prompt + completion. This double-counts the carried
   * context (each turn re-sends the prior history), which is the right
   * thing for "total since the beginning" — it matches what the operator
   * was actually billed for. Distinct from `latestAssistantUsage` which
   * reports the *current* context size and shrinks after a compaction.
   */
  const conversationCumulativeTokens = computed<number>(() => {
    const msgs = displayMessages.value ?? []
    let total = 0
    for (const m of msgs) {
      if (m && m.role === 'assistant' && m.usage) {
        total += (m.usage.prompt ?? 0) + (m.usage.completion ?? 0)
      }
    }
    return total
  })

  /**
   * Running cost summary for the currently open conversation. Honors each turn's
   * own embedded pricing so mixed-model conversations (e.g. Kimi → Flash) total
   * correctly. Null when the conversation has no assistant turns.
   */
  const conversationCostSummary = ref<{ label: string, tooltip: string, turnCount: number } | null>(null)

  // Recompute the cost meter only when streaming is idle — usage lands at
  // end-of-turn, so any recompute mid-stream would walk every message and call
  // computeConversationCost for an unchanged value. The shallowRef migration
  // on messages already prevents per-token cascades; this watch keeps the
  // recompute off the displayMessages tracking path entirely so the meter
  // stays stable through the streaming → idle transition.
  watch(() => [displayMessages.value, streaming.value] as const, ([msgs, isStreaming]) => {
    if (isStreaming) return
    const usages = (msgs ?? [])
      .filter(m => m.role === 'assistant' && m.usage)
      .map(m => m.usage as MessageUsage)
    if (usages.length === 0) {
      conversationCostSummary.value = null
      return
    }
    const breakdown = computeConversationCost(usages)
    const label = formatConversationCost(breakdown)
    if (label === null) {
      conversationCostSummary.value = null
      return
    }
    conversationCostSummary.value = {
      label,
      tooltip: formatConversationCostTooltip(breakdown),
      turnCount: breakdown.turnCount,
    }
  }, { immediate: true })

  return {
    shouldShowModelSwitchIndicator,
    latestAssistantUsage,
    conversationCumulativeTokens,
    conversationCostSummary,
  }
}
