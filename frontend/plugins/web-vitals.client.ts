// Field INP for the operator's own sessions; a lab trace measures only the interactions someone
// scripted. The SPA never reloads, so reportAllChanges sends each interaction that raises INP,
// tagged with its route, rather than one value at page hide that could belong to any page.

import { onINP } from 'web-vitals/attribution'
import { ROUTE_HISTORY_LIMIT, WEB_VITALS_URL, routeAt, toInpReport, type RouteVisit } from '~/utils/web-vitals-report'

const UNREPORTED = new Set(['/login', '/setup-password'])

export default defineNuxtPlugin(() => {
  // Playwright and the DevTools MCP both set it: an e2e run wrote 48 rows into the field data.
  if (navigator.webdriver) return
  const router = useRouter()
  const pattern = () => {
    const r = router.currentRoute.value
    return r.matched.at(-1)?.path ?? r.path
  }
  const history: RouteVisit[] = [{ at: 0, route: pattern() }]
  router.afterEach(() => {
    history.push({ at: performance.now(), route: pattern() })
    if (history.length > ROUTE_HISTORY_LIMIT) history.shift()
  })

  onINP((metric) => {
    const route = routeAt(history, metric.attribution.interactionTime)
    if (UNREPORTED.has(route)) return
    const body = new Blob([JSON.stringify(toInpReport(metric, route))], { type: 'application/json' })
    // sendBeacon rather than useApiMutation: it still delivers while the page is being hidden.
    navigator.sendBeacon(WEB_VITALS_URL, body)
  }, { reportAllChanges: true })
})
