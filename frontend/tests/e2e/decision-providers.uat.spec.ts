import type { Page } from '@playwright/test'
import type { ConfigResponse, OllamaDecisionStatus } from '~/types/api'
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
 * Read-only: blockApiWrites answers every write, and the key editor is never opened. The Ollama
 * card's reads are stubbed, so no test depends on a real Ollama server or on the instance's settings.
 */

const PLAY = { name: 'Play JEV saying “My name is Jev”' }
const OLLAMA_PLAY = { name: 'Play a llama’s call' }

async function openPanel(page: Page) {
  await gotoPage(page, '/settings?section=decision-providers')
  await expect(page.getByTestId('decision-provider-jev')).toBeVisible()
}

/** The speaker badge's computed opacity: the CSS, not a class name, decides what is visible. */
function badgeOpacity(page: Page, play = PLAY) {
  const badge = page.getByRole('button', play).locator('span[aria-hidden="true"]').first()
  return () => badge.evaluate(el => getComputedStyle(el).opacity)
}

const DECISION_MODELS = ['tev1:latest', 'nimble:latest']

const REACHABLE: OllamaDecisionStatus = {
  baseUrl: 'http://localhost:11434', customized: false, reachable: true, error: null, models: DECISION_MODELS,
}

async function stubOllama(page: Page, status: OllamaDecisionStatus) {
  await page.route(url => url.pathname === '/api/decision/ollama', route => route.fulfill({ json: status }))
}

/** Answer GET /api/config with the real entries, the keys the Ollama card reads replaced by `overrides`. */
async function stubConfig(page: Page, overrides: Record<string, string> = {}) {
  const replaced = new Set(['decision.ollama.models', 'router.classifier.provider', 'router.classifier.model', ...Object.keys(overrides)])
  await page.route(url => url.pathname === '/api/config', async (route) => {
    if (route.request().method() !== 'GET') return route.fallback()
    const response = await route.fetch()
    const config = await response.json() as ConfigResponse
    config.entries = [
      ...config.entries.filter(e => !replaced.has(e.key)),
      ...Object.entries(overrides).map(([key, value]) => ({ key, value })),
    ]
    return route.fulfill({ response, json: config })
  })
}

/** The bodies of the POST /api/config writes blockApiWrites answers. */
function recordConfigPosts(page: Page) {
  const posts: unknown[] = []
  page.on('request', (req) => {
    if (req.method() === 'POST' && new URL(req.url()).pathname === '/api/config') posts.push(req.postDataJSON())
  })
  return () => posts
}

test.describe('UAT-23 decision providers', () => {
  test('the clip and the portrait are served as media, not as the SPA fallback', async ({ request }) => {
    // An unknown path answers 200 text/html from the SPA catch-all, so the status alone proves nothing.
    for (const [path, type] of [['/jev.mp3', 'audio/'], ['/jev.webp', 'image/'], ['/ollama.mp3', 'audio/']] as const) {
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
      await expect.poll(badgeOpacity(page, OLLAMA_PLAY)).toBe('1')
    })
  })

  test.describe('the Ollama card', () => {
    // The panel re-reads the config after a write, so a stubConfig route.fetch() can still be in flight at teardown.
    test.afterEach(({ page }) => page.unrouteAll({ behavior: 'ignoreErrors' }))

    test('renders below the JEV card', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      await stubConfig(page)
      const writes = await blockApiWrites(page)
      await openPanel(page)
      const ollama = page.getByTestId('decision-provider-ollama')
      await expect(ollama).toBeVisible()
      const [jevBox, ollamaBox] = await Promise.all([page.getByTestId('decision-provider-jev').boundingBox(), ollama.boundingBox()])
      expect(ollamaBox!.y).toBeGreaterThanOrEqual(jevBox!.y + jevBox!.height)
      expect(writes()).toEqual([])
    })

    test('a reachable server lists exactly its decision models', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      await stubConfig(page)
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-ollama-status')).toHaveText('reachable')
      const models = page.getByTestId('decision-ollama-models')
      await expect(models.locator('label')).toHaveText(DECISION_MODELS)
      await expect(page.getByTestId('decision-ollama-error')).toHaveCount(0)
      expect(writes()).toEqual([])
    })

    test('an unreachable server says so and shows the error', async ({ page }) => {
      await stubOllama(page, {
        baseUrl: 'http://192.168.1.20:11434', customized: true, reachable: false,
        error: 'not reachable (ConnectException)', models: [],
      })
      await stubConfig(page)
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-ollama-status')).toHaveText('not reachable')
      await expect(page.getByTestId('decision-ollama-error')).toHaveText('not reachable (ConnectException)')
      await expect(page.getByTestId('decision-ollama-models').locator('label')).toHaveCount(0)
      expect(writes()).toEqual([])
    })

    test('selecting a model sends one write that stores it in decision.ollama.models', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      await stubConfig(page)
      const writes = await blockApiWrites(page)
      const posts = recordConfigPosts(page)
      await openPanel(page)
      // click(), not check(): the stubbed re-read after the write unticks the box, which check() reports as a failure.
      await page.getByTestId('decision-ollama-model-tev1:latest').getByRole('checkbox').click()

      await expect.poll(writes).toEqual(['POST /api/config'])
      expect(posts()).toHaveLength(1)
      const { key, value } = posts()[0] as { key: string, value: string }
      expect(key).toBe('decision.ollama.models')
      expect(JSON.parse(value)).toEqual(['tev1:latest'])
    })

    test('the router classifier on an Ollama model reads in use and names the model', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      await stubConfig(page, { 'router.classifier.provider': 'ollama-decision', 'router.classifier.model': 'tev1:latest' })
      const writes = await blockApiWrites(page)
      await openPanel(page)
      const router = page.getByTestId('decision-ollama-consumer-model-router')
      await expect(router).toContainText('(tev1:latest)')
      await expect(router).toContainText('in use')
      await expect(router).not.toContainText('not in use')
      expect(writes()).toEqual([])
    })

    test('the router classifier on JEV leaves the Ollama card not in use', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      await stubConfig(page, { 'router.classifier.provider': 'jev', 'router.classifier.model': 'tev1:latest' })
      await blockApiWrites(page)
      await openPanel(page)
      const router = page.getByTestId('decision-ollama-consumer-model-router')
      await expect(router).toContainText('not in use')
      await expect(router).not.toContainText('(tev1:latest)')
    })

    test('the llama clip is fetched only once the logo is clicked, and the click plays it', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      const writes = await blockApiWrites(page)
      const fetched: string[] = []
      page.on('request', (req) => {
        if (new URL(req.url()).pathname === '/ollama.mp3') fetched.push(req.url())
      })
      await openPanel(page)
      await expect(page.getByTestId('decision-ollama-logo')).toBeVisible()
      expect(fetched, 'preload="none" should fetch nothing before the click').toEqual([])

      await page.getByRole('button', OLLAMA_PLAY).click()
      const clip = page.locator('audio[src="/ollama.mp3"]')
      await expect.poll(() => fetched.length).toBeGreaterThan(0)
      await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.error?.code ?? 0), 'media error code').toBe(0)
      await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.duration || 0)).toBeGreaterThan(0)
      await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.played.length), 'the clip never played').toBeGreaterThan(0)
      expect(writes()).toEqual([])
    })

    test('the speaker badge is hidden at rest and shown on hover and on keyboard focus', async ({ page }) => {
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await stubOllama(page, REACHABLE)
      await blockApiWrites(page)
      await openPanel(page)
      const play = page.getByRole('button', OLLAMA_PLAY)
      const opacity = badgeOpacity(page, OLLAMA_PLAY)

      await page.mouse.move(0, 0)
      await expect.poll(opacity).toBe('0')
      await play.hover()
      await expect.poll(opacity).toBe('1')
      await page.mouse.move(0, 0)
      await expect.poll(opacity).toBe('0')

      await play.focus()
      await page.keyboard.press('Shift+Tab')
      await page.keyboard.press('Tab')
      await expect(play).toBeFocused()
      await expect.poll(opacity).toBe('1')
    })
  })
})
