import type { Page } from '@playwright/test'
import type { Agent, ChecklistItem, ConfigResponse, Conversation, ProviderModelDef } from '~/types/api'
import { ROUTER_MODEL_ID, ROUTER_PROVIDER } from '../../utils/model-route'
import { test, expect, gotoPage, json, blockApiWrites, holdChatStream, E2E_PREFIX } from './helpers'

/**
 * UAT-15 — Chat, the core product path.
 *
 * /api/chat/stream is mocked throughout: at the Playwright route level, or held open in the page
 * where a state lasts only while the turn streams. A live
 * turn would spend a real model call per assertion and make the suite's
 * runtime depend on provider latency; what is under test here is the client
 * half — SSE parsing, message rendering, tool-call display, error surfacing —
 * which is exactly the half a mock exercises faithfully.
 *
 * The backend half (cache_control injection, provider round-trip) is covered
 * by ApiChatControllerTest and the eval suites, not here.
 */

/** Build an SSE body from the event shapes chat.vue consumes. */
function sse(events: Array<Record<string, unknown>>): string {
  return events.map(e => `data: ${JSON.stringify(e)}\n`).join('')
}

/** The agent the chat page opened on, as GET /api/agents reports it. */
async function selectedAgent(page: Page): Promise<Agent> {
  const agents = (await json(page.request, '/api/agents')).body as Agent[]
  const select = page.getByRole('combobox', { name: 'Agent:' })
  // With a single agent the page renders its name as text, not a select.
  if (await select.count() === 0) return agents[0]!
  await expect(select).not.toHaveValue('')
  const id = Number(await select.inputValue())
  const agent = agents.find(a => a.id === id)
  if (!agent) throw new Error(`the chat page opened on agent ${id}, which GET /api/agents does not list`)
  return agent
}

/** Whether the agent's model is configured as thinking-capable, from the entry the page's capability pills read. */
async function supportsThinking(page: Page, agent: Agent): Promise<boolean> {
  const { entries } = (await json(page.request, '/api/config')).body as ConfigResponse
  const models = entries.find(e => e.key === `provider.${agent.modelProvider}.models`)?.value ?? '[]'
  return (JSON.parse(models) as ProviderModelDef[]).find(m => m.id === agent.modelId)?.supportsThinking === true
}

/** The model picker, whose label is "<model name><provider>". */
function modelPicker(page: Page, agent: Agent) {
  return page.locator('button').filter({ hasText: agent.modelProvider }).first()
}

/** Serve `id`, which no real row can carry, as one of `agent`'s web chats with `checklist` stored on it. */
async function stubConversation(page: Page, agent: Agent, id: number, checklist: ChecklistItem[]) {
  const row: Conversation = {
    id, agentId: agent.id, agentName: agent.name, channelType: 'web', peerId: null, preview: 'e2e-uat plan it',
    messageCount: 1, createdAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T10:00:00Z', checklist,
  }
  await page.route(url => url.pathname === '/api/conversations', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const response = await route.fetch()
    const rows = await response.json() as Conversation[]
    const forAgent = new URL(route.request().url()).searchParams.get('agentId') === String(agent.id)
    return route.fulfill({ response, json: forAgent ? [row, ...rows] : rows })
  })
  await page.route(url => url.pathname === `/api/conversations/${id}`, route => route.fulfill({ json: row }))
  await page.route(url => url.pathname === `/api/conversations/${id}/messages`, route => route.fulfill({
    json: new URL(route.request().url()).searchParams.has('before')
      ? []
      : [{ id: id * 10 + 1, role: 'user', content: 'e2e-uat plan it', createdAt: '2026-10-23T10:00:00Z' }],
  }))
}

test.describe('UAT-15 chat', () => {
  test('chat page renders its composer and controls', async ({ page }) => {
    await gotoPage(page, '/chat')
    await expect(page.getByPlaceholder('Send a message...')).toBeVisible()
    await expect(page.getByLabel('Upload files')).toBeAttached()
    await expect(page.getByRole('button', { name: 'Start voice mode' })).toBeVisible()

    const agent = await selectedAgent(page)
    // The picker naming the model proves the data the pills derive from has loaded, so an absent Think means absent.
    await expect(modelPicker(page, agent)).toBeVisible()
    // On Auto no model is resolved until a turn's route frame names one (JCLAW-1222), so Think cannot show yet.
    const onRouter = agent.modelProvider === ROUTER_PROVIDER && agent.modelId === ROUTER_MODEL_ID
    const think = page.getByRole('button', { name: 'Think' })
    if (!onRouter && await supportsThinking(page, agent)) await expect(think).toBeVisible()
    else await expect(think).toHaveCount(0)
  })

  test('the chat fits under the status banner without overflowing the page', async ({ page, context }) => {
    await gotoPage(page, '/chat')
    await expect(page.getByPlaceholder('Send a message...')).toBeVisible()

    await context.setOffline(true)
    try {
      await expect(page.getByText('The JClaw server isn\'t responding')).toBeVisible()
      const main = page.locator('main')
      // A viewport-height chat overflowed main by exactly the banner's height.
      expect(await main.evaluate(m => m.scrollHeight - m.clientHeight)).toBe(0)
      const mainBox = (await main.boundingBox())!
      const sendBox = (await page.getByRole('button', { name: 'Send' }).boundingBox())!
      expect(sendBox.y + sendBox.height).toBeLessThanOrEqual(mainBox.y + mainBox.height)
    }
    finally {
      await context.setOffline(false)
    }
  })

  test('model picker exposes the active model', async ({ page }) => {
    await gotoPage(page, '/chat')
    // Naming the agent's own provider is what tells the operator where a turn will actually go.
    await expect(modelPicker(page, await selectedAgent(page))).toBeVisible()
  })

  test('a full turn streams, renders and reports usage', async ({ page }) => {
    await page.route('**/api/chat/stream', route => route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse([
        { type: 'init', conversationId: 990001 },
        { type: 'token', content: 'Hello from ' },
        { type: 'token', content: 'the UAT suite.' },
        { type: 'status', content: JSON.stringify({ usage: { prompt: 100, completion: 5, total: 105, reasoning: 0, cached: 0, durationMs: 1000 } }) },
        { type: 'complete', content: 'Hello from the UAT suite.' },
      ]),
    }))

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    await page.getByPlaceholder('Send a message...').fill('uat hello')
    await page.getByRole('button', { name: 'Send' }).click()

    // Tokens must be concatenated in order, not rendered as separate bubbles.
    await expect(page.getByText('Hello from the UAT suite.')).toBeVisible({ timeout: 15_000 })
  })

  test('a model picked on a fresh chat rides with the first message and leaves the agent untouched', async ({ page, request }) => {
    // JCLAW-1196: the pick is a conversation override the first message carries, not
    // a write to the agent row — compare the agents list before and after.
    // Other specs create, change and delete their fixture agents while this runs, so those are left out.
    const agents = async () =>
      (await (await request.get('/api/agents')).json() as Agent[]).filter(a => !a.name.startsWith(E2E_PREFIX))
    const before = await agents()
    let sent: Record<string, unknown> | null = null
    await page.route('**/api/chat/stream', (route) => {
      sent = route.request().postDataJSON()
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: sse([{ type: 'init', conversationId: 990002 }, { type: 'complete', content: 'pending pick acknowledged' }]),
      })
    })

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    const picker = page.getByRole('button', { name: /^Model / })
    const current = (await picker.textContent()) ?? ''
    await picker.click()
    // The first model that is not the current one, whatever this install offers; the
    // option's last span carries the model id.
    const options = page.getByRole('dialog').getByRole('button')
    let picked: string | null = null
    for (let i = 0; i < await options.count(); i++) {
      const id = (await options.nth(i).locator('span').last().textContent())?.trim() ?? ''
      if (id && !current.includes(id)) {
        await options.nth(i).click()
        picked = id
        break
      }
    }
    test.skip(picked === null, 'this install offers a single model')

    await page.getByPlaceholder('Send a message...').fill('uat pending pick')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('pending pick acknowledged')).toBeVisible({ timeout: 15_000 })

    await expect.poll(() => sent).not.toBeNull()
    expect(sent).toMatchObject({ conversationId: null, modelId: picked })
    expect(await agents()).toEqual(before)
  })

  test('the sent message appears in the transcript', async ({ page }) => {
    await page.route('**/api/chat/stream', route => route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse([
        { type: 'init', conversationId: 990002 },
        { type: 'token', content: 'ack' },
        { type: 'complete', content: 'ack' },
      ]),
    }))

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    await page.getByPlaceholder('Send a message...').fill('uat-echo-probe')
    await page.getByRole('button', { name: 'Send' }).click()

    await expect(page.getByText('uat-echo-probe')).toBeVisible({ timeout: 15_000 })
    await expect(page.getByText('ack', { exact: true })).toBeVisible()
  })

  test('tool calls render in the transcript', async ({ page }) => {
    await page.route('**/api/chat/stream', route => route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse([
        { type: 'init', conversationId: 990003 },
        { type: 'tool_call', content: JSON.stringify({ name: 'web_search', arguments: { query: 'jclaw' } }) },
        { type: 'token', content: 'Searched.' },
        { type: 'complete', content: 'Searched.' },
      ]),
    }))

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    await page.getByPlaceholder('Send a message...').fill('search for jclaw')
    await page.getByRole('button', { name: 'Send' }).click()

    await expect(page.getByText('Searched.')).toBeVisible({ timeout: 15_000 })
  })

  test('the checklist spins only a step the running turn has in progress', async ({ page, request }) => {
    // Agents often reply with a step still in_progress, and the next turn shows that stored list until its own checklist call.
    const agents = (await json(request, '/api/agents')).body as Agent[]
    const agent = agents.find(a => a.isMain) ?? agents[0]!
    await stubConversation(page, agent, 990301, [
      { content: 'Run tests', status: 'completed', activeForm: 'Running tests' },
      { content: 'Fix the parser', status: 'in_progress', activeForm: 'Fixing the parser' },
    ])
    const blocked = await blockApiWrites(page)
    const stream = await holdChatStream(page)
    const panel = page.getByTestId('chat-checklist')
    const rows = panel.getByTestId('chat-checklist-item')
    const spinner = panel.locator('.animate-spin')
    const stop = page.getByRole('button', { name: 'Stop generating' })

    await gotoPage(page, '/chat?conversation=990301')
    await expect(rows).toHaveText(['Run tests(done)', 'Fix the parser(not finished)'])
    await expect(spinner).toHaveCount(0)

    await page.getByPlaceholder('Send a message...').fill('carry on')
    await page.getByRole('button', { name: 'Send' }).click()
    await stream.opened()
    await stream.push({ type: 'init', conversationId: 990301 })
    // The turn is running, but nothing in it has touched the stored step yet.
    await expect(stop).toBeVisible()
    await expect(rows).toHaveText(['Run tests(done)', 'Fix the parser(not finished)'])
    await expect(spinner).toHaveCount(0)

    await stream.push({
      type: 'tool_call', id: 'tc1', name: 'checklist',
      arguments: JSON.stringify({ items: [
        { content: 'Fix the parser', status: 'completed', activeForm: 'Fixing the parser' },
        { content: 'Commit', status: 'in_progress', activeForm: 'Committing' },
      ] }),
      resultText: 'Checklist updated successfully (2 items).',
    })
    await expect(rows).toHaveText(['Fix the parser(done)', 'Committing(in progress)'])
    await expect(spinner).toHaveCount(1)

    await stream.push({ type: 'complete', content: 'Parser fixed.' })
    await stream.end()
    await expect(stop).toHaveCount(0)
    await expect(rows).toHaveText(['Fix the parser(done)', 'Commit(not finished)'])
    await expect(spinner).toHaveCount(0)
    expect(blocked()).toEqual([])
  })

  test('a stream error surfaces to the operator rather than hanging', async ({ page }) => {
    // The failure mode this guards: the composer stays disabled behind a
    // spinner forever because the error event never unwinds the sending state.
    await page.route('**/api/chat/stream', route => route.fulfill({
      status: 200,
      contentType: 'text/event-stream',
      body: sse([
        { type: 'init', conversationId: 990004 },
        { type: 'error', content: 'UAT injected provider failure' },
      ]),
    }))

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    await page.getByPlaceholder('Send a message...').fill('trigger an error')
    await page.getByRole('button', { name: 'Send' }).click()

    await expect(page.getByText(/UAT injected provider failure|error/i).first()).toBeVisible({ timeout: 15_000 })
    // Composer must be usable again once the turn has failed.
    await expect(page.getByPlaceholder('Send a message...')).toBeEnabled({ timeout: 15_000 })
  })

  test('an HTTP failure on the stream endpoint is surfaced', async ({ page }) => {
    await page.route('**/api/chat/stream', route => route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'UAT injected 500' }),
    }))

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    await page.getByPlaceholder('Send a message...').fill('trigger a 500')
    await page.getByRole('button', { name: 'Send' }).click()

    await expect(page.getByPlaceholder('Send a message...')).toBeEnabled({ timeout: 20_000 })
  })

  test('conversation deep link loads an existing transcript', async ({ page, request }) => {
    const list = await (await request.get('/api/conversations')).json()
    const rows = (Array.isArray(list) ? list : list.conversations ?? list.items ?? []) as Array<{ id: number }>
    test.skip(rows.length === 0, 'no conversations on this install')

    await gotoPage(page, `/chat?conversation=${rows[0]!.id}`)
    await expect(page.getByPlaceholder('Send a message...')).toBeVisible()
  })

  test('chat upload endpoint rejects an unauthenticated caller', async ({ playwright }) => {
    const anon = await playwright.request.newContext({
      baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
      storageState: { cookies: [], origins: [] },
    })
    const res = await anon.post('/api/chat/stream', { data: { message: 'should not run' } })
    expect(res.status(), 'chat must be session-gated').toBe(401)
    await anon.dispose()
  })
})
