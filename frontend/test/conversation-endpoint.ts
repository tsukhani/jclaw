import { registerEndpoint } from '@nuxt/test-utils/runtime'

interface Row { id: number, usage?: unknown }

/**
 * Serves `rows` (oldest first) as GET /api/conversations/{id}/messages does — by offset, or by the `latest`, `before`
 * and `after` message-id cursors, 200 rows unless a limit up to 500 is asked — and records every query it answers.
 * Serves their usage as GET /api/conversations/{id}/usage does.
 */
export function serveConversation<T extends Row>(convoId: number, rows: T[]): Array<Record<string, string>> {
  const asked: Array<Record<string, string>> = []
  registerEndpoint(`/api/conversations/${convoId}/messages`, async (event) => {
    const { getQuery } = await import('h3')
    const query = getQuery(event) as Record<string, string>
    asked.push(query)
    const limit = Math.min(Number(query.limit) || 200, 500)
    let page: T[]
    if (query.after != null) {
      page = rows.filter(r => r.id > Number(query.after)).slice(0, limit)
    }
    else if (query.before != null || query.latest === 'true') {
      const older = query.before == null ? rows : rows.filter(r => r.id < Number(query.before))
      page = older.slice(Math.max(0, older.length - limit))
    }
    else {
      const offset = Number(query.offset) || 0
      page = rows.slice(offset, offset + limit)
    }
    return structuredClone(page)
  })
  registerEndpoint(`/api/conversations/${convoId}/usage`, async (event) => {
    const { getQuery } = await import('h3')
    const before = Number(getQuery(event).before) || Number.POSITIVE_INFINITY
    return rows.filter(r => r.usage != null && r.id < before).map(r => ({ id: r.id, usage: r.usage }))
  })
  return asked
}
