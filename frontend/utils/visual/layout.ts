/**
 * Pure geometry for the visual fence (JF-195): the diagram's layered, tree and cycle layouts, and the chart's scales.
 * v1 runs no general graph layout: layers come from the edge list, and an edge that closes a cycle is a back edge.
 */
import type { DiagramSpec } from './spec'

export const NODE_W = 150
const GAP_X = 56
const GAP_Y = 36
const PAD = 16
const LINE_H = 15
const LABEL_CHARS = 20
const BACK_DROP = 28

export interface PlacedNode { id: string, lines: string[], detail?: string, x: number, y: number, w: number, h: number }
export interface PlacedEdge { from: string, to: string, label?: string, back: boolean, path: string, labelX: number, labelY: number }
export interface DiagramLayout { nodes: PlacedNode[], edges: PlacedEdge[], width: number, height: number }

export function wrapLabel(label: string, max = LABEL_CHARS): string[] {
  const lines: string[] = []
  let line = ''
  // A word longer than a line (an identifier) is cut into line-long chunks rather than overflowing the node.
  const words = label.split(/\s+/).filter(Boolean).flatMap(w => w.match(new RegExp(`.{1,${max}}`, 'g')) ?? [])
  for (const word of words) {
    if (line && line.length + 1 + word.length > max) {
      lines.push(line)
      line = word
    }
    else line = line ? `${line} ${word}` : word
  }
  if (line) lines.push(line)
  return lines.length ? lines : [label]
}

/** DFS in declaration order; an edge to a node still on the stack (a self-loop included) is a back edge. */
export function backEdges(spec: DiagramSpec): Set<number> {
  const out = new Map<string, number[]>()
  spec.edges.forEach((e, i) => out.set(e.from, [...(out.get(e.from) ?? []), i]))
  const state = new Map<string, 'open' | 'done'>()
  const back = new Set<number>()
  const visit = (node: string) => {
    state.set(node, 'open')
    for (const i of out.get(node) ?? []) {
      const to = spec.edges[i]!.to
      const seen = state.get(to)
      if (seen === 'open') back.add(i)
      else if (seen === undefined) visit(to)
    }
    state.set(node, 'done')
  }
  for (const node of spec.nodes) if (!state.has(node.id)) visit(node.id)
  return back
}

/** Each node's layer is its longest path from a source over forward edges. */
export function layers(spec: DiagramSpec, back: Set<number>): Map<string, number> {
  const incoming = new Map<string, string[]>()
  spec.edges.forEach((e, i) => {
    if (!back.has(i)) incoming.set(e.to, [...(incoming.get(e.to) ?? []), e.from])
  })
  const layer = new Map<string, number>()
  const depth = (node: string): number => {
    const known = layer.get(node)
    if (known !== undefined) return known
    const d = Math.max(-1, ...(incoming.get(node) ?? []).map(depth)) + 1
    layer.set(node, d)
    return d
  }
  for (const node of spec.nodes) depth(node.id)
  return layer
}

function center(n: PlacedNode) {
  return { cx: n.x + n.w / 2, cy: n.y + n.h / 2 }
}

// Where the segment from a node's center towards (tx, ty) leaves its rectangle.
function boundary(n: PlacedNode, tx: number, ty: number) {
  const { cx, cy } = center(n)
  const dx = tx - cx
  const dy = ty - cy
  if (dx === 0 && dy === 0) return { x: cx, y: cy }
  const s = Math.min(dx === 0 ? Infinity : (n.w / 2) / Math.abs(dx), dy === 0 ? Infinity : (n.h / 2) / Math.abs(dy))
  return { x: cx + dx * s, y: cy + dy * s }
}

function place(spec: DiagramSpec, nodeH: number, layerOf: Map<string, number>): PlacedNode[] {
  const base = (n: DiagramSpec['nodes'][number]) => ({ id: n.id, lines: wrapLabel(n.label), detail: n.detail, w: NODE_W, h: nodeH })
  if (spec.layout === 'cycle') {
    const count = spec.nodes.length
    const rx = Math.max(NODE_W, (count * (NODE_W + 24)) / (2 * Math.PI))
    const ry = Math.max(nodeH * 1.5, rx * 0.55)
    return spec.nodes.map((n, i) => {
      const a = -Math.PI / 2 + (2 * Math.PI * i) / count
      // Centered at (rx + NODE_W/2, ry + nodeH/2), so the leftmost and topmost nodes start at 0.
      return { ...base(n), x: rx + rx * Math.cos(a), y: ry + ry * Math.sin(a) }
    })
  }
  const index = new Map<number, number>()
  const slot = new Map<string, number>()
  for (const n of spec.nodes) {
    const l = layerOf.get(n.id)!
    slot.set(n.id, index.get(l) ?? 0)
    index.set(l, (index.get(l) ?? 0) + 1)
  }
  const widest = Math.max(...index.values())
  return spec.nodes.map((n) => {
    const l = layerOf.get(n.id)!
    const s = slot.get(n.id)!
    if (spec.layout === 'tree') {
      const offset = ((widest - index.get(l)!) * (NODE_W + GAP_X)) / 2
      return { ...base(n), x: offset + s * (NODE_W + GAP_X), y: l * (nodeH + GAP_Y * 1.5) }
    }
    return { ...base(n), x: l * (NODE_W + GAP_X), y: s * (nodeH + GAP_Y) }
  })
}

function route(spec: DiagramSpec, a: PlacedNode, b: PlacedNode, back: boolean, bottom: number, right: number) {
  if (back && spec.layout === 'flow') {
    const sx = a.x + a.w / 2
    const tx = b.x + b.w / 2
    const y = bottom + BACK_DROP
    return { path: `M${sx},${a.y + a.h} V${y} H${tx} V${b.y + b.h}`, labelX: (sx + tx) / 2, labelY: y - 4 }
  }
  if (back && spec.layout === 'tree') {
    const x = right + BACK_DROP
    const sy = a.y + a.h / 2
    const ty = b.y + b.h / 2
    return { path: `M${a.x + a.w},${sy} H${x} V${ty} H${b.x + b.w}`, labelX: x + 4, labelY: (sy + ty) / 2 }
  }
  const to = center(b)
  const from = center(a)
  const p = boundary(a, to.cx, to.cy)
  const q = boundary(b, from.cx, from.cy)
  return { path: `M${p.x},${p.y} L${q.x},${q.y}`, labelX: (p.x + q.x) / 2, labelY: (p.y + q.y) / 2 - 4 }
}

export function layoutDiagram(spec: DiagramSpec): DiagramLayout {
  const lineCount = Math.max(...spec.nodes.map(n => wrapLabel(n.label).length))
  const nodeH = 16 + lineCount * LINE_H
  const cycle = spec.layout === 'cycle'
  const back = cycle ? new Set<number>() : backEdges(spec)
  // A cycle layout has no layers, and its edges may well close a cycle the layering would recurse around.
  const placed = place(spec, nodeH, cycle ? new Map() : layers(spec, back)).map(n => ({ ...n, x: n.x + PAD, y: n.y + PAD }))
  const byId = new Map(placed.map(n => [n.id, n]))
  const bottom = Math.max(...placed.map(n => n.y + n.h))
  const right = Math.max(...placed.map(n => n.x + n.w))
  const edges = spec.edges.map((e, i) => ({
    from: e.from,
    to: e.to,
    label: e.label,
    back: back.has(i),
    ...route(spec, byId.get(e.from)!, byId.get(e.to)!, back.has(i), bottom, right),
  }))
  const hasBack = edges.some(e => e.back)
  return {
    nodes: placed,
    edges,
    width: right + PAD + (hasBack && spec.layout === 'tree' ? BACK_DROP + 40 : 0),
    height: bottom + PAD + (hasBack && spec.layout === 'flow' ? BACK_DROP + 8 : 0),
  }
}

export interface Scale { (v: number): number, domain: [number, number], ticks: number[] }

/** Round tick values covering [min, max], about `count` of them. */
export function niceTicks(min: number, max: number, count = 5): number[] {
  if (min === max) {
    const pad = min === 0 ? 1 : Math.abs(min) / 2
    return niceTicks(min - pad, max + pad, count)
  }
  const raw = (max - min) / count
  const mag = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= raw)!
  const ticks: number[] = []
  const start = Math.floor(min / step) * step
  // Multiplied, not accumulated: a step below the values' ulp would never advance `t += step`.
  for (let k = 0; k <= 100; k++) {
    const t = start + k * step
    if (t > max + step * 1e-9) break
    ticks.push(Number(t.toFixed(10)))
  }
  if (ticks.at(-1)! < max) ticks.push(Number((ticks.at(-1)! + step).toFixed(10)))
  return ticks
}

export function linearScale(values: number[], range: [number, number], opts: { zero?: boolean, nice?: boolean } = {}): Scale {
  let lo = Math.min(...values, ...(opts.zero ? [0] : []))
  let hi = Math.max(...values, ...(opts.zero ? [0] : []))
  const ticks = opts.nice === false ? [] : niceTicks(lo, hi)
  if (ticks.length) {
    lo = ticks[0]!
    hi = ticks.at(-1)!
  }
  if (lo === hi) hi = lo + 1
  const [r0, r1] = range
  const scale = ((v: number) => r0 + ((v - lo) / (hi - lo)) * (r1 - r0)) as Scale
  scale.domain = [lo, hi]
  scale.ticks = ticks
  return scale
}

export function formatTick(v: number): string {
  if (Math.abs(v) >= 1e6 || (Math.abs(v) < 1e-3 && v !== 0)) return v.toExponential(1)
  return String(Number(v.toPrecision(6)))
}

export function linePath(points: [number, number][]): string {
  return points.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(2)},${y.toFixed(2)}`).join(' ')
}
