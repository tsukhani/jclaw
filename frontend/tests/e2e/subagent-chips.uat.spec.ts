import type { APIRequestContext, Page, Route } from '@playwright/test'
import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT-18 — Chat subagent chips (JCLAW-1205..1207).
 *
 * Runs against the operator's live instance, so the parent conversation, its runs and every
 * child transcript are stubbed at the route level under 990xxx ids no real row can carry.
 */

type Status = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'KILLED' | 'TIMEOUT'

interface StubRun {
  id: number
  label: string | null
  childAgentName: string
  childConversationId: number
  status: Status
}

interface StubMessage {
  id: number
  role: 'user' | 'assistant'
  content: string
  createdAt: string
}

interface ChatStub {
  parentId: number
  runs: StubRun[]
  transcripts: Map<number, StubMessage[]>
  /** X-Total-Count for the newest-window request; defaults to the number of runs. */
  totalRuns?: number
}

function message(id: number, role: StubMessage['role'], content: string): StubMessage {
  return { id, role, content, createdAt: '2026-09-01T10:00:00Z' }
}

function fulfillJson(route: Route, body: unknown, headers: Record<string, string> = {}) {
  return route.fulfill({ status: 200, contentType: 'application/json', headers, body: JSON.stringify(body) })
}

// A stubbed parent owned by a real top-level agent opens normally, not as a read-only subagent transcript.
async function topLevelAgent(request: APIRequestContext): Promise<{ id: number, name: string }> {
  const res = await request.get('/api/agents')
  expect(res.ok(), 'GET /api/agents').toBeTruthy()
  const agents = await res.json() as Array<{ id: number, name: string, isMain: boolean }>
  const agent = agents.find(a => a.isMain) ?? agents[0]
  if (!agent) throw new Error('no top-level agent on this install')
  return agent
}

/** Installs the stubs and the write guard; returns the requests no stub recognised and the writes it blocked. */
async function stubChat(page: Page, request: APIRequestContext, stub: ChatStub) {
  const agent = await topLevelAgent(request)
  const unexpected: string[] = []
  const parentMessages = [
    message(stub.parentId * 10 + 1, 'user', 'e2e-uat parent prompt'),
    message(stub.parentId * 10 + 2, 'assistant', 'e2e-uat parent reply'),
  ]

  await page.route(url => url.pathname === '/api/subagent-runs', (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const query = new URL(route.request().url()).searchParams
    if (query.get('parentConversationId') !== String(stub.parentId)) {
      unexpected.push(route.request().url())
      return fulfillJson(route, [], { 'X-Total-Count': '0' })
    }
    const status = query.get('status')
    const matching = stub.runs.filter(r => !status || r.status === status)
    const rows = [...matching].sort((a, b) => b.id - a.id).slice(0, Number(query.get('limit') ?? 100))
    const total = status ? matching.length : (stub.totalRuns ?? stub.runs.length)
    return fulfillJson(route, rows.map(r => ({
      id: r.id,
      parentAgentId: agent.id,
      parentAgentName: agent.name,
      childAgentId: r.id + 1000,
      childAgentName: r.childAgentName,
      parentConversationId: stub.parentId,
      childConversationId: r.childConversationId,
      label: r.label,
      mode: 'session',
      status: r.status,
      startedAt: '2026-09-01T10:00:00Z',
      endedAt: r.status === 'RUNNING' ? null : '2026-09-01T10:01:00Z',
      outcome: null,
      workdir: null,
    })), { 'X-Total-Count': String(total) })
  })

  await page.route(url => /^\/api\/conversations\/990\d{3}(\/|$)/.test(url.pathname), (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const url = new URL(route.request().url())
    const [, rawId, suffix] = url.pathname.match(/^\/api\/conversations\/(\d+)(\/messages)?$/) ?? []
    const id = Number(rawId)
    if (id === stub.parentId && !suffix) {
      return fulfillJson(route, {
        id,
        agentId: agent.id,
        agentName: agent.name,
        channelType: 'web',
        preview: 'e2e-uat parent prompt',
        peerId: null,
        messageCount: parentMessages.length,
        createdAt: '2026-09-01T10:00:00Z',
        updatedAt: '2026-09-01T10:00:00Z',
        parentConversationId: null,
      })
    }
    const rows = suffix ? (id === stub.parentId ? parentMessages : stub.transcripts.get(id)) : undefined
    if (rows) {
      const offset = Number(url.searchParams.get('offset') ?? 0)
      const limit = Number(url.searchParams.get('limit') ?? rows.length)
      return fulfillJson(route, rows.slice(offset, offset + limit))
    }
    unexpected.push(`${url.pathname}${url.search}`)
    return route.fulfill({ status: 404, contentType: 'application/json', body: '{"error":"not stubbed"}' })
  })

  const writes = await blockApiWrites(page)
  return { unexpected: () => unexpected, writes }
}

function chip(page: Page, text: string) {
  return page.getByTestId('subagent-chip').filter({ hasText: text })
}

async function markPage(page: Page) {
  await page.evaluate(() => {
    (window as unknown as { e2eNoReload?: boolean }).e2eNoReload = true
  })
}

async function expectNotReloaded(page: Page) {
  expect(await page.evaluate(() => (window as unknown as { e2eNoReload?: boolean }).e2eNoReload)).toBe(true)
}

test.describe('UAT-18 chat subagent chips', () => {
  test('each run shows its status word, and only the running chip spins', async ({ page, request }) => {
    const parentId = 990100
    const guard = await stubChat(page, request, {
      parentId,
      runs: [
        { id: 990501, label: 'e2e-uat research', childAgentName: 'e2e-uat-child-running', childConversationId: 990101, status: 'RUNNING' },
        { id: 990502, label: null, childAgentName: 'e2e-uat-child-completed', childConversationId: 990102, status: 'COMPLETED' },
        { id: 990503, label: null, childAgentName: 'e2e-uat-child-failed', childConversationId: 990103, status: 'FAILED' },
        { id: 990504, label: null, childAgentName: 'e2e-uat-child-killed', childConversationId: 990104, status: 'KILLED' },
        { id: 990505, label: null, childAgentName: 'e2e-uat-child-timeout', childConversationId: 990105, status: 'TIMEOUT' },
        { id: 990506, label: null, childAgentName: 'e2e-uat-child-inline', childConversationId: parentId, status: 'COMPLETED' },
      ],
      transcripts: new Map(),
    })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    await expect(page.getByTestId('subagent-chip')).toHaveCount(5)

    const expected: Array<[string, string, string]> = [
      ['e2e-uat research', 'Running', 'spin'],
      ['e2e-uat-child-completed', 'Completed', 'none'],
      ['e2e-uat-child-failed', 'Failed', 'none'],
      ['e2e-uat-child-killed', 'Killed', 'none'],
      ['e2e-uat-child-timeout', 'Timed out', 'none'],
    ]
    for (const [name, word, animation] of expected) {
      const row = chip(page, name)
      await expect(row.getByTestId('subagent-chip-status')).toHaveText(word)
      await expect(row.getByTestId('subagent-chip-icon')).toHaveCSS('animation-name', animation)
    }

    const labelled = chip(page, 'e2e-uat research').getByTestId('subagent-chip-label')
    await expect(labelled).toHaveText('e2e-uat research')
    await expect(labelled).toHaveAttribute('title', 'e2e-uat research · e2e-uat-child-running')
    const unlabelled = chip(page, 'e2e-uat-child-completed').getByTestId('subagent-chip-label')
    await expect(unlabelled).toHaveText('e2e-uat-child-completed')
    await expect(unlabelled).toHaveAttribute('title', 'e2e-uat-child-completed')

    await expect(page.getByTestId('subagent-stack')).not.toContainText('e2e-uat-child-inline')
    // The inline run gets no chip but is still one of the runs the header counts.
    await expect(page.getByTestId('subagent-stack-count')).toHaveText('6 subagents · 1 running')
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })

  test('expanding a chip shows its read-only transcript, and collapsing hides it', async ({ page, request }) => {
    const parentId = 990110
    const childId = 990111
    const guard = await stubChat(page, request, {
      parentId,
      runs: [{ id: 990511, label: 'e2e-uat summarise', childAgentName: 'e2e-uat-child-expand', childConversationId: childId, status: 'COMPLETED' }],
      transcripts: new Map([[childId, [
        message(990911, 'user', 'e2e-uat child task'),
        message(990912, 'assistant', 'e2e-uat child answer'),
      ]]]),
    })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    const expand = page.getByRole('button', { name: 'Expand e2e-uat summarise (e2e-uat-child-expand)' })
    await expect(expand).toHaveAttribute('aria-expanded', 'false')
    await expand.click()

    const panel = page.getByTestId('subagent-transcript-panel')
    await expect(panel.getByText('e2e-uat child task')).toBeVisible()
    await expect(panel.getByText('e2e-uat child answer')).toBeVisible()
    await expect(panel.getByRole('link', { name: 'Open full transcript' })).toHaveAttribute('href', `/chat?conversation=${childId}`)

    const collapse = page.getByRole('button', { name: 'Collapse e2e-uat summarise (e2e-uat-child-expand)' })
    await expect(collapse).toHaveAttribute('aria-expanded', 'true')
    await collapse.click()
    await expect(panel).toHaveCount(0)
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })

  test('an expanded running chip shows a new transcript message without a reload', async ({ page, request }) => {
    const parentId = 990120
    const childId = 990121
    const transcript = [
      message(990921, 'user', 'e2e-uat live task'),
      message(990922, 'assistant', 'e2e-uat live step one'),
    ]
    const guard = await stubChat(page, request, {
      parentId,
      runs: [{ id: 990521, label: null, childAgentName: 'e2e-uat-child-live', childConversationId: childId, status: 'RUNNING' }],
      transcripts: new Map([[childId, transcript]]),
    })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    await page.getByRole('button', { name: 'Expand e2e-uat-child-live' }).click()
    const panel = page.getByTestId('subagent-transcript-panel')
    await expect(panel.getByText('e2e-uat live step one')).toBeVisible()

    await markPage(page)
    transcript.push(message(990923, 'assistant', 'e2e-uat live step two'))
    await expect(panel.getByText('e2e-uat live step two')).toBeVisible({ timeout: 15_000 })
    await expectNotReloaded(page)
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })

  test('a chip follows its run from running to completed without a reload', async ({ page, request }) => {
    const parentId = 990130
    const run: StubRun = { id: 990531, label: null, childAgentName: 'e2e-uat-child-flip', childConversationId: 990131, status: 'RUNNING' }
    const guard = await stubChat(page, request, { parentId, runs: [run], transcripts: new Map() })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    const row = chip(page, 'e2e-uat-child-flip')
    await expect(row.getByTestId('subagent-chip-status')).toHaveText('Running')

    await markPage(page)
    run.status = 'COMPLETED'
    await expect(row.getByTestId('subagent-chip-status')).toHaveText('Completed', { timeout: 15_000 })
    await expect(row.getByTestId('subagent-chip-icon')).toHaveCSS('animation-name', 'none')
    await expectNotReloaded(page)
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })

  test('a closed chip comes back when the conversation loads again', async ({ page, request }) => {
    const parentId = 990140
    const guard = await stubChat(page, request, {
      parentId,
      runs: [
        { id: 990541, label: 'e2e-uat close me', childAgentName: 'e2e-uat-child-close', childConversationId: 990141, status: 'COMPLETED' },
        { id: 990542, label: null, childAgentName: 'e2e-uat-child-stay', childConversationId: 990142, status: 'FAILED' },
      ],
      transcripts: new Map(),
    })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    await expect(page.getByTestId('subagent-chip')).toHaveCount(2)
    await page.getByRole('button', { name: 'Dismiss e2e-uat close me' }).click()
    await expect(chip(page, 'e2e-uat close me')).toHaveCount(0)
    await expect(chip(page, 'e2e-uat-child-stay')).toBeVisible()

    await page.reload()
    await expect(page.locator('main')).toBeVisible()
    await expect(chip(page, 'e2e-uat close me')).toBeVisible()
    await expect(page.getByTestId('subagent-chip')).toHaveCount(2)
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })

  test('the header counts every run the conversation spawned and links to them on the Subagents page', async ({ page, request }) => {
    const parentId = 990150
    const guard = await stubChat(page, request, {
      parentId,
      runs: [
        { id: 990551, label: null, childAgentName: 'e2e-uat-child-window-a', childConversationId: 990151, status: 'COMPLETED' },
        { id: 990552, label: null, childAgentName: 'e2e-uat-child-window-b', childConversationId: 990152, status: 'COMPLETED' },
      ],
      transcripts: new Map(),
      totalRuns: 250,
    })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    await expect(page.getByTestId('subagent-chip')).toHaveCount(2)
    await expect(page.getByTestId('subagent-stack-count')).toHaveText('250 subagents')
    await expect(page.getByRole('link', { name: 'View all →' }))
      .toHaveAttribute('href', `/subagents?parentConversationId=${parentId}`)
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })

  test('one transcript opens at a time, and the header collapses the whole list', async ({ page, request }) => {
    const parentId = 990160
    const guard = await stubChat(page, request, {
      parentId,
      runs: [
        { id: 990561, label: 'e2e-uat first', childAgentName: 'e2e-uat-child-first', childConversationId: 990161, status: 'COMPLETED' },
        { id: 990562, label: 'e2e-uat second', childAgentName: 'e2e-uat-child-second', childConversationId: 990162, status: 'COMPLETED' },
      ],
      transcripts: new Map([
        [990161, [message(990961, 'assistant', 'e2e-uat first answer')]],
        [990162, [message(990962, 'assistant', 'e2e-uat second answer')]],
      ]),
    })

    await gotoPage(page, `/chat?conversation=${parentId}`)
    await page.getByRole('button', { name: 'Expand e2e-uat first (e2e-uat-child-first)' }).click()
    await expect(page.getByText('e2e-uat first answer')).toBeVisible()
    await page.getByRole('button', { name: 'Expand e2e-uat second (e2e-uat-child-second)' }).click()
    await expect(page.getByText('e2e-uat second answer')).toBeVisible()
    await expect(page.getByTestId('subagent-transcript-panel')).toHaveCount(1)
    await expect(page.getByRole('button', { name: 'Expand e2e-uat first (e2e-uat-child-first)' }))
      .toHaveAttribute('aria-expanded', 'false')

    const listToggle = page.getByTestId('subagent-stack-toggle')
    await expect(listToggle).toHaveAttribute('aria-expanded', 'true')
    await listToggle.click()
    await expect(page.getByTestId('subagent-chip')).toHaveCount(0)
    await expect(page.getByTestId('subagent-stack-count')).toHaveText('2 subagents')
    await expect(listToggle).toHaveAttribute('aria-expanded', 'false')
    await listToggle.click()
    await expect(page.getByTestId('subagent-chip')).toHaveCount(2)
    expect(guard.unexpected()).toEqual([])
    expect(guard.writes()).toEqual([])
  })
})
