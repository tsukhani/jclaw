import type { ChatQuote } from '~/types/api'

/** A reply started outside the chat page, e.g. from a reminder toast, for the chat page to pick up (JCLAW-1299). */
export interface ChatReplyHandoff {
  agentId: number
  quote: ChatQuote
}

export function useChatReplyHandoff() {
  return useState<ChatReplyHandoff | null>('chat-reply-handoff', () => null)
}
