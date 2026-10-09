import { describe, it, expect } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { fetchConversationMessages, MESSAGE_PAGE_SIZE } from '~/utils/conversation-messages'

// Serves `count` rows as the endpoint does: oldest first, 200 when unpaged, `limit` (at most 500) from `offset`.
function serve(convoId: number, count: number): number[] {
  const rows = Array.from({ length: count }, (_, i) => ({ id: i + 1, role: 'user', content: `m${i + 1}` }))
  const offsets: number[] = []
  registerEndpoint(`/api/conversations/${convoId}/messages`, async (event) => {
    const { getQuery } = await import('h3')
    const query = getQuery(event)
    const offset = Number(query.offset) || 0
    offsets.push(offset)
    return rows.slice(offset, offset + Math.min(Number(query.limit) || 200, 500))
  })
  return offsets
}

describe('fetchConversationMessages', () => {
  it('pages through a conversation longer than one page, oldest first', async () => {
    const offsets = serve(901, 1298)
    const rows = await fetchConversationMessages(901)
    expect(rows?.length).toBe(1298)
    expect(rows?.map(m => m.id)).toEqual(Array.from({ length: 1298 }, (_, i) => i + 1))
    expect(offsets).toEqual([0, 500, 1000])
  })

  it('asks once more when the last page is exactly full', async () => {
    const offsets = serve(902, 2 * MESSAGE_PAGE_SIZE)
    expect((await fetchConversationMessages(902))?.length).toBe(2 * MESSAGE_PAGE_SIZE)
    expect(offsets).toEqual([0, 500, 1000])
  })

  it('starts at an offset and stops once the caller has gone away', async () => {
    const offsets = serve(903, 1298)
    expect(await fetchConversationMessages(903, 10, () => false)).toBeNull()
    expect(offsets).toEqual([10])
  })
})
