import { describe, it, expect } from 'vitest'
import { lewisSvg, type DrawnAtom, type DrawnBond } from '~/utils/lewis/svg'

const atom = (x: number, y: number, label: string, pairs = 0, radicals = 0, charge = 0): DrawnAtom =>
  ({ x, y, label, charge, pairs, radicals })

function parse(svg: string): SVGSVGElement {
  return new DOMParser().parseFromString(svg, 'image/svg+xml').documentElement as unknown as SVGSVGElement
}

const num = (el: Element, name: string) => Number(el.getAttribute(name))

// Water bent at 120 degrees: O in the middle, its two lone pairs on the open side.
const water: [DrawnAtom[], DrawnBond[]] = [
  [atom(0, 0, 'O', 2), atom(-41.6, 24, 'H'), atom(41.6, 24, 'H')],
  [{ a: 0, b: 1, order: 1 }, { a: 0, b: 2, order: 1 }],
]

describe('lewisSvg', () => {
  it('draws two dots per lone pair and one per radical', () => {
    expect(parse(lewisSvg(...water, 'water')).querySelectorAll('circle')).toHaveLength(4)

    // Nitric oxide: N carries one pair and one radical, O two pairs.
    const no = lewisSvg([atom(0, 0, 'N', 1, 1), atom(48, 0, 'O', 2)], [{ a: 0, b: 1, order: 2 }], 'nitric oxide')
    expect(parse(no).querySelectorAll('circle')).toHaveLength(2 + 1 + 4)
  })

  it('draws one stroke per bond order', () => {
    const single = lewisSvg([atom(0, 0, 'H'), atom(48, 0, 'H')], [{ a: 0, b: 1, order: 1 }], 'x')
    const double = lewisSvg([atom(0, 0, 'O', 2), atom(48, 0, 'O', 2)], [{ a: 0, b: 1, order: 2 }], 'x')
    const triple = lewisSvg([atom(0, 0, 'N', 1), atom(48, 0, 'N', 1)], [{ a: 0, b: 1, order: 3 }], 'x')
    expect(parse(single).querySelectorAll('line')).toHaveLength(1)
    expect(parse(double).querySelectorAll('line')).toHaveLength(2)
    expect(parse(triple).querySelectorAll('line')).toHaveLength(3)
  })

  it('shortens each bond so it stops short of both labels', () => {
    const line = parse(lewisSvg([atom(0, 0, 'H'), atom(48, 0, 'Cl', 3)], [{ a: 0, b: 1, order: 1 }], 'x'))
      .querySelector('line')!
    expect(num(line, 'x1')).toBeGreaterThan(5)
    expect(num(line, 'x2')).toBeLessThan(48 - 10)
  })

  it('puts lone pairs in the widest gap, away from the bonds', () => {
    const svg = parse(lewisSvg(...water, 'water'))
    const bondAngles = [Math.atan2(24, -41.6), Math.atan2(24, 41.6)]
    for (const dot of svg.querySelectorAll('circle')) {
      const angle = Math.atan2(num(dot, 'cy'), num(dot, 'cx'))
      for (const bond of bondAngles) {
        const apart = Math.abs(Math.atan2(Math.sin(angle - bond), Math.cos(angle - bond)))
        expect(apart, `dot at ${angle.toFixed(2)} rad vs bond at ${bond.toFixed(2)} rad`).toBeGreaterThan(Math.PI / 4)
      }
    }
  })

  it('labels every atom, carbon and hydrogen included, and marks charges', () => {
    const svg = parse(lewisSvg(
      [atom(0, 0, 'C'), atom(48, 0, 'O', 3, 0, -1), atom(-48, 0, 'H')],
      [{ a: 0, b: 1, order: 1 }, { a: 0, b: 2, order: 1 }],
      'x',
    ))
    const texts = [...svg.querySelectorAll('text')].map(t => t.textContent)
    expect(texts).toEqual(['C', 'O', 'H', '−'])
  })

  it('is an image named by its aria-label, with the label escaped', () => {
    const markup = lewisSvg(...water, 'Lewis structure of H2O, SMILES [H]O[H] <&">')
    const svg = parse(markup)
    expect(svg.getAttribute('role')).toBe('img')
    expect(svg.getAttribute('aria-label')).toBe('Lewis structure of H2O, SMILES [H]O[H] <&">')
    expect(markup).not.toContain('<&">')
  })

  it('paints only in currentColor', () => {
    const markup = lewisSvg(
      [atom(0, 0, 'N', 0, 0, 1), atom(48, 0, 'H'), atom(-48, 0, 'H'), atom(0, 48, 'H'), atom(0, -48, 'H')],
      [1, 2, 3, 4].map(b => ({ a: 0, b, order: 1 })),
      'ammonium',
    )
    expect(markup).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(|hsla?\(|\b(?:black|white)\b/i)
    const painted = [...markup.matchAll(/(?:fill|stroke)="([^"]*)"/g)].map(m => m[1])
    expect(painted.length).toBeGreaterThan(0)
    expect(new Set(painted)).toEqual(new Set(['currentColor']))
  })

  it('draws a lone ion with its pairs spread round it', () => {
    const svg = parse(lewisSvg([atom(0, 0, 'Cl', 4, 0, -1)], [], 'chloride'))
    expect(svg.querySelectorAll('circle')).toHaveLength(8)
    const viewBox = svg.getAttribute('viewBox')!.split(' ').map(Number)
    expect(viewBox[2]).toBeGreaterThan(0)
    expect(viewBox[3]).toBeGreaterThan(0)
  })
})
