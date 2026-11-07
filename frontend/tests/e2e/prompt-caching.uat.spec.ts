import { test, expect, fakeChatTurns } from './helpers'

/**
 * UAT for the prompt-cache observability flow in the chat UI.
 *
 * Fakes the turn's start and its conversation stream in the page
 * (fakeChatTurns) with canned frames: turn 1 reports cached=0 (fresh write),
 * turn 2 reports cached=3657 (cache hit). Asserts that the usage footer renders
 * the green cached badge only on turn 2.
 *
 * What this test DOES cover: Vue usage-footer rendering, stream frame parsing,
 * the MessageUsage.cached field wiring from backend JSON → Vue state → DOM.
 *
 * What this test DOES NOT cover: the Play backend's cache_control injection or
 * the upstream OpenRouter → Anthropic round-trip. Those live below the turn's
 * frames and are validated separately by the system-prompt stability unit test
 * in AgentSystemTest and by manual UAT against a real OpenRouter API key.
 *
 * Serial mode because turn 2 depends on turn 1 being visible in the page state.
 */
test.describe.configure({ mode: 'serial' })

test.describe('prompt caching UAT', () => {
  test.beforeEach(async ({ page }) => {
    // The page sends turn 1 then turn 2 in one conversation; each plays its own frames.
    const turn = (n: number) => {
      const ackContent = `ack ${n}`
      // The chat page consumes usage by sniffing a status event whose content
      // string starts with `{` and contains `"usage"` (useChatStream).
      const usagePayload = JSON.stringify({
        usage: {
          prompt: n === 1 ? 3823 : 3859,
          completion: 7,
          total: n === 1 ? 3830 : 3866,
          reasoning: 0,
          cached: n === 1 ? 0 : 3657,
          durationMs: n === 1 ? 2000 : 1400,
        },
      })
      return [
        { type: 'init', conversationId: 990401 },
        { type: 'token', content: ackContent },
        { type: 'status', content: usagePayload },
        { type: 'complete', content: ackContent },
      ]
    }
    await fakeChatTurns(page, 990401, [turn(1), turn(2)])
    await page.goto('/chat')
    await page.waitForLoadState('domcontentloaded')
    await page.getByRole('button', { name: 'New conversation' }).click()
  })

  test('turn 1 shows no cached badge, turn 2 shows 3,657 cached', async ({ page }) => {
    const input = page.getByPlaceholder('Send a message...')

    // Turn 1 — cache WRITE. Reply renders, cached badge must not appear anywhere.
    await input.fill('turn 1')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('ack 1')).toBeVisible()
    expect(await page.getByText('3,657').count()).toBe(0)

    // Turn 2 — cache READ. Cached badge must now appear with 3,657 visible.
    await input.fill('turn 2')
    await page.getByRole('button', { name: 'Send' }).click()
    await expect(page.getByText('ack 2')).toBeVisible()
    // The cached breakdown lives in a hover popover (chat.vue:3175) gated on
    // the inline "X.X tok/s" pill. Mock yields completion=7, durationMs=1400
    // → (7/1400)*1000 = 5.0 tok/s; hover it to open the popover.
    await page.getByText('5.0 tok/s').hover()
    await expect(page.getByText('3,657')).toBeVisible()
  })
})
