import type { Page } from '@playwright/test'
import type { ConfigResponse, LayaDecisionStatus, OllamaDecisionStatus } from '~/types/api'
import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT-23 — Decision Providers.
 *
 * settings.decision-providers.test.ts covers the panel's logic in jsdom, where no asset is served,
 * no media element plays and no stylesheet applies. This spec covers what only a real browser can:
 * each card's picture and clip are served and decode, preload="none" fetches nothing before the click,
 * the click plays the clip, and the speaker badge the CSS hides at rest appears on hover, on
 * keyboard focus and on a screen with no hover.
 *
 * Read-only: blockApiWrites answers every write, and the key editor is never opened. Every test stubs
 * the Laya status read, and the Ollama card's tests stub that card's reads, so no test depends on a
 * real Ollama or Laya server or on the instance's settings.
 */

const PLAY = { name: 'Play JEV saying “My name is Jev”' }
const OLLAMA_PLAY = { name: 'Play a llama’s call' }
const LAYA_PLAY = { name: 'Play Laya shouting “Liar!”' }

/** Each card's picture and clip, which the picture tests below check alike. */
const CARDS = [
  { name: 'JEV', image: 'decision-jev-portrait', note: 'decision-jev-retention', clip: '/jev.mp3', play: PLAY },
  { name: 'Laya', image: 'decision-laya-portrait', note: 'decision-laya-about', clip: '/laya.mp3', play: LAYA_PLAY },
  { name: 'Ollama', image: 'decision-ollama-logo', note: 'decision-ollama-privacy', clip: '/ollama.mp3', play: OLLAMA_PLAY },
] as const

async function openPanel(page: Page) {
  await gotoPage(page, '/settings?section=decision-providers')
  await expect(page.getByTestId('decision-provider-jev')).toBeVisible()
}

/** The speaker badge's computed opacity: the CSS, not a class name, decides what is visible. */
function badgeOpacity(page: Page, play = PLAY) {
  const badge = page.getByRole('button', play).locator('span[aria-hidden="true"]').first()
  return () => badge.evaluate(el => getComputedStyle(el).opacity)
}

async function expectStandsThreeLinesTall(page: Page, image: string, note: string) {
  const img = page.getByTestId(image)
  await expect.poll(() => img.evaluate((el: HTMLImageElement) => el.complete ? el.naturalWidth : 0)).toBeGreaterThan(0)

  const height = await img.evaluate(el => el.getBoundingClientRect().height)
  const line = await page.getByTestId(note).evaluate(el => Number.parseFloat(getComputedStyle(el).lineHeight))
  expect(Math.abs(height - 3 * line), `${image} ${height}px against a ${line}px line`).toBeLessThanOrEqual(1)
}

async function expectBadgeFollowsHoverAndFocus(page: Page, play: { name: string }) {
  const button = page.getByRole('button', play)
  const opacity = badgeOpacity(page, play)

  await page.mouse.move(0, 0)
  await expect.poll(opacity).toBe('0')
  await button.hover()
  await expect.poll(opacity).toBe('1')
  await page.mouse.move(0, 0)
  await expect.poll(opacity).toBe('0')

  // Arrive by Tab, so :focus-visible matches for the reason a keyboard user would see it.
  await button.focus()
  await page.keyboard.press('Shift+Tab')
  await page.keyboard.press('Tab')
  await expect(button).toBeFocused()
  await expect.poll(opacity).toBe('1')
}

/** `lower` is the element right after `upper`, laid out beneath it in the same column. */
async function expectDirectlyBelow(page: Page, upper: string, lower: string) {
  // One evaluate, so both rects come from one layout: a card grows as its reads land.
  const { next, above, below } = await page.evaluate(({ a, b }) => {
    const box = (id: string) => {
      const { x, y, height } = document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect()
      return { x, y, height }
    }
    return { next: document.querySelector<HTMLElement>(`[data-testid="${a}"] + *`)?.dataset.testid, above: box(a), below: box(b) }
  }, { a: upper, b: lower })
  expect(next).toBe(lower)
  expect(below.x).toBe(above.x)
  expect(below.y).toBeGreaterThanOrEqual(above.y + above.height)
}

const DECISION_MODELS = ['tev1:latest', 'nimble:latest']

const REACHABLE: OllamaDecisionStatus = {
  baseUrl: 'http://localhost:11434', customized: false, reachable: true, error: null, models: DECISION_MODELS, cloudModels: [], contextTokens: {},
}

async function stubOllama(page: Page, status: OllamaDecisionStatus) {
  await page.route(url => url.pathname === '/api/decision/ollama', route => route.fulfill({ json: status }))
}

// An address no instance reports, so a test that reads it back shows the default stub answered.
const LAYA_DOWN: LayaDecisionStatus = {
  baseUrl: 'http://laya.e2e.invalid:8000', reachable: false, error: 'not reachable (UnknownHostException)', loaded: [], device: null,
}

const LAYA_UP: LayaDecisionStatus = {
  baseUrl: 'http://localhost:8000', reachable: true, error: null, loaded: ['english', 'typed-decisions'], device: 'mps',
}

async function stubLaya(page: Page, status: LayaDecisionStatus) {
  await page.route(url => url.pathname === '/api/decision/laya', route => route.fulfill({ json: status }))
}

/** Answer GET /api/config with the real entries, each card's model selection and the router's classifier replaced by `overrides`. */
async function stubConfig(page: Page, overrides: Record<string, string> = {}) {
  const replaced = new Set([
    'decision.ollama.models', 'decision.laya.models', 'router.classifier.provider', 'router.classifier.model', ...Object.keys(overrides),
  ])
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
  // Otherwise the instance dials its stored Laya address; a test's own stubLaya registers later, so it answers first.
  test.beforeEach(({ page }) => stubLaya(page, LAYA_DOWN))

  test('the clip and the portrait are served as media, not as the SPA fallback', async ({ request }) => {
    // An unknown path answers 200 text/html from the SPA catch-all, so the status alone proves nothing.
    for (const [path, type] of [
      ['/jev.mp3', 'audio/'], ['/jev.webp', 'image/'], ['/ollama.mp3', 'audio/'], ['/ollama.webp', 'image/'],
      ['/laya.mp3', 'audio/'], ['/laya.webp', 'image/'],
    ] as const) {
      const res = await request.get(path)
      expect(res.status(), path).toBe(200)
      expect(res.headers()['content-type'], path).toContain(type)
    }
  })

  for (const card of CARDS) {
    test.describe(`the ${card.name} picture`, () => {
      // No picture test depends on a real Ollama server.
      test.beforeEach(({ page }) => stubOllama(page, REACHABLE))

      test('decodes and stands three text lines tall beside the note', async ({ page }) => {
        const writes = await blockApiWrites(page)
        await openPanel(page)
        await expectStandsThreeLinesTall(page, card.image, card.note)
        expect(writes()).toEqual([])
      })

      test('fetches its clip only once clicked, and the click plays it', async ({ page }) => {
        const writes = await blockApiWrites(page)
        const fetched: string[] = []
        page.on('request', (req) => {
          if (new URL(req.url()).pathname === card.clip) fetched.push(req.url())
        })
        await openPanel(page)
        const picture = page.getByTestId(card.image)
        await expect.poll(() => picture.evaluate((img: HTMLImageElement) => img.complete)).toBe(true)
        expect(fetched, 'preload="none" should fetch nothing before the click').toEqual([])

        await page.getByRole('button', card.play).click()
        const clip = page.locator(`audio[src="${card.clip}"]`)
        await expect.poll(() => fetched.length).toBeGreaterThan(0)
        await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.error?.code ?? 0), 'media error code').toBe(0)
        await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.duration || 0)).toBeGreaterThan(0)
        await expect.poll(() => clip.evaluate((a: HTMLAudioElement) => a.played.length), 'the clip never played').toBeGreaterThan(0)
        expect(writes()).toEqual([])
      })

      test('hides its speaker badge at rest and shows it on hover and on keyboard focus', async ({ page }) => {
        // Reduced motion drops the opacity transition, so each read sees the settled value, not a frame of the fade.
        await page.emulateMedia({ reducedMotion: 'reduce' })
        await blockApiWrites(page)
        await openPanel(page)
        await expectBadgeFollowsHoverAndFocus(page, card.play)
      })
    })
  }

  test.describe('on a screen with no hover', () => {
    test.use({ isMobile: true, hasTouch: true })

    test('the speaker badge is shown without any hover', async ({ page }) => {
      await blockApiWrites(page)
      await openPanel(page)
      // Guards the emulation itself: without it this test would pass on a hover device by never asking.
      expect(await page.evaluate(() => matchMedia('(hover: none)').matches)).toBe(true)
      for (const card of CARDS) await expect.poll(badgeOpacity(page, card.play), card.name).toBe('1')
    })
  })

  test.describe('the Ollama card', () => {
    // The panel re-reads the config after a write, so a stubConfig route.fetch() can still be in flight at teardown.
    test.afterEach(({ page }) => page.unrouteAll({ behavior: 'ignoreErrors' }))

    test('renders last, directly below the Laya card', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      await stubConfig(page)
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-provider-ollama')).toBeVisible()
      await expectDirectlyBelow(page, 'decision-provider-laya', 'decision-provider-ollama')
      await expect(page.locator('[data-testid="decision-provider-ollama"] + *')).toHaveCount(0)
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
        error: 'not reachable (ConnectException)', models: [], cloudModels: [], contextTokens: {},
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
  })

  test.describe('the Laya card', () => {
    // The panel re-reads the config after a write, so a stubConfig route.fetch() can still be in flight at teardown.
    test.afterEach(({ page }) => page.unrouteAll({ behavior: 'ignoreErrors' }))

    test('renders directly below the JEV card', async ({ page }) => {
      await stubOllama(page, REACHABLE)
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-provider-laya')).toBeVisible()
      await expect(page.getByTestId('decision-laya-address')).toContainText(LAYA_DOWN.baseUrl)
      await expectDirectlyBelow(page, 'decision-provider-jev', 'decision-provider-laya')
      expect(writes()).toEqual([])
    })

    test('a reachable server marks its loaded checkpoints and names its device', async ({ page }) => {
      await stubLaya(page, LAYA_UP)
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-laya-status')).toHaveText('reachable')
      await expect(page.getByTestId('decision-laya-device')).toHaveText('Server device: mps')
      await expect(page.getByTestId('decision-laya-checkpoints').locator('label')).toHaveText([
        'english 512 tokens, loaded', 'multilingual 1,024 tokens', 'typed-decisions 1,024 tokens, loaded',
      ])
      await expect(page.getByTestId('decision-laya-error')).toHaveCount(0)
      await expect(page.getByTestId('decision-laya-keyed')).toHaveCount(0)
      expect(writes()).toEqual([])
    })

    test('an unreachable server says so and shows the error', async ({ page }) => {
      // Not LAYA_DOWN's error, so the test also shows its own stub answering rather than the default.
      await stubLaya(page, {
        baseUrl: 'http://192.168.1.30:8000', reachable: false, error: 'not reachable (SocketTimeoutException)', loaded: [], device: null,
      })
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-laya-status')).toHaveText('not reachable')
      await expect(page.getByTestId('decision-laya-error')).toHaveText('not reachable (SocketTimeoutException)')
      await expect(page.getByTestId('decision-laya-device')).toHaveCount(0)
      await expect(page.getByTestId('decision-laya-checkpoints').locator('label')).toHaveText([
        'english 512 tokens', 'multilingual 1,024 tokens', 'typed-decisions 1,024 tokens',
      ])
      expect(writes()).toEqual([])
    })

    test('a server that says only that it is up shows the hint about the key', async ({ page }) => {
      await stubLaya(page, { ...LAYA_UP, loaded: [], device: null })
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await expect(page.getByTestId('decision-laya-status')).toHaveText('reachable')
      await expect(page.getByTestId('decision-laya-keyed')).toContainText('set or correct the key above to see what it has loaded')
      await expect(page.getByTestId('decision-laya-device')).toHaveCount(0)
      await expect(page.getByTestId('decision-laya-error')).toHaveCount(0)
      expect(writes()).toEqual([])
    })

    test('ticking a checkpoint sends one write that stores it in decision.laya.models', async ({ page }) => {
      await stubLaya(page, LAYA_UP)
      await stubConfig(page)
      const writes = await blockApiWrites(page)
      const posts = recordConfigPosts(page)
      await openPanel(page)
      const box = page.getByTestId('decision-laya-checkpoint-english').getByRole('checkbox')
      await box.click()

      await expect.poll(writes).toEqual(['POST /api/config'])
      // Enabled again only once the write and its re-read finish, so the count below is final.
      await expect(box).toBeEnabled()
      expect(writes()).toEqual(['POST /api/config'])
      expect(posts()).toHaveLength(1)
      const { key, value } = posts()[0] as { key: string, value: string }
      expect(key).toBe('decision.laya.models')
      expect(JSON.parse(value)).toEqual(['english'])
    })

    test('the limits link opens the user guide at Laya\'s limits', async ({ page }) => {
      const writes = await blockApiWrites(page)
      await openPanel(page)
      await page.getByTestId('decision-laya-limits-link').click()
      await expect(page).toHaveURL(/\/guide#settings-laya-limits$/)
      const heading = page.locator('#settings-laya-limits')
      await expect(heading).toHaveText('Laya\'s limits')
      await expect(heading).toBeInViewport()
      expect(writes()).toEqual([])
    })
  })
})
