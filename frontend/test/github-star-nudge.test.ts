import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import GithubStarNudge from '~/components/GithubStarNudge.vue'

// Literal on purpose: asserting against the shared constant would pass even
// if that constant were renamed, which would silently orphan every flag
// already written into real users' browsers.
const SEEN_KEY = 'jclaw-star-nudge-seen'
const APPEAR_DELAY_MS = 1200
const VISIBLE_MS = 8000

/**
 * jsdom reports an all-zero rect for every element, which the component reads
 * as "no anchor" and stays hidden. Plant a stub anchor reporting a realistic
 * header-pill rect so placement runs the way it does in a browser.
 */
function plantAnchor(left = 1800) {
  const el = document.createElement('a')
  el.setAttribute('data-star-anchor', '')
  el.getBoundingClientRect = () => ({
    left, right: left + 80, top: 12, bottom: 44, width: 80, height: 32, x: left, y: 12,
    toJSON: () => ({}),
  })
  document.body.appendChild(el)
  return el
}

/** Mount, then run past the appear delay so the nudge has decided. */
async function mountAndSettle(props: { suppressed?: boolean } = {}) {
  const c = await mountSuspended(GithubStarNudge, { props })
  await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS)
  await c.vm.$nextTick()
  return c
}

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.clear()
  document.body.innerHTML = ''
  vi.stubGlobal('innerWidth', 1920)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('GithubStarNudge', () => {
  it('appears after the delay and reads "Leave a star!"', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')
  })

  it('stays hidden until the appear delay has elapsed', async () => {
    plantAnchor()
    const c = await mountSuspended(GithubStarNudge)
    await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS - 100)
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('never shows a second time once it has been seen', async () => {
    plantAnchor()
    localStorage.setItem(SEEN_KEY, '1')
    const c = await mountAndSettle()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('records that it was seen, so the next load stays quiet', async () => {
    plantAnchor()
    await mountAndSettle()
    expect(localStorage.getItem(SEEN_KEY)).toBe('1')
  })

  it('fades out on its own after the visible window', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')

    await vi.advanceTimersByTimeAsync(VISIBLE_MS)
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('dismisses early when the user clicks anywhere', async () => {
    plantAnchor()
    const c = await mountAndSettle()

    document.dispatchEvent(new Event('click'))
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('holds back while the guided-tour intro is up', async () => {
    plantAnchor()
    const c = await mountAndSettle({ suppressed: true })
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('does not burn its seen flag while suppressed', async () => {
    plantAnchor()
    await mountAndSettle({ suppressed: true })
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
  })

  it('stays hidden below 640px wide and keeps its showing for a wider visit', async () => {
    vi.stubGlobal('innerWidth', 639)
    plantAnchor(500)
    const c = await mountAndSettle()
    expect(c.text()).not.toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
  })

  it('shows at 640px wide', async () => {
    vi.stubGlobal('innerWidth', 640)
    plantAnchor(540)
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')
  })

  it('stays hidden while the pill sits past the edge of the viewport', async () => {
    plantAnchor(1880)
    const c = await mountAndSettle()
    expect(c.text()).not.toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
  })

  // Deliberate: the nudge does not pounce the moment the walkthrough ends,
  // because the user is likely still reading the last tour step.
  it('does not reappear when the tour ends mid-page', async () => {
    plantAnchor()
    const c = await mountAndSettle({ suppressed: true })

    await c.setProps({ suppressed: false })
    await vi.advanceTimersByTimeAsync(APPEAR_DELAY_MS)
    await c.vm.$nextTick()

    expect(c.text()).not.toContain('Leave a star!')
  })

  // ...and this is what makes that acceptable: the flag was never spent, so
  // the next full page load (a fresh mount) still owes the user its showing.
  it('shows on the next page load after a tour held it back', async () => {
    plantAnchor()
    const first = await mountAndSettle({ suppressed: true })
    expect(first.text()).not.toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
    first.unmount()

    const next = await mountAndSettle()
    expect(next.text()).toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBe('1')
  })

  it('stands down if the tour starts after it is already up', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    expect(c.text()).toContain('Leave a star!')

    await c.setProps({ suppressed: true })
    await c.vm.$nextTick()
    expect(c.text()).not.toContain('Leave a star!')
  })

  it('stays hidden when the GitHub pill is not on the page', async () => {
    const c = await mountAndSettle()
    expect(c.text()).not.toContain('Leave a star!')
    expect(localStorage.getItem(SEEN_KEY)).toBeNull()
  })

  it('points its arrow tip at the pill rather than a fixed offset', async () => {
    plantAnchor()
    const c = await mountAndSettle()

    // Anchor spans x 1800–1880 (centre 1840), bottom 44, in a 1920px viewport.
    // top  = 44 + 8 - TIP_Y(18)                    = 34
    // right = 1920 - 1840 - (SVG_W(120) - TIP_X(106)) = 66
    const style = c.find('[aria-hidden="true"]').attributes('style')
    expect(style).toContain('top: 34px')
    expect(style).toContain('right: 66px')
  })

  it('is inert: hidden from screen readers and transparent to clicks', async () => {
    plantAnchor()
    const c = await mountAndSettle()
    const el = c.find('[aria-hidden="true"]')
    expect(el.exists()).toBe(true)
    expect(el.classes()).toContain('pointer-events-none')
  })
})
