import cronstrue from 'cronstrue'
import type { Task } from '~/types/api'

/**
 * Schedule + fire-time formatting shared by the Tasks and Reminders pages.
 * The cron/interval humanizers were lifted verbatim out of pages/tasks.vue
 * (JCLAW-294 era) so both list pages render schedules identically instead of
 * keeping two divergent copies of the ~50-line cron parser. The two
 * reminder-facing formatters (absolute datetime + relative countdown) are new.
 *
 * Nuxt auto-imports `utils/`, so call sites use these without an explicit
 * import (matching utils/task-steps.ts, utils/format.ts, etc.).
 */

/**
 * Humanize a Task's recurring schedule for display. Order of preference:
 *   1. Recognized cron pattern (daily at 9 AM, every 30 min, weekdays at...)
 *   2. INTERVAL duration humanized (every 30 min, every 2 hours, every 1 day)
 *   3. Server's scheduleDisplay — the operator's raw input verbatim
 *   4. em-dash if nothing applies (one-shot / unscheduled tasks)
 */
export function humanSchedule(task: Task): string {
  if (task.type === 'INTERVAL') {
    const secs = task.intervalSeconds as number | null | undefined
    if (typeof secs === 'number' && secs > 0) return `every ${humanDuration(secs)}`
    return (task.scheduleDisplay as string | null) || '—'
  }
  if (task.type === 'CRON') {
    const expr = task.cronExpression as string | null | undefined
    if (expr) {
      const h = humanCron(expr)
      if (h) return h
    }
    return (task.scheduleDisplay as string | null) || expr || '—'
  }
  return (task.scheduleDisplay as string | null) || '—'
}

export function humanDuration(secs: number): string {
  if (secs % 86400 === 0) {
    const d = secs / 86400
    return d === 1 ? '1 day' : `${d} days`
  }
  if (secs % 3600 === 0) {
    const h = secs / 3600
    return h === 1 ? '1 hour' : `${h} hours`
  }
  if (secs % 60 === 0) {
    const m = secs / 60
    return m === 1 ? '1 min' : `${m} min`
  }
  return `${secs}s`
}

/**
 * Recognize common cron patterns and return a natural-language equivalent.
 * Returns null for patterns we don't know — caller falls back to the
 * server's scheduleDisplay so the operator at least sees their raw input.
 */
export function humanCron(expr: string): string | null {
  const trimmed = expr.trim()
  switch (trimmed) {
    case '@hourly': return 'hourly'
    case '@daily':
    case '@midnight': return 'daily at midnight'
    case '@weekly': return 'weekly on Sunday at midnight'
    case '@monthly': return 'monthly on the 1st at midnight'
    case '@yearly':
    case '@annually': return 'yearly on Jan 1 at midnight'
  }
  const parts = trimmed.split(/\s+/)
  let sec: string, min: string, hour: string, dom: string, mon: string, dow: string
  if (parts.length === 6) {
    // We know all six indexes exist after the length check.
    [sec, min, hour, dom, mon, dow] = parts as [string, string, string, string, string, string]
  }
  else if (parts.length === 5) {
    [min, hour, dom, mon, dow] = parts as [string, string, string, string, string]
    sec = '0'
  }
  else return null
  const dailyWildcards = dom === '*' && mon === '*' && dow === '*'
  if (sec === '0' && dailyWildcards) {
    if (min.startsWith('*/') && hour === '*') {
      const n = Number.parseInt(min.slice(2), 10)
      if (!Number.isNaN(n)) return `every ${n} min`
    }
    if (min === '0' && hour.startsWith('*/')) {
      const n = Number.parseInt(hour.slice(2), 10)
      if (!Number.isNaN(n)) {
        const unit = n === 1 ? '1 hour' : `${n} hours`
        return `every ${unit}`
      }
    }
    if (/^\d+$/.test(min) && /^\d+$/.test(hour)) {
      return `daily at ${formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))}`
    }
  }
  if (sec === '0' && dom === '*' && mon === '*' && /^\d+$/.test(min) && /^\d+$/.test(hour)) {
    if (dow === '1-5' || /^MON-FRI$/i.test(dow)) {
      return `weekdays at ${formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))}`
    }
    const dowNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']
    if (/^\d+$/.test(dow)) {
      const d = Number.parseInt(dow, 10)
      if (d >= 0 && d <= 6) return `weekly on ${dowNames[d]} at ${formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))}`
    }
  }
  // "Last <weekday> of the month" — two equivalent spellings the UI should read
  // identically (cronstrue renders both verbosely). Full weekday names here
  // because "last Friday of the month" reads more naturally than the weekly form:
  //   • "<n>L" in the day-of-week field — the canonical form (e.g. "0 0 17 * * 5L"),
  //     what task_manager now emits; dom is "*" or Quartz "?".
  //   • dom=25-31 + a single weekday — the lossy idiom older tasks may carry; a
  //     weekday in the final week (day 25-31) occurs at most once a month.
  if (sec === '0' && mon === '*' && /^\d+$/.test(min) && /^\d+$/.test(hour)) {
    const fullDow = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    const at = formatTime12h(Number.parseInt(hour, 10), Number.parseInt(min, 10))
    const lDay = /^([0-7])L$/i.exec(dow)?.[1]
    if ((dom === '*' || dom === '?') && lDay !== undefined) {
      return `last ${fullDow[Number.parseInt(lDay, 10) % 7]} of the month at ${at}`
    }
    if (dom === '25-31' && /^\d+$/.test(dow)) {
      const d = Number.parseInt(dow, 10)
      if (d >= 0 && d <= 6) return `last ${fullDow[d]} of the month at ${at}`
    }
  }
  // Anything the hand-rolled patterns above don't recognize — day-of-month
  // specifics, L/# modifiers ("last Friday of the month", "third Saturday"),
  // ranges/lists — delegate to cronstrue so a UI view never shows a raw cron
  // expression (JCLAW-438 item 4). cronstrue auto-detects the 6-field Spring
  // form (seconds first). Returns null only on a genuinely invalid expression,
  // in which case humanSchedule falls back to the operator's scheduleDisplay.
  try {
    return tidyCronstrueTimes(cronstrue.toString(trimmed, { throwExceptionOnParseError: true }))
  }
  catch {
    return null
  }
}

export function formatTime12h(hour: number, min: number): string {
  const period = hour >= 12 ? 'PM' : 'AM'
  const h12 = hour % 12 || 12
  const mm = min === 0 ? '' : `:${min.toString().padStart(2, '0')}`
  return `${h12}${mm} ${period}`
}

/**
 * Render a 12-hour wall-clock time dropping a zero minute and zero second
 * (JCLAW-438): 5pm → "5 PM", 1:15pm → "1:15 PM", 1:15:45pm → "1:15:45 PM".
 * The minute is kept whenever seconds show, so "5:00:30" stays well-formed.
 * The am/pm token passes through verbatim so its case matches the caller's
 * surrounding style (cronstrue emits "PM"; formatDateTime emits "pm").
 */
function formatClock(hour: number, minute: number, second: number, period: string): string {
  const showSeconds = second !== 0
  const showMinutes = minute !== 0 || showSeconds
  let out = String(hour)
  if (showMinutes) out += `:${String(minute).padStart(2, '0')}`
  if (showSeconds) out += `:${String(second).padStart(2, '0')}`
  return period ? `${out} ${period}` : out
}

/**
 * cronstrue renders times zero-padded with an explicit :MM ("At 05:00 PM, …").
 * Apply the same drop-zero rule to every time token in its output so the cron
 * fallback reads "At 5 PM, …" like the hand-rolled phrasings.
 */
function tidyCronstrueTimes(s: string): string {
  return s.replaceAll(/\b(\d{1,2}):(\d{2})(?::(\d{2}))?\s+(AM|PM)\b/g,
    (_m, h, mm, ss, ap) => formatClock(
      Number.parseInt(h, 10), Number.parseInt(mm, 10), ss ? Number.parseInt(ss, 10) : 0, ap))
}

/**
 * Absolute wall-clock datetime, rendered in the app's effective IANA zone
 * (resolved server-side onto Task.effectiveTimezone). Produces e.g.
 * "10 Jun 2026 · 1:15 pm" — day-first (en-GB, the app's locale convention),
 * 12-hour so am/pm is stable regardless of OS locale. The zone is applied for
 * rendering but NOT labelled — the configured timezone is implicit from
 * Settings (JCLAW-438 item 1). A zero minute and/or second is dropped:
 * "5:00:00 pm" → "5 pm", "1:30:00 pm" → "1:30 pm", "1:30:45 pm" stays. A
 * null/blank zone falls back to the browser's local zone. Shared by the
 * Tasks (Next Run) and Reminders (When / Fired) columns.
 */
export function formatDateTime(iso: string, zone?: string | null): string {
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return iso
  const d = new Date(t)
  const opts: Intl.DateTimeFormatOptions = zone ? { timeZone: zone } : {}
  const date = d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', ...opts })
  // Pull the clock parts in the target zone (so the minute is correct even for
  // half-hour-offset zones) and apply the drop-zero rule via formatClock.
  const parts = new Intl.DateTimeFormat('en-GB', {
    hour: 'numeric', minute: '2-digit', second: '2-digit', hour12: true, ...opts,
  }).formatToParts(d)
  const part = (type: string) => parts.find(p => p.type === type)?.value ?? ''
  const time = formatClock(
    Number.parseInt(part('hour') || '0', 10),
    Number.parseInt(part('minute') || '0', 10),
    Number.parseInt(part('second') || '0', 10),
    part('dayPeriod').toLowerCase())
  return `${date} · ${time}`
}

/**
 * Relative countdown to a fire instant — "in 4 days", "in 23 hours",
 * "in 3 hours", "in 5 minutes", "in 30 seconds". Always relative (never an
 * absolute date) so the Reminders When column reads consistently however far
 * out the fire is. The unit steps up at each natural boundary; cascade rounding
 * (e.g. 59.6 min) rolls into the next unit cleanly. Already-elapsed → "past
 * due"; an unparseable input is echoed back so a bad value is visible, not
 * silently blanked. `nowMs` is passed in so the caller's reactive clock tick
 * drives re-render.
 */
export function timeUntil(iso: string, nowMs: number): string {
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return iso
  const deltaMs = t - nowMs
  if (deltaMs <= 0) return 'past due'
  const sec = Math.round(deltaMs / 1000)
  if (sec < 60) return relative(sec, 'second')
  const min = Math.round(sec / 60)
  if (min < 60) return relative(min, 'minute')
  const hr = Math.round(min / 60)
  if (hr < 24) return relative(hr, 'hour')
  const day = Math.round(hr / 24)
  return relative(day, 'day')
}

function relative(n: number, unit: string): string {
  return `in ${n} ${unit}${n === 1 ? '' : 's'}`
}
