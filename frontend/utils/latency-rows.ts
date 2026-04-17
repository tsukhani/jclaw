/**
 * Row-building helpers for the dashboard Chat Performance panel.
 *
 * Two separate shapes come out of the same `/api/metrics/latency` payload:
 *
 * - **Table rows** — request-lifetime order, with `prologue_*` child
 *   histograms nested directly under the `prologue` parent (`isChild: true`).
 *   Unknown keys still surface so data never silently disappears.
 *
 * - **Chart series** — top-level segments only. `prologue_*` children are
 *   suppressed because their contribution is already represented by the
 *   `Prologue` line they sum to; plotting them separately clutters the
 *   overlay without adding information.
 *
 * Kept as a pure helper so the logic is unit-testable without mounting
 * the dashboard.
 */

export interface LatencyHistogram {
  count: number
  p50?: number
  p90?: number
  p99?: number
  p999?: number
  min?: number
  max?: number
}

export interface LatencyRow {
  key: string
  label: string
  h: LatencyHistogram
  /** True when this row is a `prologue_*` child decomposition. Drives indentation. */
  isChild: boolean
}

/** Canonical top-level segment order; matches SEGMENT_ORDER in index.vue. */
export const TOP_LEVEL_ORDER = [
  'queue_wait',
  'prologue',
  'ttft',
  'stream_body',
  'tool_exec',
  'tool_round_count',
  'persist',
  'total',
] as const

export const TOP_LEVEL_LABELS: Record<string, string> = {
  queue_wait: 'Queue wait',
  prologue: 'Prologue',
  ttft: 'Time to first token',
  stream_body: 'Stream body',
  tool_exec: 'Tool execution',
  persist: 'Persist',
  total: 'Total',
  tool_round_count: 'Tool rounds / turn',
}

/**
 * Known `prologue_*` children. Their order in the table follows this array.
 * Unknown `prologue_*` keys (not in this list) still render as children but
 * after the known ones, with their raw suffix as a fallback label.
 */
export const PROLOGUE_CHILDREN_ORDER = [
  'prologue_parse',
  'prologue_conv',
  'prologue_tools',
  'prologue_prompt',
] as const

export const PROLOGUE_CHILD_LABELS: Record<string, string> = {
  prologue_parse: 'Parse',
  prologue_conv: 'Conversation',
  prologue_tools: 'Tools',
  prologue_prompt: 'Prompt',
}

function isPrologueChildKey(key: string): boolean {
  return key.startsWith('prologue_')
}

function labelForChild(key: string): string {
  return PROLOGUE_CHILD_LABELS[key] ?? key.replace(/^prologue_/, '')
}

function hasSamples(h: LatencyHistogram | undefined | null): h is LatencyHistogram {
  return !!h && typeof h.count === 'number' && h.count > 0
}

/**
 * Build the flat row list the table renders. `prologue_*` rows are emitted
 * immediately after the `prologue` row (if present), in PROLOGUE_CHILDREN_ORDER
 * followed by any unknown prologue_* keys in encounter order.
 */
export function buildLatencyRows(
  metrics: Record<string, LatencyHistogram | undefined>,
): LatencyRow[] {
  const rows: LatencyRow[] = []
  const seen = new Set<string>()

  const emitChild = (key: string) => {
    const h = metrics[key]
    if (!hasSamples(h)) return
    rows.push({ key, label: labelForChild(key), h, isChild: true })
    seen.add(key)
  }

  for (const key of TOP_LEVEL_ORDER) {
    const h = metrics[key]
    const parentEmitted = hasSamples(h)
    if (parentEmitted) {
      rows.push({ key, label: TOP_LEVEL_LABELS[key] ?? key, h, isChild: false })
      seen.add(key)
    }
    // Nest prologue children immediately under the parent — only when the
    // parent was actually emitted. If prologue is absent, any stray
    // prologue_* keys fall through to the unknown-key catch-all below so
    // the operator still sees the data instead of it silently disappearing.
    if (key === 'prologue' && parentEmitted) {
      for (const child of PROLOGUE_CHILDREN_ORDER) emitChild(child)
      // Unknown prologue_* keys (future-proofing) after known ones.
      for (const [mk, mh] of Object.entries(metrics)) {
        if (seen.has(mk) || !isPrologueChildKey(mk)) continue
        if (!hasSamples(mh)) continue
        rows.push({ key: mk, label: labelForChild(mk), h: mh, isChild: true })
        seen.add(mk)
      }
    }
  }

  // Any other unknown key the backend emits (non-prologue_* that's not in the
  // canonical order) — append at the bottom so data never silently disappears.
  for (const [key, h] of Object.entries(metrics)) {
    if (seen.has(key) || !hasSamples(h)) continue
    rows.push({ key, label: key, h, isChild: false })
  }

  return rows
}

/**
 * Build the series list the overlay chart consumes — top-level rows only.
 * Suppresses `prologue_*` children to avoid double-plotting the prologue sum.
 */
export function buildChartSeries(
  metrics: Record<string, LatencyHistogram | undefined>,
): Array<{ key: string; label: string; histogram: LatencyHistogram }> {
  return buildLatencyRows(metrics)
    .filter(r => !r.isChild)
    .map(r => ({ key: r.key, label: r.label, histogram: r.h }))
}
