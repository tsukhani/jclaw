import { describe, it, expect } from 'vitest'
import type { INPMetricWithAttribution } from 'web-vitals/attribution'
import { routeAt, toInpReport, type RouteVisit } from '~/utils/web-vitals-report'

const visits: RouteVisit[] = [
  { at: 0, route: '/' },
  { at: 1_000, route: '/agents/:name?' },
  { at: 5_000, route: '/chat' },
]

describe('routeAt', () => {
  it('names the route that was current when the user interacted', () => {
    expect(routeAt(visits, 4_999)).toBe('/agents/:name?')
    expect(routeAt(visits, 5_000)).toBe('/chat')
  })

  it('does not credit a click to the page it navigated to', () => {
    // A click at t=4900 on the agents page lands its report after the router moved to /chat.
    expect(routeAt(visits, 4_900)).toBe('/agents/:name?')
  })

  it('falls back to the current route when the interaction time is unknown', () => {
    expect(routeAt(visits, undefined)).toBe('/chat')
  })

  it('never returns an empty route', () => {
    expect(routeAt([], 10)).toBe('unknown')
    expect(routeAt(visits, -1)).toBe('/')
  })
})

function metric(value: number, attribution: Partial<INPMetricWithAttribution['attribution']>) {
  return {
    name: 'INP',
    value,
    attribution: { inputDelay: 0, processingDuration: 0, presentationDelay: 0, ...attribution },
  } as unknown as INPMetricWithAttribution
}

describe('toInpReport', () => {
  it('rounds the durations and carries the attribution the backend logs', () => {
    const report = toInpReport(metric(312.4, {
      interactionType: 'keyboard',
      interactionTarget: 'textarea#composer',
      inputDelay: 12.3,
      processingDuration: 249.6,
      presentationDelay: 50.5,
    }), '/chat')
    expect(report).toEqual({
      name: 'INP',
      value: 312,
      route: '/chat',
      interactionType: 'keyboard',
      interactionTarget: 'textarea#composer',
      inputDelay: 12,
      processingDuration: 250,
      presentationDelay: 51,
    })
  })

  it('sends null rather than dropping a field the browser did not attribute', () => {
    const report = toInpReport(metric(40, {}), '/')
    expect(report.interactionType).toBeNull()
    expect(report.interactionTarget).toBeNull()
  })
})
