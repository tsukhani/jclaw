/**
 * Draws a Lewis structure as a monochrome SVG string. Pure: positions arrive in pixels from ./index.ts. Everything is
 * painted in currentColor, so the drawing takes the surrounding text color and follows a theme switch unredrawn.
 */
import { chargeLabel } from './electrons'

export interface DrawnAtom {
  x: number
  y: number
  label: string
  charge: number
  pairs: number
  radicals: number
}

export interface DrawnBond {
  a: number
  b: number
  order: number
}

const FONT = 16
const CHARGE_FONT = 12
const CHAR_WIDTH = 0.68 * FONT
const CAP_HEIGHT = 0.72 * FONT
const BOND_CLEARANCE = 3.5
const DOT_CLEARANCE = 5
const CHARGE_CLEARANCE = 9
const DOT_RADIUS = 1.8
const PAIR_HALF_GAP = 2.8
const LINE_GAP = 5
const STROKE = 1.6
const MARGIN = 4
const TAU = 2 * Math.PI
// Upper right in SVG coordinates, where y grows downward.
const PREFERRED_CHARGE_ANGLE = -Math.PI / 4

const fmt = (n: number) => String(Math.round(n * 10) / 10)

function escapeXml(text: string): string {
  return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;')
}

// Distance from an ellipse's center to its edge along angle `theta`.
function ellipseRadius(rx: number, ry: number, theta: number): number {
  return (rx * ry) / Math.hypot(ry * Math.cos(theta), rx * Math.sin(theta))
}

function angularDistance(a: number, b: number): number {
  const d = Math.abs(a - b) % TAU
  return Math.min(d, TAU - d)
}

/** Places `count` items in the widest gaps between the occupied directions, spread evenly within each gap. */
function spreadInGaps(occupied: number[], count: number): number[] {
  if (count === 0) return []
  if (occupied.length === 0) return Array.from({ length: count }, (_, k) => k * TAU / count)
  const sorted = occupied.map(a => ((a % TAU) + TAU) % TAU).sort((a, b) => a - b)
  const gaps = sorted.map((start, i) => ({ start, size: (sorted[i + 1] ?? sorted[0]! + TAU) - start, items: 0 }))
  for (let k = 0; k < count; k++) {
    let best = gaps[0]!
    for (const gap of gaps) {
      const room = gap.size / (gap.items + 1)
      const bestRoom = best.size / (best.items + 1)
      if (room > bestRoom + 1e-9 || (Math.abs(room - bestRoom) <= 1e-9 && gap.size > best.size)) best = gap
    }
    best.items++
  }
  return gaps.flatMap(gap => Array.from({ length: gap.items }, (_, j) => gap.start + gap.size * (j + 1) / (gap.items + 1)))
}

function chargeAngle(occupied: number[]): number {
  if (occupied.length === 0) return PREFERRED_CHARGE_ANGLE
  const candidates = Array.from({ length: 24 }, (_, k) => k * TAU / 24)
  const clearance = (angle: number) => Math.min(...occupied.map(o => angularDistance(angle, o)))
  const widest = Math.max(...candidates.map(clearance))
  const acceptable = candidates.filter(c => clearance(c) >= Math.min(widest, Math.PI / 4) - 1e-9)
  return acceptable.reduce((best, c) =>
    angularDistance(c, PREFERRED_CHARGE_ANGLE) < angularDistance(best, PREFERRED_CHARGE_ANGLE) ? c : best)
}

export function lewisSvg(atoms: DrawnAtom[], bonds: DrawnBond[], ariaLabel: string): string {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  const extend = (x: number, y: number, rx: number, ry: number) => {
    minX = Math.min(minX, x - rx)
    maxX = Math.max(maxX, x + rx)
    minY = Math.min(minY, y - ry)
    maxY = Math.max(maxY, y + ry)
  }
  const halfWidth = (atom: DrawnAtom) => atom.label.length * CHAR_WIDTH / 2
  const halfHeight = CAP_HEIGHT / 2
  const directions = atoms.map((): number[] => [])

  const lines: string[] = []
  for (const bond of bonds) {
    const from = atoms[bond.a]!
    const to = atoms[bond.b]!
    const theta = Math.atan2(to.y - from.y, to.x - from.x)
    directions[bond.a]!.push(theta)
    directions[bond.b]!.push(theta + Math.PI)
    const trimFrom = ellipseRadius(halfWidth(from) + BOND_CLEARANCE, halfHeight + BOND_CLEARANCE, theta)
    const trimTo = ellipseRadius(halfWidth(to) + BOND_CLEARANCE, halfHeight + BOND_CLEARANCE, theta)
    const [cos, sin] = [Math.cos(theta), Math.sin(theta)]
    for (let k = 0; k < bond.order; k++) {
      const offset = (k - (bond.order - 1) / 2) * LINE_GAP
      const [ox, oy] = [-sin * offset, cos * offset]
      lines.push(`<line x1="${fmt(from.x + cos * trimFrom + ox)}" y1="${fmt(from.y + sin * trimFrom + oy)}" `
        + `x2="${fmt(to.x - cos * trimTo + ox)}" y2="${fmt(to.y - sin * trimTo + oy)}"/>`)
    }
  }

  const dots: string[] = []
  const labels: string[] = []
  const charges: string[] = []
  atoms.forEach((atom, i) => {
    const hw = halfWidth(atom)
    labels.push(`<text x="${fmt(atom.x)}" y="${fmt(atom.y + halfHeight)}">${escapeXml(atom.label)}</text>`)
    extend(atom.x, atom.y, hw, halfHeight)

    const angles = spreadInGaps(directions[i]!, atom.pairs + atom.radicals)
    angles.forEach((theta, k) => {
      const r = ellipseRadius(hw + DOT_CLEARANCE, halfHeight + DOT_CLEARANCE, theta)
      const [cx, cy] = [atom.x + r * Math.cos(theta), atom.y + r * Math.sin(theta)]
      const centers = k < atom.pairs
        ? [-1, 1].map(s => [cx - Math.sin(theta) * PAIR_HALF_GAP * s, cy + Math.cos(theta) * PAIR_HALF_GAP * s] as const)
        : [[cx, cy] as const]
      for (const [x, y] of centers) {
        dots.push(`<circle cx="${fmt(x)}" cy="${fmt(y)}" r="${DOT_RADIUS}"/>`)
        extend(x, y, DOT_RADIUS, DOT_RADIUS)
      }
    })

    const text = chargeLabel(atom.charge)
    if (text) {
      const theta = chargeAngle([...directions[i]!, ...angles])
      const textHalfWidth = text.length * 0.68 * CHARGE_FONT / 2
      const r = ellipseRadius(hw + CHARGE_CLEARANCE + textHalfWidth, halfHeight + CHARGE_CLEARANCE, theta)
      const [x, y] = [atom.x + r * Math.cos(theta), atom.y + r * Math.sin(theta)]
      charges.push(`<text x="${fmt(x)}" y="${fmt(y + 0.36 * CHARGE_FONT)}">${text}</text>`)
      extend(x, y, textHalfWidth, 0.36 * CHARGE_FONT)
    }
  })

  const [x0, y0] = [minX - MARGIN, minY - MARGIN]
  const [width, height] = [maxX - minX + 2 * MARGIN, maxY - minY + 2 * MARGIN]
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${fmt(x0)} ${fmt(y0)} ${fmt(width)} ${fmt(height)}" `
    + `width="${fmt(width)}" height="${fmt(height)}" role="img" aria-label="${escapeXml(ariaLabel)}">`
    + `<g stroke="currentColor" stroke-width="${STROKE}" stroke-linecap="round">${lines.join('')}</g>`
    + `<g fill="currentColor">${dots.join('')}</g>`
    + `<g fill="currentColor" font-size="${FONT}" text-anchor="middle">${labels.join('')}</g>`
    + `<g fill="currentColor" font-size="${CHARGE_FONT}" text-anchor="middle">${charges.join('')}</g>`
    + `</svg>`
}
