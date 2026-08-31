import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { test, expect, gotoPage } from './helpers'

/**
 * UAT-9 — Settings.
 *
 * Twenty-six panels behind one page, each lazily mounted from the TOC. The
 * historical failure mode is a panel that throws on mount and leaves the
 * content column blank while the TOC still highlights it (see the cold-boot
 * top-level-await regression), so every section is visited rather than
 * spot-checked.
 *
 * No setting is saved. Maintenance (Restart + Upgrade since JCLAW-1057) is visited but
 * its action buttons are never clicked — both stop this JVM.
 */
/**
 * Read the section ids out of the registry rather than restating them (JCLAW-1139).
 *
 * This list used to be hardcoded, and drifted: JCLAW-1057 merged Password, Upgrade and
 * Restart into one Maintenance section, and five specs here failed from that merge until
 * someone happened to run the suite by hand. Playwright needs the ids at collection time to
 * generate one test per section, so the registry is parsed as text — importing it would pull
 * in the `.vue` panel components, which this runner cannot compile.
 *
 * The regex is deliberately paired with a floor assertion below: if the registry's formatting
 * ever changes, this yields an empty list, and an empty list would make every test here pass
 * by doing nothing.
 */
const REGISTRY = fileURLToPath(new URL('../../components/settings/sections.ts', import.meta.url))
const SECTIONS = [...readFileSync(REGISTRY, 'utf8').matchAll(/id: '([a-z0-9-]+)'/g)].map(m => m[1]!)

test.describe('UAT-9 settings', () => {
  test('the section registry was actually read', () => {
    // Guards the parse above. Without this, a registry rename or a formatting change would
    // empty SECTIONS and every per-section test would silently stop existing.
    expect(SECTIONS.length,
      `parsed no section ids from ${REGISTRY} — the registry format probably changed`)
      .toBeGreaterThan(20)
    expect(new Set(SECTIONS).size, 'duplicate section ids in the registry').toBe(SECTIONS.length)
  })

  test('every section is listed in the table of contents', async ({ page }) => {
    await gotoPage(page, '/settings')
    for (const id of SECTIONS) {
      await expect(page.getByTestId(`settings-toc-item-${id}`), `TOC entry ${id}`).toBeVisible()
    }
  })

  for (const id of SECTIONS) {
    test(`${id} panel mounts without error`, async ({ page }) => {
      const errors: string[] = []
      page.on('console', (msg) => {
        if (msg.type() === 'error') errors.push(msg.text())
      })
      page.on('pageerror', err => errors.push(String(err)))

      await gotoPage(page, '/settings')
      await page.getByTestId(`settings-toc-item-${id}`).click()

      // A panel that throws on mount leaves the column empty while the TOC
      // still marks it active — assert on rendered content, not on the click.
      const panel = page.locator('main')
      await expect(panel).toBeVisible()
      await expect(async () => {
        const text = (await panel.innerText()).trim()
        expect(text.length, `${id} panel rendered no content`).toBeGreaterThan(40)
      }).toPass({ timeout: 10_000 })

      expect(errors, `console errors mounting ${id}`).toHaveLength(0)
    })
  }

  test('section deep link opens that panel directly', async ({ page }) => {
    await gotoPage(page, '/settings?section=providers')
    await expect(async () => {
      expect((await page.locator('main').innerText())).toContain('Provider')
    }).toPass({ timeout: 10_000 })
  })

  test('config read endpoints back the panels', async ({ request }) => {
    for (const path of ['/api/config', '/api/logging/levels', '/api/providers', '/api/ocr/status']) {
      expect((await request.get(path)).status(), path).toBe(200)
    }
  })

  test('maintenance preflight is a read, and is not triggered here', async ({ page }) => {
    // Visiting the panel must not arm anything. The POSTs that reboot or upgrade the JVM are
    // deliberately never exercised by this suite. Restart and Upgrade live under Maintenance
    // since JCLAW-1057.
    await gotoPage(page, '/settings')
    await page.getByTestId('settings-toc-item-maintenance').click()
    await expect(page.locator('main')).toBeVisible()
  })
})
