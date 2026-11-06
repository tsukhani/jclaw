/**
 * Series encodings beyond color (JF-195): each index gets its own dash pattern and marker, so a series is told apart
 * in grayscale and the legend can name it by shape.
 */
import type { ChartSpec } from './spec'

export const DASHES = ['none', '7 3', '2 3', '9 3 2 3', '12 5', '1 4'] as const

export type MarkerShape = 'circle' | 'square' | 'triangle' | 'diamond' | 'cross' | 'ring'
export const MARKERS: readonly MarkerShape[] = ['circle', 'square', 'triangle', 'diamond', 'cross', 'ring']

export function seriesColor(i: number): string {
  return `var(--visual-series-${(i % 6) + 1})`
}

/** A marker centered on (x, y), as path data; `ring` and `cross` are drawn unfilled. */
export function markerPath(shape: MarkerShape, x: number, y: number, r = 3.5): string {
  switch (shape) {
    case 'circle':
    case 'ring':
      return `M${x - r},${y} a${r},${r} 0 1,0 ${2 * r},0 a${r},${r} 0 1,0 ${-2 * r},0 Z`
    case 'square':
      return `M${x - r},${y - r} h${2 * r} v${2 * r} h${-2 * r} Z`
    case 'triangle':
      return `M${x},${y - r * 1.2} L${x + r * 1.1},${y + r * 0.8} L${x - r * 1.1},${y + r * 0.8} Z`
    case 'diamond':
      return `M${x},${y - r * 1.3} L${x + r},${y} L${x},${y + r * 1.3} L${x - r},${y} Z`
    case 'cross':
      return `M${x - r},${y - r} L${x + r},${y + r} M${x + r},${y - r} L${x - r},${y + r}`
  }
}

export const isHollow = (shape: MarkerShape) => shape === 'ring' || shape === 'cross'

export function axisHeader(axis: ChartSpec['x'], fallback: string): string {
  const label = axis.label || fallback
  return axis.unit ? `${label} (${axis.unit})` : label
}

export function formatNumber(v: number): string {
  return String(Number(v.toPrecision(6)))
}
