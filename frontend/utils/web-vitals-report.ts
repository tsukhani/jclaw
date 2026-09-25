import type { INPMetricWithAttribution } from 'web-vitals/attribution'

/** Receives the SPA's INP reports; see ApiMetricsController.webVitals. */
export const WEB_VITALS_URL = '/api/metrics/web-vitals'

export interface InpReport {
  name: 'INP'
  value: number
  route: string
  interactionType: string | null
  interactionTarget: string | null
  inputDelay: number
  processingDuration: number
  presentationDelay: number
}

/** A route pattern (`/agents/:name?`, not `/agents/main`) and when it became current. */
export interface RouteVisit {
  at: DOMHighResTimeStamp
  route: string
}

/** How many visits to keep: enough to resolve any interaction a report can still arrive for. */
export const ROUTE_HISTORY_LIMIT = 20

/**
 * The route the user was on when they interacted. The report arrives after the next paint,
 * by which time a click on a link has already moved the router on, so the current route would
 * name the destination rather than the page the target lived on.
 */
export function routeAt(history: readonly RouteVisit[], time: DOMHighResTimeStamp | undefined): string {
  if (history.length === 0) return 'unknown'
  if (time === undefined) return history.at(-1)!.route
  let found = history[0]!.route
  for (const v of history) {
    if (v.at > time) break
    found = v.route
  }
  return found
}

export function toInpReport(metric: INPMetricWithAttribution, route: string): InpReport {
  const a = metric.attribution
  return {
    name: 'INP',
    value: Math.round(metric.value),
    route,
    interactionType: a.interactionType ?? null,
    interactionTarget: a.interactionTarget ?? null,
    inputDelay: Math.round(a.inputDelay),
    processingDuration: Math.round(a.processingDuration),
    presentationDelay: Math.round(a.presentationDelay),
  }
}
