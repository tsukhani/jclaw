import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { useScrollRegion } from '~/composables/useScrollRegion'
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

describe('useScrollRegion', () => {
  const ScrollBox = defineComponent({
    props: { shown: Boolean },
    setup() {
      const box = ref<HTMLElement | null>(null)
      useScrollRegion(box, 'Context size by file, scrolls sideways')
      return { box }
    },
    template: '<div><div v-if="shown" ref="box" data-scroll="560" data-client="326"><table /></div></div>',
  })

  function stubWidths() {
    const keys = [['scrollWidth', 'scroll'], ['clientWidth', 'client']] as const
    const saved = keys.map(([key]) => Object.getOwnPropertyDescriptor(HTMLElement.prototype, key))
    for (const [key, attr] of keys) {
      Object.defineProperty(HTMLElement.prototype, key, {
        configurable: true,
        get(this: HTMLElement) {
          return Number(this.dataset[attr] ?? 0)
        },
      })
    }
    return () => keys.forEach(([key], i) => {
      const before = saved[i]
      if (before) Object.defineProperty(HTMLElement.prototype, key, before)
      else Reflect.deleteProperty(HTMLElement.prototype, key)
    })
  }

  it('marks a box that only appears after the page has mounted, as a card loaded later does', async () => {
    const restore = stubWidths()
    try {
      const c = mount(ScrollBox, { props: { shown: false } })
      await c.setProps({ shown: true })
      await nextTick()
      expect(marks(c.find('[data-scroll]').element as HTMLElement)).toEqual(['0', 'region', 'Context size by file, scrolls sideways'])
      c.unmount()
    }
    finally {
      restore()
    }
  })
})
