import { describe, it, expect } from 'vitest'
import { markScrollRegion } from '~/utils/scroll-region'

function box(widths: { scroll: number, client: number }) {
  const el = document.createElement('div')
  Object.defineProperty(el, 'scrollWidth', { configurable: true, get: () => widths.scroll })
  Object.defineProperty(el, 'clientWidth', { configurable: true, get: () => widths.client })
  return el
}

const marks = (el: HTMLElement) => [el.getAttribute('tabindex'), el.getAttribute('role'), el.getAttribute('aria-label')]

describe('markScrollRegion', () => {
  it('makes a box that overflows a named region the keyboard can reach', () => {
    const el = box({ scroll: 560, client: 326 })
    markScrollRegion(el, 'Wide figure, scrolls sideways')
    expect(marks(el)).toEqual(['0', 'region', 'Wide figure, scrolls sideways'])
  })

  it('leaves out of the tab order a box that fits, and one a rounding pixel over', () => {
    for (const scroll of [326, 327]) {
      const el = box({ scroll, client: 326 })
      markScrollRegion(el, 'Wide table, scrolls sideways')
      expect(marks(el), `scrollWidth ${scroll}`).toEqual([null, null, null])
    }
    const el = box({ scroll: 328, client: 326 })
    markScrollRegion(el, 'Wide table, scrolls sideways')
    expect(marks(el)).toEqual(['0', 'region', 'Wide table, scrolls sideways'])
  })

  it('takes the region away once the column grows to fit it', () => {
    const widths = { scroll: 1624, client: 672 }
    const el = box(widths)
    markScrollRegion(el, 'Wide figure, scrolls sideways')
    widths.client = 1624
    markScrollRegion(el, 'Wide figure, scrolls sideways')
    expect(marks(el)).toEqual([null, null, null])
  })
})
