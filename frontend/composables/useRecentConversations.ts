import type { Conversation } from '~/types/api'

/**
 * Shared recents for the sidebar "Conversations" section — the 10 most
 * recently updated web-channel conversations across all agents. Exposed as a
 * composable so both the layout (which renders the list) and the chat page
 * (which calls {@code refresh} after creating or renaming a conversation)
 * hit the same useFetch cache via the shared key.
 */
export function useRecentConversations() {
  return useFetch<Conversation[]>('/api/conversations', {
    query: { channel: 'web', limit: 10 },
    default: () => [],
    key: 'recent-conversations',
  })
}
