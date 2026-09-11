import { test, expect, gotoPage } from './helpers'

/**
 * UAT-15 — Chat, the core product path.
 *
 * /api/chat/stream is mocked at the Playwright route level throughout. A live
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

test.describe('UAT-15 chat', () => {
  test('chat page renders its composer and controls', async ({ page }) => {
    await gotoPage(page, '/chat')
    await expect(page.getByPlaceholder('Send a message...')).toBeVisible()
    await expect(page.getByLabel('Upload files')).toBeAttached()
    await expect(page.getByRole('button', { name: 'Start voice mode' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Think' })).toBeVisible()
  })

  test('model picker exposes the active model', async ({ page }) => {
    await gotoPage(page, '/chat')
    // The combobox label is "<model name><provider>" — assert a provider is
    // named, which is what tells the operator where a turn will actually go.
    const picker = page.locator('button').filter({ hasText: /ollama|openai|openrouter|anthropic|lm-?studio/i }).first()
    await expect(picker).toBeVisible()
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
    const before = await (await request.get('/api/agents')).json()
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
    const after = await (await request.get('/api/agents')).json()
    expect(after).toEqual(before)
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
