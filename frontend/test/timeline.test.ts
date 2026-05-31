import { describe, it, expect } from 'vitest'
import { timelineBar } from '~/utils/timeline'

// Axis: 0 .. 1000 (arbitrary ms units); now = 1000.
const AXIS_START = 0
const AXIS_END = 1000
const NOW = 1000

describe('timelineBar (JCLAW-22 slice TL)', () => {
  it('positions a mid-axis completed run', () => {
    // run from 200..400 on a 0..1000 axis
    const { leftPct, widthPct } = timelineBar(200, 400, AXIS_START, AXIS_END, NOW)
    expect(leftPct).toBeCloseTo(20)
    expect(widthPct).toBeCloseTo(20)
  })

  it('extends a RUNNING run (no end) to now', () => {
    const { leftPct, widthPct } = timelineBar(600, null, AXIS_START, AXIS_END, NOW)
    expect(leftPct).toBeCloseTo(60)
    expect(widthPct).toBeCloseTo(40) // 600 -> now(1000)
  })

  it('gives an instant run a visible minimum width', () => {
    const { widthPct } = timelineBar(500, 500, AXIS_START, AXIS_END, NOW)
    expect(widthPct).toBeGreaterThanOrEqual(0.8)
  })

  it('clamps a run starting before the axis', () => {
    const { leftPct } = timelineBar(-500, 100, AXIS_START, AXIS_END, NOW)
    expect(leftPct).toBe(0)
  })

  it('never overflows the right edge', () => {
    const { leftPct, widthPct } = timelineBar(900, 5000, AXIS_START, AXIS_END, NOW)
    expect(leftPct + widthPct).toBeLessThanOrEqual(100.0001)
  })
})
