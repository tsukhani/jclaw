import type { Page } from '@playwright/test'
import { DRIVER_LABELS, browserSetupNeeded, chromiumLabel, type DriverSource } from '../../utils/browser-setup'
import { test, expect, gotoPage, json, blockApiWrites } from './helpers'

/**
 * The browser tool's first-use setup: Settings > Browser's Browser components section and the
 * chat progress bar.
 *
 * A real setup downloads ~300 MB into the instance under test, so only the first test reads live
 * state, and it never clicks Download now. The download flow and the chat bar run against
 * /api/browser/setup answered inside the browser, and blockApiWrites stops any other write.
 */

interface Setup {
  active: boolean
  step: string | null
  percent: number | null
  error: string | null
  driverSource: DriverSource
  platform: string | null
  nodeVersion: string
  chromiumInstalled: boolean
}

function setup(o: Partial<Setup> = {}): Setup {
  return {
    active: false, step: null, percent: null, error: null,
    driverSource: 'missing', platform: 'linux-arm64', nodeVersion: '24.21.0', chromiumInstalled: false, ...o,
  }
}

/**
 * Answer /api/browser/setup with whatever `current` holds at the time of each request. Register it
 * after blockApiWrites: Playwright consults the last route first, so this answers the setup POST
 * locally and every other write still falls through to the blocker.
 */
async function serveSetup(page: Page, current: () => Setup, onPost?: () => Setup) {
  await page.route('**/api/browser/setup', (route) => {
    if (route.request().method() === 'POST') {
      return route.fulfill({ json: onPost ? onPost() : current() })
    }
    return route.fulfill({ json: current() })
  })
}

test.describe('UAT-22 browser setup', () => {
  test('Browser components shows what this instance really has, and offers a download only if needed', async ({ page, request }) => {
    const { status, body } = await json(request, '/api/browser/setup')
    expect(status).toBe(200)
    const live = body as Setup

    await gotoPage(page, '/settings?section=browser')
    await expect(page.getByRole('heading', { name: 'Browser components' })).toBeVisible()
    await expect(page.getByTestId('browser-driver-state')).toHaveText(DRIVER_LABELS[live.driverSource])
    await expect(page.getByTestId('browser-chromium-state')).toHaveText(chromiumLabel(live.chromiumInstalled))
    // Read, never clicked: on a live instance it starts a real download.
    await expect(page.getByTestId('browser-setup-download')).toHaveCount(browserSetupNeeded(live) && !live.active ? 1 : 0)
  })

  test('Download now shows each step\'s progress, then what it installed', async ({ page }) => {
    const blocked = await blockApiWrites(page)
    let phase = 0
    const phases = [
      setup(),
      setup({ active: true, step: 'Downloading the browser driver (Node.js 24.21.0)', percent: 40 }),
      setup({ active: true, driverSource: 'downloaded', step: 'Downloading Chrome for Testing 153.0.8010.12', percent: 70 }),
      setup({ driverSource: 'downloaded', chromiumInstalled: true }),
    ]
    let posts = 0
    await serveSetup(page, () => phases[phase]!, () => {
      posts++
      phase = 1
      return phases[1]!
    })

    await gotoPage(page, '/settings?section=browser')
    await expect(page.getByTestId('browser-driver-state')).toHaveText(DRIVER_LABELS.missing)
    await page.getByTestId('browser-setup-download').click()

    const progress = page.getByTestId('browser-setup-progress')
    await expect(progress).toContainText('Downloading the browser driver (Node.js 24.21.0)')
    await expect(progress).toContainText('40%')
    await expect(page.getByTestId('browser-setup-download')).toHaveCount(0)

    phase = 2
    await expect(progress).toContainText('Downloading Chrome for Testing 153.0.8010.12')
    await expect(progress.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '70')
    await expect(page.getByTestId('browser-driver-state')).toHaveText(DRIVER_LABELS.downloaded)

    phase = 3
    await expect(page.getByTestId('browser-chromium-state')).toHaveText(chromiumLabel(true))
    await expect(progress).toHaveCount(0)

    expect(posts).toBe(1)
    expect(blocked()).toEqual([])
  })

  test('the chat shows the download under the reply whose browser call is waiting on it', async ({ page }) => {
    // A stream the test holds open, since the bar lives only while the turn streams. Installed
    // before the page's scripts, so every fetch of /api/chat/stream gets it; the rest pass through.
    await page.addInitScript(() => {
      const realFetch = window.fetch.bind(window)
      const encoder = new TextEncoder()
      let controller: ReadableStreamDefaultController<Uint8Array> | null = null
      ;(window as unknown as { e2eStream: object }).e2eStream = {
        open: () => controller !== null,
        push: (event: object) => controller?.enqueue(encoder.encode(`data: ${JSON.stringify(event)}\n`)),
        end: () => {
          controller?.close()
          controller = null
        },
      }
      window.fetch = (input, init) => {
        const url = input instanceof Request ? input.url : String(input)
        if (!url.includes('/api/chat/stream')) return realFetch(input, init)
        const body = new ReadableStream<Uint8Array>({
          start(c) {
            controller = c
          },
        })
        return Promise.resolve(new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } }))
      }
    })
    type Stream = { e2eStream: { open: () => boolean, push: (e: object) => void, end: () => void } }

    const blocked = await blockApiWrites(page)
    let current = setup({ active: true, driverSource: 'downloaded', step: 'Downloading Chrome for Testing 153.0.8010.12', percent: 35 })
    await serveSetup(page, () => current)

    await gotoPage(page, '/chat')
    await page.getByRole('button', { name: 'New conversation' }).click()
    await page.getByPlaceholder('Send a message...').fill('open example.com')
    await page.getByRole('button', { name: 'Send' }).click()
    await page.waitForFunction(() => (window as unknown as Stream).e2eStream.open())

    await page.evaluate(() => {
      const s = (window as unknown as Stream).e2eStream
      s.push({ type: 'init', conversationId: 990201 })
      // What the backend sends just before the browser call runs; the tool_call frame comes after.
      s.push({ type: 'status', content: 'Using tool: browser' })
    })

    const bar = page.getByTestId('browser-setup-progress')
    await expect(bar).toBeVisible({ timeout: 10_000 })
    await expect(bar).toContainText('Downloading Chrome for Testing 153.0.8010.12')
    await expect(bar).toContainText('35%')
    await expect(bar.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '35')

    // The setup finishing takes the bar away while the turn is still streaming.
    current = setup({ driverSource: 'downloaded', chromiumInstalled: true })
    await expect(bar).toHaveCount(0, { timeout: 10_000 })

    await page.evaluate(() => {
      const s = (window as unknown as Stream).e2eStream
      s.push({ type: 'complete', content: 'Opened.' })
      s.end()
    })
    await expect(page.getByText('Opened.')).toBeVisible()
    expect(blocked()).toEqual([])
  })
})
