import type { Page } from '@playwright/test'
import { test, expect, gotoPage, applyFilter, expectFilterChip, blockApiWrites, json } from './helpers'

/**
 * UAT-19 — Subagents page, parent-conversation column (JCLAW-1208).
 *
 * The page reads /api/subagent-runs from the operator's live instance, so the list is stubbed
 * under 990xxx ids and honours the query params the page sends. Only the contract check reads real rows.
 */

const CONVERSATION_A = 990201
const CONVERSATION_B = 990202

interface StubRun {
  id: number
  parentConversationId: number
  childAgentName: string
  startedAt: string
}

const RUNS: StubRun[] = [
  { id: 990601, parentConversationId: CONVERSATION_A, childAgentName: 'e2e-uat-child-a1', startedAt: '2026-09-01T10:00:00Z' },
  { id: 990602, parentConversationId: CONVERSATION_A, childAgentName: 'e2e-uat-child-a2', startedAt: '2026-09-01T10:05:00Z' },
  { id: 990603, parentConversationId: CONVERSATION_B, childAgentName: 'e2e-uat-child-b1', startedAt: '2026-09-02T09:00:00Z' },
  { id: 990604, parentConversationId: CONVERSATION_B, childAgentName: 'e2e-uat-child-b2', startedAt: '2026-09-02T09:05:00Z' },
]

function sortKey(sort: string | null, run: StubRun): number {
  if (sort === 'conversation') return run.parentConversationId
  if (sort === 'started') return Date.parse(run.startedAt)
  return run.id
}

/** Stubs the list and guards writes; returns every query the page sent and the writes it blocked. */
async function stubRuns(page: Page) {
  const queries: URLSearchParams[] = []
  await page.route(url => url.pathname === '/api/subagent-runs', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const query = new URL(route.request().url()).searchParams
    queries.push(query)
    const conversation = query.get('parentConversationId')
    const sort = query.get('sort')
    const dir = query.get('dir') === 'asc' ? 1 : -1
    const matching = RUNS.filter(r => !conversation || String(r.parentConversationId) === conversation)
    // The endpoint's conversation order: groups by dir, newest run first inside a group.
    const sorted = [...matching].sort((a, b) => dir * (sortKey(sort, a) - sortKey(sort, b))
      || (sort === 'conversation' ? Date.parse(b.startedAt) - Date.parse(a.startedAt) : a.id - b.id))
    const offset = Number(query.get('offset') ?? 0)
    const limit = Number(query.get('limit') ?? 100)
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      headers: { 'X-Total-Count': String(matching.length) },
      body: JSON.stringify(sorted.slice(offset, offset + limit).map(r => ({
        id: r.id,
        parentAgentId: 990001,
        parentAgentName: 'e2e-uat-parent',
        childAgentId: r.id + 1000,
        childAgentName: r.childAgentName,
        parentConversationId: r.parentConversationId,
        childConversationId: r.id + 2000,
        label: null,
        mode: 'session',
        status: 'COMPLETED',
        startedAt: r.startedAt,
        endedAt: r.startedAt,
        outcome: null,
        workdir: null,
      }))),
    })
  })
  const writes = await blockApiWrites(page)
  return { queries, writes }
}

function runCell(page: Page, id: number) {
  return page.getByRole('cell', { name: `#${id}`, exact: true })
}

async function expectRunsShown(page: Page, shown: number[], hidden: number[] = []) {
  for (const id of shown) await expect(runCell(page, id)).toBeVisible()
  for (const id of hidden) await expect(runCell(page, id)).toHaveCount(0)
}

test.describe('UAT-19 subagents page', () => {
  test('the Conversation column links each run to its parent conversation', async ({ page }) => {
    const { writes } = await stubRuns(page)
    await gotoPage(page, '/subagents')

    for (const conversation of [CONVERSATION_A, CONVERSATION_B]) {
      await expect(page.getByRole('link', { name: `#${conversation}`, exact: true }).first())
        .toHaveAttribute('href', `/chat?conversation=${conversation}`)
    }
    expect(writes()).toEqual([])
  })

  test('runs group under a header per parent conversation by default', async ({ page }) => {
    const { queries, writes } = await stubRuns(page)
    await gotoPage(page, '/subagents')

    await expect(page.getByRole('rowheader')).toHaveText([`Conversation #${CONVERSATION_B}`, `Conversation #${CONVERSATION_A}`])
    expect(queries[0]?.get('sort')).toBe('conversation')
    expect(queries[0]?.get('dir')).toBe('desc')
    expect(writes()).toEqual([])
  })

  test('the column control narrows the list to one conversation, and its chip clears it', async ({ page }) => {
    const { queries, writes } = await stubRuns(page)
    await gotoPage(page, '/subagents')
    await expectRunsShown(page, [990601, 990602, 990603, 990604])

    await page.getByRole('button', { name: `Show only runs from conversation #${CONVERSATION_A}` }).first().click()
    const clear = page.getByRole('button', { name: 'Clear conversation filter' })
    await expect(clear).toBeVisible()
    await expectRunsShown(page, [990601, 990602], [990603, 990604])
    expect(queries.at(-1)?.get('parentConversationId')).toBe(String(CONVERSATION_A))

    await clear.click()
    await expectRunsShown(page, [990601, 990602, 990603, 990604])
    await expect(clear).toHaveCount(0)
    expect(queries.at(-1)?.has('parentConversationId')).toBe(false)
    expect(writes()).toEqual([])
  })

  test('removing the conversation token from the filter bar restores the full list', async ({ page }) => {
    const { queries, writes } = await stubRuns(page)
    await gotoPage(page, '/subagents')
    await expectRunsShown(page, [990601, 990602, 990603, 990604])

    await applyFilter(page, `parentConversation:${CONVERSATION_B}`)
    await expectFilterChip(page, 'parentConversation', String(CONVERSATION_B))
    await expectRunsShown(page, [990603, 990604], [990601, 990602])
    await expect(page.getByRole('button', { name: 'Clear conversation filter' })).toBeVisible()

    await page.getByRole('button', { name: `Remove filter parentConversation: ${CONVERSATION_B}` }).click()
    await expectRunsShown(page, [990601, 990602, 990603, 990604])
    await expect(page.getByRole('button', { name: 'Clear conversation filter' })).toHaveCount(0)
    expect(queries.at(-1)?.has('parentConversationId')).toBe(false)
    expect(writes()).toEqual([])
  })

  test('sorting by another column drops the group headers', async ({ page }) => {
    const { queries, writes } = await stubRuns(page)
    await gotoPage(page, '/subagents')
    await expect(page.getByRole('rowheader')).toHaveCount(2)

    await page.getByRole('button', { name: /^Started/ }).click()
    await expect(page.getByRole('rowheader')).toHaveCount(0)
    await expectRunsShown(page, [990601, 990602, 990603, 990604])
    expect(queries.at(-1)?.get('sort')).toBe('started')
    expect(writes()).toEqual([])
  })

  test('the live runs endpoint orders by parent conversation and carries each run\'s label', async ({ request }) => {
    const { status, body } = await json(request, '/api/subagent-runs?sort=conversation')
    expect(status).toBe(200)
    const rows = body as Array<Record<string, unknown>>
    expect(Array.isArray(rows)).toBe(true)
    for (const row of rows) expect(Object.keys(row), `run #${row.id}`).toContain('label')
    const ids = rows.map(r => r.parentConversationId).filter((id): id is number => typeof id === 'number')
    for (let i = 1; i < ids.length; i++) {
      expect(ids[i]!, `row ${i} after conversation #${ids[i - 1]}`).toBeLessThanOrEqual(ids[i - 1]!)
    }
  })
})
