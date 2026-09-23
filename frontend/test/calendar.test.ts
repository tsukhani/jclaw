import { describe, it, expect } from 'vitest'
import { expandCron, packLanes } from '~/utils/calendar'

// Fixed window: June 2026 (local). June 1 2026 is a Monday, so Fridays fall on
// 5/12/19/26 (26 is the last) and the 2nd Monday is the 8th.
const from = new Date(2026, 5, 1) // Mon Jun 1 00:00 local
const to = new Date(2026, 6, 1) // Wed Jul 1 00:00 local
const days = (fires: Date[]) => fires.map(d => d.getDate())

describe('expandCron L/# day modifiers (JCLAW-440)', () => {
  it('"5L" fires only on the LAST Friday of the month (not every Friday)', () => {
    const f = expandCron('0 0 17 * * 5L', from, to)
    expect(days(f)).toEqual([26])
    expect(f[0]!.getDay()).toBe(5) // Friday
    expect(f[0]!.getHours()).toBe(17)
  })

  it('"5L" with Quartz "?" day-of-month works the same', () => {
    expect(days(expandCron('0 0 17 ? * 5L', from, to))).toEqual([26])
  })

  it('plain "5" still fires every Friday — regression guard', () => {
    expect(days(expandCron('0 0 17 * * 5', from, to))).toEqual([5, 12, 19, 26])
  })

  it('"1#2" fires only on the 2nd Monday', () => {
    const f = expandCron('0 0 9 * * 1#2', from, to)
    expect(days(f)).toEqual([8])
    expect(f[0]!.getDay()).toBe(1) // Monday
  })

  it('day-of-month "L" fires on the last calendar day', () => {
    expect(days(expandCron('0 0 9 L * *', from, to))).toEqual([30])
  })

  it('the legacy 25-31 idiom still lands on the in-range Friday', () => {
    // June days 25-30 are available; the only Friday in that range is the 26th.
    expect(days(expandCron('0 0 17 25-31 * 5', from, to))).toEqual([26])
  })

  it('daily / @daily fire every day', () => {
    expect(expandCron('0 0 9 * * *', from, to)).toHaveLength(30)
    expect(expandCron('@daily', from, to)).toHaveLength(30)
  })

  it('unparseable / unsupported (sec != 0) yields no fires', () => {
    expect(expandCron('30 0 9 * * *', from, to)).toEqual([]) // 6-field with sec=30
    expect(expandCron('not a cron', from, to)).toEqual([])
  })
})

describe('packLanes day-column overlap', () => {
  const span = (topPct: number, heightPct: number) => ({ topPct, heightPct })

  it('leaves non-overlapping spans full width', () => {
    expect(packLanes([span(0, 10), span(20, 10)]))
      .toEqual([{ leftPct: 0, widthPct: 100 }, { leftPct: 0, widthPct: 100 }])
  })

  it('splits two concurrent spans side by side', () => {
    expect(packLanes([span(0, 10), span(5, 10)]))
      .toEqual([{ leftPct: 0, widthPct: 50 }, { leftPct: 50, widthPct: 50 }])
  })

  it('treats abutting spans (end === start) as non-overlapping', () => {
    expect(packLanes([span(0, 10), span(10, 10)]))
      .toEqual([{ leftPct: 0, widthPct: 100 }, { leftPct: 0, widthPct: 100 }])
  })

  it('scopes lane count to the cluster — a 9am collision keeps 3pm full width', () => {
    const [a, b, loner] = packLanes([span(0, 10), span(5, 10), span(60, 5)])
    expect(a).toEqual({ leftPct: 0, widthPct: 50 })
    expect(b).toEqual({ leftPct: 50, widthPct: 50 })
    expect(loner).toEqual({ leftPct: 0, widthPct: 100 })
  })

  it('reuses a freed lane within one cluster', () => {
    // A(0-10) and B(5-15) collide; C(11-20) clears A, so C takes A's lane. All
    // three are one cluster (B overlaps C), so all three are half width.
    expect(packLanes([span(0, 10), span(5, 10), span(11, 9)])).toEqual([
      { leftPct: 0, widthPct: 50 },
      { leftPct: 50, widthPct: 50 },
      { leftPct: 0, widthPct: 50 },
    ])
  })

  it('packs three-deep concurrency into thirds', () => {
    const lanes = packLanes([span(0, 30), span(5, 20), span(10, 10)])
    expect(lanes.map(l => l.widthPct)).toEqual([100 / 3, 100 / 3, 100 / 3])
    expect(lanes.map(l => l.leftPct)).toEqual([0, 100 / 3, 200 / 3])
  })

  it('returns placements parallel to the input, not sorted order', () => {
    // Input is reverse-chronological; output index 0 must still describe span(5).
    expect(packLanes([span(5, 10), span(0, 10)]))
      .toEqual([{ leftPct: 50, widthPct: 50 }, { leftPct: 0, widthPct: 50 }])
  })

  it('handles the empty column', () => {
    expect(packLanes([])).toEqual([])
  })
})
