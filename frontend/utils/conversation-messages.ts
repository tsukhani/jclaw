import type { Message } from '~/types/api'

/** The messages endpoint's largest page; asked for none, it answers only the first 200 rows. */
export const MESSAGE_PAGE_SIZE = 500

/**
 * Every message of a conversation from `offset` on, oldest first, fetched page by page until a short page: one unpaged
 * request returns only the first 200, so a long conversation lost its latest turns on reload. Null when `active` turns
 * false between pages, so a caller that went away sends nothing more and never merges half a conversation.
 */
export async function fetchConversationMessages(
  conversationId: number | string,
  offset = 0,
  active: () => boolean = () => true,
): Promise<Message[] | null> {
  const rows: Message[] = []
  for (;;) {
    const page = await $fetch<Message[]>(`/api/conversations/${conversationId}/messages`, {
      query: { limit: MESSAGE_PAGE_SIZE, offset: offset + rows.length },
    }) ?? []
    rows.push(...page)
    if (page.length < MESSAGE_PAGE_SIZE) return rows
    if (!active()) return null
  }
}
