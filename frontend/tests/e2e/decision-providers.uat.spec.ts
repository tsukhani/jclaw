import type { Page } from '@playwright/test'
import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT-23 — Decision Providers.
 *
 * settings.decision-providers.test.ts covers the panel's logic in jsdom, where no asset is served,
 * no media element plays and no stylesheet applies. This spec covers what only a real browser can:
 * the JEV portrait and clip are served and decode, preload="none" fetches nothing before the click,
 * the click plays the clip, and the speaker badge the CSS hides at rest appears on hover, on
 * keyboard focus and on a screen with no hover.
 *
 * Read-only: blockApiWrites answers every write, and the key editor is never opened.
 */

const PLAY = { name: 'Play JEV saying “My name is Jev”' }

async function openPanel(page: Page) {
  await gotoPage(page, '/settings?section=decision-providers')
  await expect(page.getByTestId('decision-provider-jev')).toBeVisible()
}

/** The speaker badge's computed opacity: the CSS, not a class name, decides what is visible. */
function badgeOpacity(page: Page) {
  const badge = page.getByRole('button', PLAY).locator('span[aria-hidden="true"]').first()
  return () => badge.evaluate(el => getComputedStyle(el).opacity)
}

test.describe('UAT-23 decision providers', () => {
  test('the clip and the portrait are served as media, not as the SPA fallback', async ({ request }) => {
    // An unknown path answers 200 text/html from the SPA catch-all, so the status alone proves nothing.
    for (const [path, type] of [['/jev.mp3', 'audio/'], ['/jev.webp', 'image/']] as const) {
      const res = await request.get(path)
      expect(res.status(), path).toBe(200)
      expect(res.headers()['content-type'], path).toContain(type)
    }
  })

  test('the portrait decodes and stands three text lines tall beside the note', async ({ page }) => {
    const writes = await blockApiWrites(page)
    await openPanel(page)
    const portrait = page.getByTestId('decision-jev-portrait')
    await expect.poll(() => portrait.evaluate((img: HTMLImageElement) => img.complete ? img.naturalWidth : 0)).toBeGreaterThan(0)

    const { height, line } = await page.evaluate(() => ({
      height: document.querySelector('[data-testid="decision-jev-portrait"]')!.getBoundingClientRect().height,
      line: Number.parseFloat(getComputedStyle(document.querySelector('[data-testid="decision-jev-retention"]')!).lineHeight),
    }))
    expect(Math.abs(height - 3 * line), `portrait ${height}px against a ${line}px line`).toBeLessThanOrEqual(1)
    expect(writes()).toEqual([])
  })

  test('the clip is fetched only once the portrait is clicked, and the click plays it', async ({ page }) => {
    const writes = await blockApiWrites(page)
    const fetched: string[] = []
    page.on('request', (req) => {
      if (new URL(req.url()).pathname === '/jev.mp3') fetched.push(req.url())
    })
    await openPanel(page)
    const portrait = page.getByTestId('decision-jev-portrait')
    await expect.poll(() => portrait.evaluate((img: HTMLImageElement) => img.complete)).toBe(true)
    expect(fetched, 'preload="none" should fetch nothing before the click').toEqual([])

    await page.getByRole('button', PLAY).click()
    const clip = page.locator('audio[src="/jev.mp3"]')
    await expect.poll(() => fetched.length).toBeGreaterThan(0)
    await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.error?.code ?? 0), 'media error code').toBe(0)
    await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.duration || 0)).toBeGreaterThan(0)
    await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.played.length), 'the clip never played').toBeGreaterThan(0)
    expect(writes()).toEqual([])
  })

  test('the speaker badge is hidden at rest and shown on hover and on keyboard focus', async ({ page }) => {
    // Reduced motion drops the opacity transition, so each read sees the settled value, not a frame of the fade.
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await blockApiWrites(page)
    await openPanel(page)
    const play = page.getByRole('button', PLAY)
    const opacity = badgeOpacity(page)

    await page.mouse.move(0, 0)
    await expect.poll(opacity).toBe('0')
    await play.hover()
    await expect.poll(opacity).toBe('1')
    await page.mouse.move(0, 0)
    await expect.poll(opacity).toBe('0')

    // Arrive by Tab, so :focus-visible matches for the reason a keyboard user would see it.
    await play.focus()
    await page.keyboard.press('Shift+Tab')
    await page.keyboard.press('Tab')
    await expect(play).toBeFocused()
    await expect.poll(opacity).toBe('1')
  })

  test.describe('on a screen with no hover', () => {
    test.use({ isMobile: true, hasTouch: true })

    test('the speaker badge is shown without any hover', async ({ page }) => {
      await blockApiWrites(page)
      await openPanel(page)
      // Guards the emulation itself: without it this test would pass on a hover device by never asking.
      expect(await page.evaluate(() => matchMedia('(hover: none)').matches)).toBe(true)
      await expect.poll(badgeOpacity(page)).toBe('1')
    })
  })
})
