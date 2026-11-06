import type { Locator, Page } from '@playwright/test'
import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT-25 — Chat visuals (JF-195).
 *
 * The conversation detail page renders a transcript answered inside the browser for a conversation id no
 * install has, so the spec reads no real transcript and, behind blockApiWrites, writes nothing. A live model
 * turn is left out on purpose: it costs tokens, depends on what the model writes, and would leave a real
 * conversation on the instance that this suite could not remove without deleting a transcript.
 *
 * What only a browser can show is the bounded-mount test: jsdom has no layout, so the unit tests can only
 * prove that figures the IntersectionObserver reports get mounted.
 */
const CONVERSATION_ID = 999_999_001

const fence = (spec: object) => '```visual\n' + JSON.stringify(spec) + '\n```'

const chart = (n: number) => ({
  kind: 'chart',
  title: `Sprint velocity ${n}`,
  caption: `Committed against delivered ${n}`,
  x: { label: 'Sprint' },
  y: { label: 'Points', unit: 'pts' },
  series: [
    { name: 'Committed', points: [[1, 34], [2, 38], [3, 41]] },
    { name: 'Delivered', points: [[1, 30], [2, 37], [3, 40]] },
  ],
})

const diagram = {
  kind: 'diagram',
  layout: 'flow',
  title: 'How a request becomes a reply',
  nodes: [{ id: 'in', label: 'Inbound message' }, { id: 'run', label: 'Agent run' }, { id: 'out', label: 'Outbound reply' }],
  edges: [{ from: 'in', to: 'run' }, { from: 'run', to: 'out' }],
  steps: [
    { title: 'Receive', body: 'The message is persisted first.', focus: ['in'] },
    { title: 'Run', body: 'The agent takes one turn.', focus: ['run'] },
  ],
}

const explore = {
  kind: 'explore',
  title: 'Compound interest',
  x: { label: 'Years' },
  y: { label: 'Balance', unit: 'RM' },
  params: [{ id: 'rate', label: 'Annual rate', unit: '%', min: 1, max: 10, step: 1, value: 5 }],
  frames: [
    { params: { rate: 1 }, points: [[0, 1000], [10, 1105], [20, 1220]] },
    { params: { rate: 10 }, points: [[0, 1000], [10, 2594], [20, 6727]] },
  ],
}

// Frame 2 carries a different x value from frame 1, which the validator refuses by name.
const malformed = {
  ...explore,
  title: 'Malformed',
  frames: [
    { params: { rate: 1 }, points: [[0, 1000], [1, 1010]] },
    { params: { rate: 10 }, points: [[0, 1000], [2, 1100]] },
  ],
}

/** One assistant message per entry, answered for the conversation and its messages. */
async function serveTranscript(page: Page, contents: string[]) {
  const conversation = {
    id: CONVERSATION_ID, agentId: 1, preview: 'visuals', channelType: 'web', agentName: 'main', peerId: null,
    messageCount: contents.length, createdAt: '2026-11-06T10:00:00Z', updatedAt: '2026-11-06T10:00:00Z', pinned: false,
  }
  const rows = contents.map((content, i) => ({
    id: i + 1, role: 'assistant', content, createdAt: '2026-11-06T10:00:00Z', toolCalls: null, toolResults: null,
  }))
  await page.route(url => url.pathname === `/api/conversations/${CONVERSATION_ID}`, route => route.fulfill({ json: conversation }))
  // The page pages by offset until a page comes back short, so everything past the first page is empty.
  await page.route(url => url.pathname === `/api/conversations/${CONVERSATION_ID}/messages`, (route) => {
    const offset = Number(new URL(route.request().url()).searchParams.get('offset') ?? 0)
    return route.fulfill({ json: offset === 0 ? rows : [] })
  })
  return await blockApiWrites(page)
}

const figures = (page: Page) => page.locator('figure.visual-view')

/** The figure drawn: scrolled near the viewport, which is when the directive mounts it. */
async function drawn(figure: Locator) {
  await figure.scrollIntoViewIfNeeded()
  await expect(figure.locator('.visual-host svg').first()).toBeVisible()
  return figure
}

const mountedCount = (page: Page) =>
  page.evaluate(() => [...document.querySelectorAll('.visual-host')].filter(h => h.childElementCount > 0).length)

test.describe('UAT-25 chat visuals', () => {
  test('a chart, a diagram and an explorer draw inline with their JSON hidden and the caption below', async ({ page }) => {
    const blocked = await serveTranscript(page, [fence(chart(1)), fence(diagram), fence(explore)])
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)

    await expect(figures(page)).toHaveCount(3)
    for (let i = 0; i < 3; i++) {
      const figure = await drawn(figures(page).nth(i))
      await expect(figure.locator(':scope > .code-block')).toBeHidden()
      await expect(figure.locator('svg').first()).toHaveAttribute('role', 'img')
    }
    await expect(figures(page).first().locator('figcaption')).toHaveText('Committed against delivered 1')
    expect(blocked(), 'the staged page wrote to the instance').toEqual([])
  })

  test('moving an explorer slider redraws the curve without a request', async ({ page }) => {
    await serveTranscript(page, [fence(explore)])
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)
    const figure = await drawn(figures(page).first())

    const curve = () => figure.locator('.visual-host svg path').evaluateAll(paths => paths.map(p => p.getAttribute('d')).join('|'))
    const before = await curve()
    const requests: string[] = []
    page.on('request', req => requests.push(new URL(req.url()).pathname))

    const slider = figure.getByRole('slider', { name: 'Annual rate' })
    await slider.fill('10')
    await expect(figure).toContainText('10 %')
    await expect.poll(curve).not.toBe(before)
    expect(requests.filter(p => p.startsWith('/api/')), 'a slider move asked the server for something').toEqual([])
  })

  test('stepping a diagram shows the step\'s text', async ({ page }) => {
    await serveTranscript(page, [fence(diagram)])
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)
    const figure = await drawn(figures(page).first())

    // Nothing is selected until the first Next, and the first step has no step before it.
    await expect(figure.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await figure.getByRole('button', { name: 'Next' }).click()
    await expect(figure).toContainText('The message is persisted first.')
    await expect(figure.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await figure.getByRole('button', { name: 'Next' }).click()
    await expect(figure).toContainText('The agent takes one turn.')
    await expect(figure.getByRole('button', { name: 'Previous' })).toBeEnabled()
  })

  test('a malformed spec keeps its JSON and names what is wrong', async ({ page }) => {
    await serveTranscript(page, [`A refused one:\n\n${fence(malformed)}\n\nStill renders.`])
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)

    await expect(page.locator('p.visual-error')).toHaveText('Frame 2 does not carry the same x values as frame 1.')
    await expect(page.locator('.code-block', { hasText: '"title":"Malformed"' })).toBeVisible()
    await expect(page.getByText('Still renders.')).toBeVisible()
    await expect(page.locator('.visual-host')).toHaveCount(0)
  })

  test('reduced motion turns playback off and leaves manual stepping', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await serveTranscript(page, [fence(diagram)])
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)
    const figure = await drawn(figures(page).first())

    await expect(figure.getByRole('button', { name: 'Play' })).toBeDisabled()
    await figure.getByRole('button', { name: 'Next' }).click()
    await expect(figure).toContainText('The message is persisted first.')
  })

  test('twenty figures never have more than four drawn at once', async ({ page }) => {
    const twenty = Array.from({ length: 20 }, (_, i) => fence(chart(i + 1)))
    await serveTranscript(page, twenty)
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)
    await expect(figures(page)).toHaveCount(20)

    let most = 0
    for (let i = 0; i < 20; i++) {
      await drawn(figures(page).nth(i))
      most = Math.max(most, await mountedCount(page))
    }
    // A harness that counted nothing would pass the bound below, so the floor comes first.
    expect(most, 'no figure was ever counted as drawn').toBeGreaterThan(0)
    expect(most, 'figures drawn at once while scrolling twenty').toBeLessThanOrEqual(4)
  })

  test('turning visual.enabled off leaves the spec as plain JSON', async ({ page }) => {
    await serveTranscript(page, [fence(chart(1))])
    // Read-through: the real config with the one entry replaced, never written back.
    await page.route(url => url.pathname === '/api/config', async (route) => {
      const response = await route.fetch()
      const body = await response.json() as { entries?: Array<{ key: string, value: string }> }
      const others = (body.entries ?? []).filter(e => e.key !== 'visual.enabled')
      return route.fulfill({ response, json: { ...body, entries: [...others, { key: 'visual.enabled', value: 'false' }] } })
    })
    await gotoPage(page, `/conversations/${CONVERSATION_ID}`)

    await expect(page.locator('.code-block', { hasText: '"kind":"chart"' })).toBeVisible()
    await expect(page.locator('figure.visual-view')).toHaveCount(0)
    await expect(page.locator('.visual-host')).toHaveCount(0)
  })
})
