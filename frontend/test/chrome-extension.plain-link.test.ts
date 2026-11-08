import { describe, expect, it } from 'vitest'
import { plainLink } from '../../chrome-extension/plain-link.js'

const PAGE = 'https://shop.example.com/cart'
const link = (over: Record<string, unknown> = {}) => ({
  tag: 'A',
  rawHref: '/about',
  href: 'https://shop.example.com/about',
  onclick: false,
  role: null,
  ...over,
})

describe('a click that needs no approval', () => {
  it('goes to another page on the web', () => {
    expect(plainLink(link(), PAGE)).toBe(true)
    expect(plainLink(link({ rawHref: 'https://other.example.org/x', href: 'https://other.example.org/x' }), PAGE)).toBe(true)
  })

  it('is never a hash link, which a single-page app answers in script', () => {
    expect(plainLink(link({ rawHref: '#', href: `${PAGE}#` }), PAGE)).toBe(false)
    expect(plainLink(link({ rawHref: '#delete', href: `${PAGE}#delete` }), PAGE)).toBe(false)
  })

  it('is never an empty or missing href', () => {
    expect(plainLink(link({ rawHref: '', href: PAGE }), PAGE)).toBe(false)
    expect(plainLink(link({ rawHref: '   ', href: PAGE }), PAGE)).toBe(false)
    expect(plainLink(link({ rawHref: null, href: '' }), PAGE)).toBe(false)
  })

  it('is never a link back to the same page, with or without a fragment', () => {
    expect(plainLink(link({ rawHref: '/cart', href: PAGE }), PAGE)).toBe(false)
    expect(plainLink(link({ rawHref: '/cart?x=1#top', href: `${PAGE}?x=1#top` }), `${PAGE}?x=1`)).toBe(false)
  })

  it('is never a link with a click handler or an action role', () => {
    expect(plainLink(link({ onclick: true }), PAGE)).toBe(false)
    expect(plainLink(link({ role: 'button' }), PAGE)).toBe(false)
    expect(plainLink(link({ role: 'Menuitem' }), PAGE)).toBe(false)
    expect(plainLink(link({ role: 'link' }), PAGE)).toBe(true)
  })

  it('is never a javascript:, mailto: or data: address', () => {
    for (const href of ['javascript:void(0)', 'mailto:a@b.c', 'data:text/html,x']) {
      expect(plainLink(link({ rawHref: href, href }), PAGE)).toBe(false)
    }
  })

  it('is never anything but an anchor, nor a page address it cannot read', () => {
    expect(plainLink(link({ tag: 'BUTTON' }), PAGE)).toBe(false)
    expect(plainLink(link({ href: 'not a url' }), PAGE)).toBe(false)
    expect(plainLink(link(), 'not a url')).toBe(false)
  })
})
