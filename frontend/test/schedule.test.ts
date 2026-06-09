import { describe, it, expect } from 'vitest'
import type { Task } from '~/types/api'
import {
  humanSchedule,
  humanCron,
  humanDuration,
  formatTime12h,
  formatDateTime,
  timeUntil,
} from '~/utils/schedule'

// Intl can emit a NARROW NO-BREAK SPACE (U+202F) before am/pm on newer ICU;
// normalize all whitespace to a plain space so the exact-format assertions
// don't hinge on the runtime's ICU build.
const norm = (s: string) => s.replace(/\s+/g, ' ')

// Minimal Task factory — the formatters only read a handful of fields, so cast
// a partial through unknown rather than constructing the full row shape.
function task(partial: Partial<Task>): Task {
  return partial as unknown as Task
}

describe('timeUntil (JCLAW-438 — always-relative countdown)', () => {
  const base = Date.parse('2026-06-09T00:00:00Z')
  const at = (deltaMs: number) => new Date(base + deltaMs).toISOString()

  it('seconds, plural and singular', () => {
    expect(timeUntil(at(30_000), base)).toBe('in 30 seconds')
    expect(timeUntil(at(1_000), base)).toBe('in 1 second')
  })

  it('minutes', () => {
    expect(timeUntil(at(5 * 60_000), base)).toBe('in 5 minutes')
    expect(timeUntil(at(60_000), base)).toBe('in 1 minute')
  })

  it('hours — under a day stays in hours', () => {
    expect(timeUntil(at(3 * 3_600_000), base)).toBe('in 3 hours')
    expect(timeUntil(at(23 * 3_600_000), base)).toBe('in 23 hours')
  })

  it('days — 24h+ rolls up to days', () => {
    expect(timeUntil(at(25 * 3_600_000), base)).toBe('in 1 day')
    expect(timeUntil(at(4 * 86_400_000), base)).toBe('in 4 days')
  })

  it('already elapsed → past due', () => {
    expect(timeUntil(at(-10_000), base)).toBe('past due')
    expect(timeUntil(at(0), base)).toBe('past due')
  })

  it('unparseable input is echoed back, not blanked', () => {
    expect(timeUntil('not-a-date', base)).toBe('not-a-date')
  })
})

describe('formatDateTime (JCLAW-438 — absolute, app-zone, day-first, no zone label)', () => {
  it('renders an ISO instant in the given IANA zone, day-first, no zone label', () => {
    // 13:15:00 in +08 → 1:15 pm in Asia/Kuala_Lumpur (fixed offset, no DST).
    const out = formatDateTime('2026-06-10T13:15:00+08:00', 'Asia/Kuala_Lumpur')
    expect(norm(out)).toBe('10 Jun 2026 · 1:15 pm')
    expect(out).not.toContain('(') // no zone label (item 1)
  })

  it('shows seconds only when the instant is off a whole minute (item 3)', () => {
    expect(norm(formatDateTime('2026-06-09T13:30:00+08:00', 'Asia/Kuala_Lumpur')))
      .toBe('9 Jun 2026 · 1:30 pm')
    expect(norm(formatDateTime('2026-06-09T13:30:45+08:00', 'Asia/Kuala_Lumpur')))
      .toBe('9 Jun 2026 · 1:30:45 pm')
  })

  it('drops a zero minute too: 5pm not 5:00pm', () => {
    expect(norm(formatDateTime('2026-06-09T17:00:00+08:00', 'Asia/Kuala_Lumpur')))
      .toBe('9 Jun 2026 · 5 pm')
    expect(norm(formatDateTime('2026-06-09T11:00:00+08:00', 'Asia/Kuala_Lumpur')))
      .toBe('9 Jun 2026 · 11 am')
  })

  it('no zone → renders browser-local, still no label', () => {
    expect(formatDateTime('2026-06-10T13:15:00Z')).not.toContain('(')
  })

  it('unparseable input is echoed back', () => {
    expect(formatDateTime('nope', 'UTC')).toBe('nope')
  })
})

describe('humanSchedule / humanCron (shared with the Tasks page)', () => {
  it('Spring 6-field cron → weekly phrase', () => {
    // sec min hour dom mon dow=2 (Tuesday) at 17:00
    expect(humanSchedule(task({ type: 'CRON', cronExpression: '0 0 17 * * 2' })))
      .toBe('weekly on Tue at 5 PM')
  })

  it('daily-at cron', () => {
    expect(humanCron('0 9 * * *')).toBe('daily at 9 AM')
  })

  it('@daily shortcut', () => {
    expect(humanCron('@daily')).toBe('daily at midnight')
  })

  it('renders "last <day> of the month" for both the 5L form and the 25-31 idiom', () => {
    // Canonical "<n>L" day-of-week form (what task_manager now emits), with "*"
    // and Quartz "?" day-of-month, 6- and 5-field.
    expect(humanCron('0 0 17 * * 5L')).toBe('last Friday of the month at 5 PM')
    expect(humanCron('0 0 17 ? * 5L')).toBe('last Friday of the month at 5 PM')
    expect(humanCron('0 17 * * 5L')).toBe('last Friday of the month at 5 PM')
    // Legacy lossy idiom (dom=25-31 + weekday) reads identically.
    expect(humanCron('0 0 17 25-31 * 5')).toBe('last Friday of the month at 5 PM')
    expect(humanCron('0 17 25-31 * 5')).toBe('last Friday of the month at 5 PM')
  })

  it('falls back to cronstrue for patterns the hand-rolled humanizer misses (never raw cron)', () => {
    // Day-of-month specific and "#" (Nth weekday) aren't hand-rolled, so they
    // come back humanized via cronstrue (JCLAW-438 item 4).
    expect(humanCron('0 0 9 1 * *')).toContain('day 1')
    expect(humanCron('0 0 17 ? * 6#3')).toContain('third Saturday')
  })

  it('applies the drop-zero rule to cronstrue times too: 9 AM not 09:00 AM', () => {
    const dayOne = humanCron('0 0 9 1 * *')!
    expect(dayOne).toContain('9 AM')
    expect(dayOne).not.toContain('09:00')
    // Non-zero minutes are kept.
    expect(humanCron('0 30 8 1 * *')).toContain('8:30 AM')
  })

  it('INTERVAL → every <duration>', () => {
    expect(humanSchedule(task({ type: 'INTERVAL', intervalSeconds: 1800 }))).toBe('every 30 min')
  })

  it('one-shot type falls back to scheduleDisplay then em-dash', () => {
    expect(humanSchedule(task({ type: 'SCHEDULED', scheduleDisplay: '2026-06-10T11:00' })))
      .toBe('2026-06-10T11:00')
    expect(humanSchedule(task({ type: 'SCHEDULED' }))).toBe('—')
  })

  it('humanDuration / formatTime12h', () => {
    expect(humanDuration(3600)).toBe('1 hour')
    expect(humanDuration(7200)).toBe('2 hours')
    expect(humanDuration(86_400)).toBe('1 day')
    expect(formatTime12h(17, 0)).toBe('5 PM')
    expect(formatTime12h(13, 15)).toBe('1:15 PM')
    expect(formatTime12h(0, 0)).toBe('12 AM')
  })
})
