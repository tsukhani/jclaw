import { test, expect } from '@playwright/test'

/**
 * UAT for the prompt-cache observability flow in the chat UI.
 *
 * Mocks /api/chat/stream at the Playwright route level and returns canned SSE
 * events: turn 1 reports cached=0 (fresh write), turn 2 reports cached=3657
 * (cache hit). Asserts that the usage footer renders the green cached badge only
 * on turn 2.
 *
 * What this test DOES cover: Vue usage-footer rendering, SSE event parsing,
 * the MessageUsage.cached field wiring from backend JSON → Vue state → DOM.
 *
 * What this test DOES NOT cover: the Play backend's cache_control injection or
 * the upstream OpenRouter → Anthropic round-trip. Those live below /api/chat/stream
 * and are validated separately by the system-prompt stability unit test in
 * AgentSystemTest and by manual UAT against a real OpenRouter API key.
 *
 * Serial mode because turn 2 depends on turn 1 being visible in the page state.
 */
test.describe.configure({ mode: 'serial' })

test.describe('prompt caching UAT', () => {
  test.beforeEach(async ({ page }) => {
    // Each test gets its own turn counter so the mock returns turn-1 then turn-2.
    let turn = 0
    await page.route('**/api/chat/stream', async route => {
      turn += 1
      const isFirstTurn = turn === 1
      const ackContent = `ack ${turn}`
      // The chat page consumes usage by sniffing a status event whose content
      // string starts with `{` and contains `"usage"`. See chat.vue ~line 419.
      const usagePayload = JSON.stringify({
        usage: {
          prompt: isFirstTurn ? 3823 : 3859,
          completion: 7,
          total: isFirstTurn ? 3830 : 3866,
          reasoning: 0,
          cached: isFirstTurn ? 0 : 3657,
          durationMs: isFirstTurn ? 2000 : 1400,
        },
      })
      const body =
        `data: ${JSON.stringify({ type: 'init', conversationId: 9000 + turn })}\n` +
        `data: ${JSON.stringify({ type: 'token', content: ackContent })}\n` +
        `data: ${JSON.stringify({ type: 'status', content: usagePayload })}\n` +
        `data: ${JSON.stringify({ type: 'complete', content: ackContent })}\n`
      await route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        headers: {
          'Cache-Control': 'no-cache',
          Connection: 'keep-alive',
        },
        body,
      })
    })
    await page.goto('/chat')
    await page.waitForLoadState('domcontentloaded')
    await page.getByRole('button', { name: 'New conversation' }).click()
  })

  test('turn 1 shows no cached badge, turn 2 shows 3,657 cached', async ({ page }) => {
    const input = page.getByPlaceholder('Type a message...')

    // Turn 1 — cache WRITE. Reply renders, cached badge must not appear anywhere.
    await input.fill('turn 1')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('ack 1')).toBeVisible()
    expect(await page.getByText('3,657').count()).toBe(0)

    // Turn 2 — cache READ. Cached badge must now appear with 3,657 visible.
    await input.fill('turn 2')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('ack 2')).toBeVisible()
    await expect(page.getByText('3,657')).toBeVisible()
  })
})
