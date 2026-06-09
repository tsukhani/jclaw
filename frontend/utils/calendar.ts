import type { Task } from '~/types/api'

/**
 * Calendar fire projection (JCLAW-440), extracted from ScheduleCalendar.vue so
 * the cron expander — including the L/# day-of-week modifiers — is unit-testable
 * and shared by the Tasks and Reminders calendars.
 */

/** A single projected fire of a task within a calendar window. */
export interface ProjectedFire {
  taskId: number
  taskName: string
  taskType: string
  taskStatus: string
  agentName: string | null
  fireAt: Date
  isPast: boolean
  taskPaused: boolean
}

/**
 * Project every fire a task makes in the half-open window [from, to). CRON uses
 * {@link expandCron}; INTERVAL steps from nextRunAt by intervalSeconds;
 * SCHEDULED / IMMEDIATE pin to their single nextRunAt.
 */
export function projectFires(task: Task, from: Date, to: Date): ProjectedFire[] {
  const out: ProjectedFire[] = []
  const nowEpoch = Date.now()
  const base = {
    taskId: task.id,
    taskName: task.name,
    taskType: task.type,
    taskStatus: task.status,
    agentName: task.agentName,
    taskPaused: task.paused,
  }
  const push = (fireAt: Date) => {
    out.push({ ...base, fireAt, isPast: fireAt.getTime() < nowEpoch })
  }
  if (task.type === 'INTERVAL') {
    const secs = task.intervalSeconds as number | null | undefined
    const start = task.nextRunAt as string | null | undefined
    if (typeof secs === 'number' && secs > 0 && start) {
      let cursor = new Date(start)
      while (cursor.getTime() > to.getTime()) cursor = new Date(cursor.getTime() - secs * 1000)
      for (let i = 0; i < 500 && cursor.getTime() < to.getTime(); i++) {
        if (cursor.getTime() >= from.getTime()) push(new Date(cursor))
        cursor = new Date(cursor.getTime() + secs * 1000)
      }
    }
    return out
  }
  if (task.type === 'CRON') {
    const expr = task.cronExpression as string | null | undefined
    if (expr) {
      for (const f of expandCron(expr, from, to)) push(f)
    }
    else if (task.nextRunAt) {
      const d = new Date(task.nextRunAt)
      if (d >= from && d < to) push(d)
    }
    return out
  }
  // SCHEDULED / IMMEDIATE / one-shot — single fire on nextRunAt.
  if (task.nextRunAt) {
    const d = new Date(task.nextRunAt)
    if (d >= from && d < to) push(d)
  }
  return out
}

/**
 * Lightweight cron expander for the calendar grid. Handles ranges / steps /
 * lists plus the positional modifiers a humanized schedule can carry:
 *   - day-of-week "<n>L"   → the LAST <weekday> of the month ("0 0 17 * * 5L")
 *   - day-of-week "<n>#<k>" → the Kth <weekday> of the month ("0 0 9 * * 1#2")
 *   - day-of-month "L"      → the last calendar day of the month
 * Without these, "5L" matched every Friday (parseInt("5L") === 5). Iteration is
 * bounded (~60k minutes for a 42-day grid), with a 45-day cap as a guard.
 */
export function expandCron(expr: string, from: Date, to: Date): Date[] {
  const out: Date[] = []
  const trimmed = expr.trim()
  const shortcut: Record<string, string> = {
    '@hourly': '0 * * * *',
    '@daily': '0 0 * * *',
    '@midnight': '0 0 * * *',
    '@weekly': '0 0 * * 0',
    '@monthly': '0 0 1 * *',
    '@yearly': '0 0 1 1 *',
    '@annually': '0 0 1 1 *',
  }
  const expanded = shortcut[trimmed]
  if (expanded) return expandCron(expanded, from, to)

  const parts = trimmed.split(/\s+/)
  // Normalize to the 5-field [min, hour, dom, mon, dow]. A 6-field Spring cron
  // contributes its trailing 5 fields, but only when sec=0 (minute-resolution).
  let fields: string[] | null = null
  if (parts.length === 5) fields = parts
  else if (parts.length === 6 && parts[0] === '0') fields = parts.slice(1)
  if (!fields) return []
  const [min, hour, dom, mon, dow] = fields as [string, string, string, string, string]

  // Date-aware day modifiers — matchCronField only sees a single numeric field
  // value, so "last/Nth weekday" and "last day" need the whole calendar date.
  const lastDow = /^([0-7])L$/i.exec(dow) // "5L" → last <weekday> of the month
  const nthDow = /^([0-7])#([1-5])$/.exec(dow) // "5#3" → 3rd <weekday> of the month
  const domLast = /^L$/i.test(dom) // "L" → last calendar day of the month
  // Adding n days crosses into the next month ⇒ d sits in the final n-day window.
  const crossesMonth = (d: Date, n: number): boolean => {
    const next = new Date(d)
    next.setDate(d.getDate() + n)
    return next.getMonth() !== d.getMonth()
  }
  const matchDay = (d: Date): boolean => {
    // "<n>L" → the weekday with no later occurrence this month (last <weekday>).
    if (lastDow) {
      return d.getDay() === Number.parseInt(lastDow[1]!, 10) % 7 && crossesMonth(d, 7)
    }
    // "<n>#<k>" → the Kth occurrence of the weekday (day-of-month in k*7-6 .. k*7).
    if (nthDow) {
      return d.getDay() === Number.parseInt(nthDow[1]!, 10) % 7
        && Math.ceil(d.getDate() / 7) === Number.parseInt(nthDow[2]!, 10)
    }
    const domOk = domLast ? crossesMonth(d, 1) : matchCronField(dom, d.getDate(), 1, 31)
    return domOk && matchCronField(dow, d.getDay(), 0, 6)
  }

  const cap = Math.min(to.getTime(), from.getTime() + 45 * 24 * 60 * 60 * 1000)
  const cursor = new Date(from)
  cursor.setSeconds(0, 0)
  while (cursor.getTime() < cap) {
    if (matchCronField(min, cursor.getMinutes(), 0, 59)
      && matchCronField(hour, cursor.getHours(), 0, 23)
      && matchCronField(mon, cursor.getMonth() + 1, 1, 12)
      && matchDay(cursor)) {
      out.push(new Date(cursor))
    }
    cursor.setMinutes(cursor.getMinutes() + 1)
  }
  return out
}

/** Test whether a cron-field value matches the current numeric clock value. */
export function matchCronField(field: string, value: number, min: number, max: number): boolean {
  if (field === '*' || field === '?') return true
  for (const part of field.split(',')) {
    const stepMatch = /^(.+)\/(\d+)$/.exec(part)
    if (stepMatch) {
      const step = Number.parseInt(stepMatch[2]!, 10)
      const base = stepMatch[1]!
      const nonStarRange: [number, number] = base.includes('-')
        ? base.split('-').map(n => Number.parseInt(n, 10)) as [number, number]
        : [Number.parseInt(base, 10), max]
      const [lo, hi] = base === '*' ? [min, max] : nonStarRange
      if (value >= lo && value <= hi && (value - lo) % step === 0) return true
      continue
    }
    if (part.includes('-')) {
      const [lo, hi] = part.split('-').map(n => Number.parseInt(n, 10)) as [number, number]
      if (value >= lo && value <= hi) return true
      continue
    }
    if (Number.parseInt(part, 10) === value) return true
  }
  return false
}
