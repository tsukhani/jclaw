/**
 * JCLAW-22 (slice TL): geometry for the Timeline view's run bars.
 */
export interface TimelineBar {
  leftPct: number
  widthPct: number
}

/**
 * Position + width (as percentages) for one run bar on a horizontal time axis
 * spanning [axisStartMs, axisEndMs]. A RUNNING run (no end) extends to nowMs.
 * Results are clamped to the axis, with a small minimum width so an instant
 * run stays visible, and the width never overflows the right edge.
 */
export function timelineBar(
  startMs: number,
  endMs: number | null,
  axisStartMs: number,
  axisEndMs: number,
  nowMs: number,
): TimelineBar {
  const span = Math.max(1, axisEndMs - axisStartMs)
  const start = Math.max(axisStartMs, startMs)
  const end = Math.min(axisEndMs, endMs ?? nowMs)
  const rawWidthPct = ((Math.max(end, start) - start) / span) * 100
  // Min visible width so instant runs don't vanish; never wider than the axis.
  const widthPct = Math.min(100, Math.max(0.8, rawWidthPct))
  let leftPct = Math.max(0, Math.min(100, ((start - axisStartMs) / span) * 100))
  // If the bar would overflow the right edge, pull it left so the min width
  // stays visible — otherwise just-now runs collapse to a sliver at 100%.
  if (leftPct + widthPct > 100) leftPct = Math.max(0, 100 - widthPct)
  return { leftPct, widthPct }
}
