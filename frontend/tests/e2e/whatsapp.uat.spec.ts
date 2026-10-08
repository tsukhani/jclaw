import type { Page } from '@playwright/test'
import type { WhatsAppBindingSummary, WhatsAppSubscriptionState, WhatsAppUsageState } from '../../types/api'
import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT-24 — WhatsApp binding cards: the Meta subscription warning, this month's replies and the
 * delivery-failure line.
 *
 * All three read Meta with a real binding's token, and Subscribe writes to Meta, so the cards run
 * on a binding list answered inside the browser, behind blockApiWrites. What runs against the
 * instance is the two routes' own gate: a session, then a 404 for a binding that does not exist,
 * both before anything is sent to Meta.
 */

const BINDINGS = '/api/channels/whatsapp/bindings'
/** One binding's subscription or usage route: group 1 is the binding id, group 2 which of the two. */
const PER_BINDING = /^\/api\/channels\/whatsapp\/bindings\/(\d+)\/(subscription|usage)$/
const ABSENT_BINDING = 999_999_999
const CLOUD = 9001
const WABA = '104857600000001'
const MINUTE = 60_000
const HOUR = 60 * MINUTE

function binding(o: Partial<WhatsAppBindingSummary> = {}): WhatsAppBindingSummary {
  return {
    id: CLOUD, agentId: null, agentName: 'e2e-uat-cloud', transport: 'CLOUD_API', phoneNumberId: '100000000000001',
    hasAccessToken: true, hasAppSecret: true, hasVerifyToken: true, verifiedName: null, displayPhoneNumber: null,
    templateName: null, templateLanguage: null, defaultTarget: null, ownerNumber: null, enabled: true,
    createdAt: null, updatedAt: null,
    lastDeliveryFailureAt: null, lastDeliveryFailureCode: null, lastDeliveryFailureTitle: null,
    ...o,
  }
}

function subscription(bindingId: number, o: Partial<WhatsAppSubscriptionState> = {}): WhatsAppSubscriptionState {
  return { bindingId, state: 'SUBSCRIBED', wabaId: WABA, appId: '200000000000002', reason: null, ...o }
}

function usage(bindingId: number, o: Partial<WhatsAppUsageState> = {}): WhatsAppUsageState {
  return { bindingId, state: 'NOT_APPLICABLE', year: 2026, month: 10, replies: null, billed: null, allowance: 1000, reason: null, ...o }
}

interface Meta {
  subscription?: Partial<WhatsAppSubscriptionState>
  usage?: Partial<WhatsAppUsageState>
  /** The answer to a Subscribe click; by default the app is now subscribed. */
  onSubscribe?: () => { status: number, json: unknown }
}

/**
 * Serve `bindings` as the page's list and answer every per-binding read with `meta`. Registered
 * after blockApiWrites: Playwright consults the last route first, so the Subscribe POST is answered
 * here and never by the instance, while every other write still reaches the blocker.
 */
async function serveCards(page: Page, bindings: WhatsAppBindingSummary[], meta: Meta = {}) {
  const blocked = await blockApiWrites(page)
  const asked: string[] = []
  await page.route(url => url.pathname === BINDINGS, route =>
    route.request().method() === 'GET' ? route.fulfill({ json: bindings }) : route.fallback())
  await page.route(url => PER_BINDING.test(url.pathname), (route) => {
    const [, id, kind] = PER_BINDING.exec(new URL(route.request().url()).pathname)!
    const method = route.request().method()
    asked.push(`${method} ${id}/${kind}`)
    if (kind === 'usage') return route.fulfill({ json: usage(Number(id), meta.usage) })
    if (method === 'POST') return route.fulfill(meta.onSubscribe?.() ?? { status: 200, json: subscription(Number(id)) })
    return route.fulfill({ json: subscription(Number(id), meta.subscription) })
  })
  return { asked: () => asked, blocked }
}

async function openCards(page: Page, ...agentNames: string[]) {
  await gotoPage(page, '/channels/whatsapp')
  for (const name of agentNames.length ? agentNames : ['e2e-uat-cloud']) {
    await expect(page.getByRole('heading', { name })).toBeVisible()
  }
}

function at(msAgo: number) {
  return new Date(Date.now() - msAgo).toISOString()
}

test.describe('UAT-24 WhatsApp binding cards', () => {
  test.describe('Meta app subscription', () => {
    test('a number whose app is not subscribed warns with its business account, and Subscribe clears it', async ({ page }) => {
      const meta = await serveCards(page, [binding()], { subscription: { state: 'NOT_SUBSCRIBED' } })
      await openCards(page)

      const warning = page.getByTestId('subscription-warning')
      await expect(warning).toContainText('Meta will not deliver this number\'s messages to JClaw until the app is subscribed to business account')
      await expect(warning).toContainText(WABA)
      await warning.getByRole('button', { name: 'Subscribe' }).click()

      await expect(warning).toHaveCount(0)
      expect(meta.asked().filter(a => a.startsWith('POST'))).toEqual([`POST ${CLOUD}/subscription`])
      expect(meta.blocked()).toEqual([])
    })

    test('a Subscribe that Meta refuses keeps the warning and shows Meta\'s reason', async ({ page }) => {
      const refusal = 'Meta refused the subscription: (#200) Permissions error'
      await serveCards(page, [binding()], {
        subscription: { state: 'NOT_SUBSCRIBED' },
        onSubscribe: () => ({ status: 422, json: { type: 'error', code: 'cloud_api_subscribe_failed', message: refusal } }),
      })
      await openCards(page)

      const warning = page.getByTestId('subscription-warning')
      await warning.getByRole('button', { name: 'Subscribe' }).click()
      await expect(warning.getByTestId('subscription-error')).toHaveText(refusal)
      await expect(warning.getByRole('button', { name: 'Subscribe' })).toBeEnabled()
    })

    test('a subscription Meta would not report says why, in place of the warning', async ({ page }) => {
      await serveCards(page, [binding()], {
        subscription: { state: 'UNKNOWN', wabaId: null, appId: null, reason: 'Meta answered 401' },
      })
      await openCards(page)

      await expect(page.getByTestId('subscription-unknown')).toHaveText('Couldn\'t check the Meta app subscription: Meta answered 401')
      await expect(page.getByTestId('subscription-warning')).toHaveCount(0)
    })
  })

  test.describe('this month\'s replies', () => {
    const cases = [
      { name: 'under 800 replies the bar is plain and filled in proportion', replies: 412, billed: 0, label: '412 of 1,000 free replies this month', now: '412', share: 0.412, warns: false },
      { name: 'from 800 replies the bar warns', replies: 800, billed: 0, label: '800 of 1,000 free replies this month', now: '800', share: 0.8, warns: true },
      { name: 'past the allowance the bar is full and names the billed count', replies: 1240, billed: 240, label: '1,240 replies this month, 240 billed', now: '1000', share: 1, warns: true },
    ]
    for (const c of cases) {
      test(c.name, async ({ page }) => {
        await serveCards(page, [binding()], { usage: { state: 'COUNTED', replies: c.replies, billed: c.billed } })
        await openCards(page)

        // Found by role and name, so the bar is also what a screen reader announces.
        const bar = page.getByRole('progressbar', { name: c.label })
        await expect(bar).toHaveAttribute('aria-valuenow', c.now)
        await expect(bar).toHaveAttribute('aria-valuemax', '1000')
        const fill = page.getByTestId('usage-fill')
        await expect(fill).toHaveClass(c.warns ? /bg-amber-500/ : /bg-emerald-600/)
        await expect.poll(async () => {
          const [track, filled] = await Promise.all([bar.boundingBox(), fill.boundingBox()])
          return filled!.width / track!.width
        }).toBeCloseTo(c.share, 2)
      })
    }

    test('usage Meta could not report says why, with no bar', async ({ page }) => {
      await serveCards(page, [binding()], { usage: { state: 'UNKNOWN', reason: 'Meta answered 500' } })
      await openCards(page)

      await expect(page.getByTestId('usage-unknown')).toHaveText('Couldn\'t read this month\'s replies from Meta: Meta answered 500')
      await expect(page.getByRole('progressbar')).toHaveCount(0)
    })
  })

  test.describe('delivery failures', () => {
    test('a reply Meta failed to deliver in the last day is on the card with its code and reason', async ({ page }) => {
      await serveCards(page, [binding({
        lastDeliveryFailureAt: at(2 * HOUR + MINUTE),
        lastDeliveryFailureCode: 131042,
        lastDeliveryFailureTitle: 'Business eligibility payment issue',
      })])
      await openCards(page)

      await expect(page.getByTestId('delivery-failure-warning'))
        .toHaveText('A reply failed to reach a customer 2h ago — Meta error 131042: Business eligibility payment issue')
    })

    test('a delivery failure older than 24 hours is no longer shown', async ({ page }) => {
      await serveCards(page, [binding({ lastDeliveryFailureAt: at(25 * HOUR), lastDeliveryFailureCode: 131042 })])
      await openCards(page)

      await expect(page.getByTestId('delivery-failure-warning')).toHaveCount(0)
    })
  })

  test('only an enabled Cloud API binding is asked about, so a disabled or WhatsApp-Web one never reaches Meta', async ({ page }) => {
    const meta = await serveCards(page, [
      binding(),
      binding({ id: 9002, agentName: 'e2e-uat-disabled', enabled: false }),
      binding({ id: 9003, agentName: 'e2e-uat-web', transport: 'WHATSAPP_WEB', phoneNumberId: null }),
    ], { subscription: { state: 'NOT_SUBSCRIBED' }, usage: { state: 'COUNTED', replies: 12, billed: 0 } })
    await openCards(page, 'e2e-uat-cloud', 'e2e-uat-disabled', 'e2e-uat-web')

    // Any binding asked about would render both, so three cards showing one of each is the claim.
    await expect(page.getByTestId('subscription-warning')).toHaveCount(1)
    await expect(page.getByTestId('usage')).toHaveCount(1)
    const asked = [...meta.asked()].sort((a, b) => a.localeCompare(b))
    expect(asked).toEqual([`GET ${CLOUD}/subscription`, `GET ${CLOUD}/usage`])
  })

  test.describe('the routes themselves', () => {
    test('subscription and usage answer 404 for a binding that does not exist', async ({ request }) => {
      for (const kind of ['subscription', 'usage']) {
        const res = await request.get(`${BINDINGS}/${ABSENT_BINDING}/${kind}`)
        expect(res.status(), `GET ${kind}`).toBe(404)
      }
      // The binding lookup comes before the call to Meta, so this POST writes nothing anywhere.
      const res = await request.post(`${BINDINGS}/${ABSENT_BINDING}/subscription`)
      expect(res.status(), 'POST subscription').toBe(404)
    })

    test('both are session-gated', async ({ playwright }) => {
      const anon = await playwright.request.newContext({
        baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
        storageState: { cookies: [], origins: [] },
      })
      for (const kind of ['subscription', 'usage']) {
        const res = await anon.get(`${BINDINGS}/${ABSENT_BINDING}/${kind}`)
        expect(res.status(), kind).toBe(401)
      }
      await anon.dispose()
    })
  })
})
