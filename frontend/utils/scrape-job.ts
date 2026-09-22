import type { ScrapeJob, ScrapeJobState } from '~/types/api'

/**
 * Background scrape job helpers (JCLAW-1273), shared by the Scrapes page, its detail view, the
 * chat card and the dashboard, so a job reads the same everywhere.
 */

/** Words, not colours, carry the state: the badge's colour only repeats what its label says. */
export const SCRAPE_STATE_LABEL: Record<ScrapeJobState, string> = {
  PENDING: 'Waiting',
  RUNNING: 'Running',
  PAUSED: 'Paused',
  INTERRUPTED: 'Interrupted',
  SUCCEEDED: 'Finished',
  FAILED: 'Failed',
  CANCELLED: 'Stopped',
}

export const SCRAPE_STATE_BADGE: Record<ScrapeJobState, string> = {
  PENDING: 'bg-zinc-100 dark:bg-zinc-400/10 text-zinc-700 dark:text-zinc-300 border-zinc-300 dark:border-zinc-400/20',
  RUNNING: 'bg-blue-100 dark:bg-blue-400/10 text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-400/20',
  PAUSED: 'bg-amber-100 dark:bg-amber-400/10 text-amber-800 dark:text-amber-300 border-amber-300 dark:border-amber-400/20',
  INTERRUPTED: 'bg-orange-100 dark:bg-orange-400/10 text-orange-800 dark:text-orange-300 border-orange-300 dark:border-orange-400/20',
  SUCCEEDED: 'bg-emerald-100 dark:bg-emerald-400/10 text-emerald-700 dark:text-emerald-400 border-emerald-300 dark:border-emerald-400/20',
  FAILED: 'bg-red-100 dark:bg-red-400/10 text-red-700 dark:text-red-400 border-red-300 dark:border-red-400/20',
  CANCELLED: 'bg-zinc-100 dark:bg-zinc-400/10 text-zinc-700 dark:text-zinc-300 border-zinc-300 dark:border-zinc-400/20',
}

export const SCRAPE_STATES = Object.keys(SCRAPE_STATE_LABEL) as ScrapeJobState[]

/** Queued or running: a scheduler owns it, so its numbers are still moving. */
export function isActive(state: ScrapeJobState): boolean {
  return state === 'PENDING' || state === 'RUNNING'
}

/** Ended for good; nothing resumes it. */
export function isEnded(state: ScrapeJobState): boolean {
  return state === 'SUCCEEDED' || state === 'FAILED' || state === 'CANCELLED'
}

export const canPause = isActive

export function canResume(state: ScrapeJobState): boolean {
  return state === 'PAUSED' || state === 'INTERRUPTED'
}

export function canStop(state: ScrapeJobState): boolean {
  return !isEnded(state)
}

/** The server refuses to delete a job that is queued or running. */
export function canDelete(state: ScrapeJobState): boolean {
  return !isActive(state)
}

/**
 * Pages read against what the job can still reach: the smaller of what it has found and its page
 * limit, and never less than it has read, since a resumed or redirected crawl can pass either.
 */
export function scrapeProgress(job: Pick<ScrapeJob, 'pagesRead' | 'pagesDiscovered' | 'options'>): { done: number, total: number } {
  const reachable = Math.min(job.pagesDiscovered, job.options.maxPages)
  return { done: job.pagesRead, total: Math.max(job.pagesRead, reachable, 1) }
}

/** Host and path, without the scheme: what a row needs to say which site this is. */
export function scrapeSite(url: string): string {
  try {
    const parsed = new URL(url)
    const path = parsed.pathname === '/' ? '' : parsed.pathname
    return `${parsed.host}${path}`
  }
  catch {
    return url
  }
}

/** "1 h 5 min", "4 min 12 s", "38 s". */
export function formatRuntime(seconds: number): string {
  if (seconds < 60) return `${seconds} s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ${seconds % 60} s`
  return `${Math.floor(minutes / 60)} h ${minutes % 60} min`
}
