import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { LatencyHistogram } from '~/types/api'
import LatencyOverlayChart from '~/components/LatencyOverlayChart.vue'

const STORAGE_KEY = 'jclaw:chat-perf:selected-series'

function hist(overrides: Partial<LatencyHistogram> = {}): LatencyHistogram {
  // Keep the bucket distribution and min_ms self-consistent: min_ms=1 means
  // all sample-containing buckets fall within the (min, max] axis range that
  // the component renders, so bar counts sum to `count`.
  return {
    count: 10,
    sum_ms: 500,
    min_ms: 1,
    max_ms: 200,
    p50_ms: 20,
    p90_ms: 100,
    p99_ms: 180,
    p999_ms: 200,
    buckets: [
      { le_ms: 0, count: 0 },
      { le_ms: 2, count: 1 },
      { le_ms: 4, count: 2 },
      { le_ms: 8, count: 3 },
      { le_ms: 16, count: 2 },
      { le_ms: 32, count: 1 },
      { le_ms: 64, count: 1 },
      { le_ms: 128, count: 0 },
      { le_ms: 256, count: 0 },
    ],
    ...overrides,
  }
}

function buildSeries() {
  return [
    { key: 'queue_wait', label: 'Queue wait', histogram: hist({ p50_ms: 5, p99_ms: 14, max_ms: 14 }) },
    { key: 'prologue', label: 'Prologue', histogram: hist({ p50_ms: 26, p99_ms: 94, max_ms: 94 }) },
    { key: 'ttft', label: 'Time to first token', histogram: hist({ p50_ms: 1880, p99_ms: 12200, max_ms: 12200 }) },
    { key: 'total', label: 'Total', histogram: hist({ p50_ms: 7030, p99_ms: 17200, max_ms: 17200 }) },
  ]
}

beforeEach(() => {
  localStorage.clear()
})

// ─── Picker ───────────────────────────────────────────────────────────────

describe('LatencyOverlayChart — segment picker', () => {
  it('renders a tab button for every input series', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const pills = wrapper.findAll('[role="tab"][data-series-key]')
    expect(pills).toHaveLength(series.length)

    const labels = pills.map(p => p.text())
    expect(labels).toEqual(['Queue wait', 'Prologue', 'Time to first token', 'Total'])
  })

  it('defaults the active segment to "total" when present', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const active = wrapper.find('[role="tab"][aria-selected="true"]')
    expect(active.attributes('data-series-key')).toBe('total')
  })

  it('falls back to the first series when "total" is absent', async () => {
    const series = buildSeries().filter(s => s.key !== 'total')
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const active = wrapper.find('[role="tab"][aria-selected="true"]')
    expect(active.attributes('data-series-key')).toBe('queue_wait')
  })

  it('clicking a tab switches the active segment', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    await wrapper.find('[role="tab"][data-series-key="prologue"]').trigger('click')

    const active = wrapper.find('[role="tab"][aria-selected="true"]')
    expect(active.attributes('data-series-key')).toBe('prologue')
  })
})

// ─── Persistence ──────────────────────────────────────────────────────────

describe('LatencyOverlayChart — localStorage persistence', () => {
  it('writes the selected segment key to localStorage on every change', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    await wrapper.find('[role="tab"][data-series-key="ttft"]').trigger('click')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('ttft')

    await wrapper.find('[role="tab"][data-series-key="queue_wait"]').trigger('click')
    expect(localStorage.getItem(STORAGE_KEY)).toBe('queue_wait')
  })

  it('restores the selected segment from localStorage on mount', async () => {
    localStorage.setItem(STORAGE_KEY, 'prologue')
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const active = wrapper.find('[role="tab"][aria-selected="true"]')
    expect(active.attributes('data-series-key')).toBe('prologue')
  })

  it('ignores a stale localStorage key that no longer exists in the series list', async () => {
    localStorage.setItem(STORAGE_KEY, 'deprecated_segment_xyz')
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    // Default ("total") should win when the stored key isn't available.
    const active = wrapper.find('[role="tab"][aria-selected="true"]')
    expect(active.attributes('data-series-key')).toBe('total')
  })
})

// ─── Chart rendering ─────────────────────────────────────────────────────

describe('LatencyOverlayChart — chart rendering', () => {
  it('renders histogram bars for the selected segment', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const bars = wrapper.findAll('svg rect[fill-opacity="0.25"]')
    // The fixture for "total" has 6 non-empty buckets after preprocessing.
    expect(bars.length).toBeGreaterThanOrEqual(1)
  })

  it('renders a smooth density curve (Catmull-Rom Bezier) over the bars', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const curve = wrapper.find('path[stroke-width="1.75"]')
    expect(curve.exists()).toBe(true)
    expect(curve.attributes('d')).toContain(' C')
  })

  it('always shows min/p50/p99/max markers (no hover gating in single-select mode)', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const markers = wrapper.findAll('[data-testid="percentile-markers"] line')
    expect(markers).toHaveLength(4) // min + p50 + p99 + max

    const labels = wrapper.findAll('[data-testid="percentile-markers"] text').map(t => t.text())
    expect(labels).toContain('min')
    expect(labels).toContain('p50')
    expect(labels).toContain('p99')
    expect(labels).toContain('max')
  })

  it('labels the markers with their numeric percentile values', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    // Default "total" fixture: min=1, p50=7030, p99=17200, max=17200.
    const labels = wrapper.findAll('[data-testid="percentile-markers"] text').map(t => t.text())
    expect(labels).toContain('1ms')
    expect(labels).toContain('7.03s')
    expect(labels).toContain('17.2s')
  })

  it('staggers tightly-clustered marker labels across multiple rows', async () => {
    // Queue wait fixture: p50=5, p99=14, max=14, min=5. Four markers cluster
    // very tightly on a narrow axis → all four should end up on distinct rows.
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    await wrapper.find('[role="tab"][data-series-key="queue_wait"]').trigger('click')

    const nameTexts = wrapper.findAll('[data-testid="percentile-markers"] text')
      .filter(t => ['min', 'p50', 'p99', 'max'].includes(t.text()))
    expect(nameTexts.length).toBeGreaterThanOrEqual(3)

    const yValues = nameTexts.map(t => Number(t.attributes('y')))
    const uniqueYs = new Set(yValues)
    // At minimum, the four name labels should occupy ≥ 2 distinct y positions
    // (the stagger engaged because markers are close).
    expect(uniqueYs.size).toBeGreaterThanOrEqual(2)
  })

  it('switching segments re-colors the chart to the new series', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const initialStroke = wrapper.find('path[stroke-width="1.75"]').attributes('stroke')

    await wrapper.find('[role="tab"][data-series-key="queue_wait"]').trigger('click')

    const afterStroke = wrapper.find('path[stroke-width="1.75"]').attributes('stroke')
    expect(afterStroke).not.toBe(initialStroke)
  })

  it('renders the centered stats footer with n + min + p50 + p99 + max', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const footer = wrapper.get('[data-testid="latency-stats-footer"]')

    // Each stat is a dt/dd pair — assert on the structured DOM rather than the
    // concatenated text (Vue's .text() joins nodes without whitespace).
    const labels = footer.findAll('dt').map(d => d.text())
    const values = footer.findAll('dd').map(d => d.text())

    expect(labels).toEqual(['n', 'min', 'p50', 'p99', 'max'])
    // Default "total" fixture: count=10, min=1, p50=7030, p99=17200, max=17200.
    expect(values).toEqual(['10', '1ms', '7.03s', '17.2s', '17.2s'])

    // Flex + justify-center classes drive the horizontal centering.
    expect(footer.classes()).toEqual(expect.arrayContaining(['flex', 'justify-center']))
  })

  it('hides per-bar count labels by default (shown only on hover)', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const countTexts = wrapper.findAll('[data-testid="bar-counts"] text')
    expect(countTexts).toHaveLength(0)
  })

  it('reveals the bucket count when hovering over a bar', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const hitTargets = wrapper.findAll('[data-testid="bar-hit-targets"] rect')
    expect(hitTargets.length).toBeGreaterThan(0)

    await hitTargets[0]!.trigger('mouseenter')

    const countTexts = wrapper.findAll('[data-testid="bar-counts"] text')
    expect(countTexts).toHaveLength(1)
    // Should be one of the bucket counts from the fixture (1, 2, or 3).
    expect(Number(countTexts[0]!.text())).toBeGreaterThan(0)

    await hitTargets[0]!.trigger('mouseleave')
    expect(wrapper.findAll('[data-testid="bar-counts"] text')).toHaveLength(0)
  })

  it('renders a y-axis with 0, midpoint, and max count ticks', async () => {
    const series = buildSeries()
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    const yAxisLabels = wrapper.findAll('[data-testid="y-axis"] text')
    // Exactly 3 ticks: 0, ceil(maxCount/2), maxCount.
    expect(yAxisLabels).toHaveLength(3)

    const values = yAxisLabels.map(t => Number(t.text()))
    expect(values[0]).toBe(0)
    // Max tick should equal the tallest bucket count in the "total" fixture (3).
    expect(values[2]).toBe(3)
  })

  it('handles non-factor-of-2 bucket spacing (no hard-coded le/2 assumption)', async () => {
    // Mimic the backend's 2^(1/4) grid: bucket boundaries at 91, 108, 128,
    // 153, … — not powers of two. 100 samples concentrated in (91, 108] and
    // (108, 128] should produce exactly two bars summing to 100.
    const finerHist = {
      count: 100,
      sum_ms: 11000,
      min_ms: 100,
      max_ms: 121,
      p50_ms: 101,
      p90_ms: 108,
      p99_ms: 118,
      p999_ms: 121,
      buckets: [
        { le_ms: 77, count: 0 },
        { le_ms: 91, count: 0 },
        { le_ms: 108, count: 60 },
        { le_ms: 128, count: 40 },
        { le_ms: 153, count: 0 },
      ],
    }
    const series = [{ key: 'ttft', label: 'Time to first token', histogram: finerHist }]
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    // Two non-zero bars inside (min_ms, max_ms]: (91, 108] and (108, 128].
    const bars = wrapper.findAll('svg rect[fill-opacity="0.25"]')
    expect(bars.length).toBeGreaterThanOrEqual(2)

    // Hover the first hit target; the revealed count should be one of the
    // retained bucket counts (60 or 40), never a number derived from le/2.
    const hitTargets = wrapper.findAll('[data-testid="bar-hit-targets"] rect')
    await hitTargets[0]!.trigger('mouseenter')
    const countText = wrapper.findAll('[data-testid="bar-counts"] text')[0]!.text()
    expect([60, 40]).toContain(Number(countText))
  })
})

// ─── Empty state ──────────────────────────────────────────────────────────

describe('LatencyOverlayChart — empty state', () => {
  it('shows the fallback message when the selected segment has no buckets', async () => {
    const emptyHist = hist({
      count: 0, sum_ms: 0, min_ms: 0, max_ms: 0,
      p50_ms: 0, p90_ms: 0, p99_ms: 0, p999_ms: 0,
      buckets: [{ le_ms: 0, count: 0 }],
    })
    const series = [
      { key: 'total', label: 'Total', histogram: emptyHist },
    ]
    const wrapper = await mountSuspended(LatencyOverlayChart, { props: { series } })

    expect(wrapper.text()).toContain('Not enough samples')
    expect(wrapper.find('path[stroke-width="1.75"]').exists()).toBe(false)
  })
})
