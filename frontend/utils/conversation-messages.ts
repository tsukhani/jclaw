import type { Message } from '~/types/api'
import { settledRowId } from '~/utils/tool-calls'
import type { MessageUsage } from '~/utils/usage-cost'

/** The messages endpoint's largest page; asked for none, it answers only the first 200 rows. */
export const MESSAGE_PAGE_SIZE = 500

/** Rows the chat fetches per step back through a conversation's history. */
export const HISTORY_PAGE_SIZE = 100

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

/** The {@link HISTORY_PAGE_SIZE} rows just older than message `before`, or the newest ones when it is null; oldest first. */
export async function fetchHistoryPage(conversationId: number, before: number | null): Promise<Message[]> {
  const query = before == null ? { latest: true, limit: HISTORY_PAGE_SIZE } : { before, limit: HISTORY_PAGE_SIZE }
  return await $fetch<Message[]>(`/api/conversations/${conversationId}/messages`, { query }) ?? []
}

/**
 * How far behind the newest shown row a fetch of new rows starts. A row's id is assigned when it is saved but it is
 * seen only once its transaction commits, so one saved before a shown row can still arrive; each row commits on its own.
 */
export const NEW_ROWS_LOOKBACK_MS = 60_000

/**
 * The cursor to fetch a shown conversation's new rows after: its newest settled row at least {@link
 * NEW_ROWS_LOOKBACK_MS} older than its newest row, so the rows hydrate as in a full reload and a late commit is not
 * skipped; else just before its oldest row, so the whole shown window is fetched again; null when nothing is shown.
 */
export function newRowsCursor(shown: Message[]): number | null {
  const saved = shown.filter(m => typeof m.id === 'number' && Number.isFinite(Date.parse(m.createdAt)))
  const horizon = Math.max(...saved.map(m => Date.parse(m.createdAt))) - NEW_ROWS_LOOKBACK_MS
  const settled = settledRowId(saved.filter(m => Date.parse(m.createdAt) <= horizon))
  if (settled != null) return settled
  const oldest = shown.find(m => typeof m.id === 'number')?.id
  return oldest == null ? null : oldest - 1
}

/** Every row newer than message `after` (all of them when null), oldest first. */
export async function fetchMessagesAfter(conversationId: number, after: number | null): Promise<Message[]> {
  const rows: Message[] = []
  for (;;) {
    const cursor = rows.at(-1)?.id ?? after ?? 0
    const page = await $fetch<Message[]>(`/api/conversations/${conversationId}/messages`, {
      query: { after: cursor, limit: MESSAGE_PAGE_SIZE },
    }) ?? []
    rows.push(...page)
    if (page.length < MESSAGE_PAGE_SIZE) return rows
  }
}

export interface MessageUsageRow {
  id: number
  usage: MessageUsage
}

/** Token usage of every message older than message `before`, for totals over rows the chat has not loaded. */
export async function fetchUsageBefore(conversationId: number, before: number): Promise<MessageUsageRow[]> {
  return await $fetch<MessageUsageRow[]>(`/api/conversations/${conversationId}/usage`, { query: { before } }) ?? []
}
