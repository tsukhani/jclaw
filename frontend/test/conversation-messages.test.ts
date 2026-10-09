import { describe, it, expect } from 'vitest'
import type { Message } from '~/types/api'
import {
  fetchConversationMessages,
  fetchHistoryPage,
  fetchMessagesAfter,
  HISTORY_PAGE_SIZE,
  MESSAGE_PAGE_SIZE,
  newRowsCursor,
  NEW_ROWS_LOOKBACK_MS,
} from '~/utils/conversation-messages'
import { serveConversation } from './conversation-endpoint'

const rows = (count: number) => Array.from({ length: count }, (_, i) => ({ id: i + 1, role: 'user', content: `m${i + 1}` }))
const ids = (from: number, to: number) => Array.from({ length: to - from + 1 }, (_, i) => from + i)

describe('fetchConversationMessages', () => {
  it('pages through a conversation longer than one page, oldest first', async () => {
    const asked = serveConversation(901, rows(1298))
    const loaded = await fetchConversationMessages(901)
    expect(loaded?.map(m => m.id)).toEqual(ids(1, 1298))
    expect(asked.map(q => q.offset)).toEqual(['0', '500', '1000'])
  })

  it('asks once more when the last page is exactly full', async () => {
    const asked = serveConversation(902, rows(2 * MESSAGE_PAGE_SIZE))
    expect((await fetchConversationMessages(902))?.length).toBe(2 * MESSAGE_PAGE_SIZE)
    expect(asked.map(q => q.offset)).toEqual(['0', '500', '1000'])
  })

  it('starts at an offset and stops once the caller has gone away', async () => {
    const asked = serveConversation(903, rows(1298))
    expect(await fetchConversationMessages(903, 10, () => false)).toBeNull()
    expect(asked.map(q => q.offset)).toEqual(['10'])
  })
})

describe('fetchHistoryPage', () => {
  it('fetches the newest page, then the page just older than a message, oldest first', async () => {
    serveConversation(904, rows(250))
    expect((await fetchHistoryPage(904, null)).map(m => m.id)).toEqual(ids(251 - HISTORY_PAGE_SIZE, 250))
    expect((await fetchHistoryPage(904, 151)).map(m => m.id)).toEqual(ids(51, 150))
    expect((await fetchHistoryPage(904, 51)).map(m => m.id)).toEqual(ids(1, 50))
  })
})

describe('fetchMessagesAfter', () => {
  it('pages forward from the cursor by the last row each page returned', async () => {
    const asked = serveConversation(905, rows(1298))
    expect((await fetchMessagesAfter(905, 100)).map(m => m.id)).toEqual(ids(101, 1298))
    expect(asked.map(q => q.after)).toEqual(['100', '600', '1100'])
  })

  it('fetches from the first row with no cursor', async () => {
    serveConversation(906, rows(3))
    expect((await fetchMessagesAfter(906, null)).map(m => m.id)).toEqual([1, 2, 3])
  })
})

describe('newRowsCursor', () => {
  const at = (seconds: number) => new Date(Date.UTC(2026, 9, 9, 12, 0, seconds)).toISOString()
  const row = (id: number | undefined, role: Message['role'], content: string | null, seconds: number | null,
    extra: Partial<Message> = {}) => ({ id, role, content, createdAt: seconds == null ? '' : at(seconds), ...extra }) as Message

  it('starts at the newest settled reply a minute or more older than the newest row, so a late commit is fetched', () => {
    const shown = [
      row(1, 'user', 'q', 0),
      row(2, 'assistant', 'a', 10),
      row(3, 'user', 'q2', 100),
      row(4, 'assistant', 'b', 110),
      row(5, 'assistant', 'stopped', 150, { messageKind: 'stop_marker' }),
      row(undefined, 'assistant', 'streamed, no id yet', null),
    ]
    // Reply 4 is newer than the horizon (150 s less 60 s), so a row saved before it may still be uncommitted.
    expect(NEW_ROWS_LOOKBACK_MS).toBe(60_000)
    expect(newRowsCursor(shown)).toBe(2)
  })

  it('falls back to just before the oldest shown row when nothing settled is that old, then to nothing', () => {
    expect(newRowsCursor([row(40, 'user', 'q', 0), row(41, 'assistant', 'a', 30)])).toBe(39)
    expect(newRowsCursor([row(40, 'user', 'q', 0), row(41, 'assistant', null, 90, { toolCalls: [] })])).toBe(39)
    expect(newRowsCursor([])).toBeNull()
  })
})
