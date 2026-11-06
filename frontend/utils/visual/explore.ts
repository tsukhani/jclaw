import type { ExploreParam, ExploreSpec } from './spec'

/** Clamps to the range and snaps to min plus a whole multiple of step, never rounding past max. */
export function snapParam(param: ExploreParam, raw: number): number {
  const value = Number.isFinite(raw) ? Math.min(param.max, Math.max(param.min, raw)) : param.value
  let n = Math.round((value - param.min) / param.step)
  // Float steps (0.1) leave 1e-15 residue; rounding to 10 places keeps the shown value clean.
  const at = (k: number) => Number((param.min + k * param.step).toFixed(10))
  while (n > 0 && at(n) > param.max) n--
  return at(n)
}

type Frame = ExploreSpec['frames'][number]

function nearest(values: number[], target: number): number {
  let best = values[0]!
  for (const v of values) {
    const d = Math.abs(v - target)
    const bestD = Math.abs(best - target)
    if (d < bestD || (d === bestD && v < best)) best = v
  }
  return best
}

/**
 * The curve at the given slider values. The first param is the interpolation axis; each other param, in declaration
 * order, narrows the frames to those at the value nearest its slider (ties to the lower), so the result depends only
 * on the slider positions. Between two bracketing frames each y is linear; outside their span, the nearest frame.
 */
export function interpolateFrames(spec: ExploreSpec, values: Record<string, number>): [number, number][] {
  const [axis, ...rest] = spec.params
  const valueOf = (p: ExploreParam) => values[p.id] ?? p.value
  let frames: Frame[] = spec.frames
  for (const p of rest) {
    const pick = nearest(frames.map(f => f.params[p.id]!), valueOf(p))
    frames = frames.filter(f => f.params[p.id] === pick)
  }
  const key = (f: Frame) => f.params[axis!.id]!
  const sorted = [...frames].sort((a, b) => key(a) - key(b))
  const v = valueOf(axis!)
  const lo = sorted.findLast(f => key(f) <= v) ?? sorted[0]!
  const hi = sorted.find(f => key(f) >= v) ?? sorted.at(-1)!
  if (lo === hi || key(lo) === key(hi)) return lo.points.map(([x, y]) => [x, y] as [number, number])
  const t = (v - key(lo)) / (key(hi) - key(lo))
  return lo.points.map(([x, y], i) => [x, y + t * (hi.points[i]![1] - y)] as [number, number])
}
