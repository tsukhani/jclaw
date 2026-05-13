<script setup lang="ts">
import type { LatencyHistogram } from '~/types/api'

interface SeriesInput {
  key: string
  label: string
  histogram: LatencyHistogram
}

const props = withDefaults(defineProps<{
  series: SeriesInput[]
  storageKey?: string
}>(), {
  storageKey: 'jclaw:chat-perf:selected-series',
})

// Hand-picked palette — Tailwind-400-equivalent HSL tones readable on both
// light and dark backgrounds. Index is by series position, so each segment
// keeps a stable color across sessions.
const PALETTE = [
  'hsl(160 84% 52%)', // emerald
  'hsl(199 89% 58%)', // sky
  'hsl(38 92% 55%)', // amber
  'hsl(258 85% 66%)', // violet
  'hsl(350 82% 60%)', // rose
  'hsl(173 70% 52%)', // teal
  'hsl(328 86% 65%)', // pink
  'hsl(215 20% 65%)', // slate
]
function colorForIndex(i: number): string {
  return PALETTE[((i % PALETTE.length) + PALETTE.length) % PALETTE.length]!
}
function colorFor(key: string): string {
  return colorForIndex(props.series.findIndex(s => s.key === key))
}

// ── Selected segment state ──────────────────────────────────────────────────
// Default: 'total' if present (most useful single-glance metric), else the
// first series the parent supplies. Overridden from localStorage on mount.
// With JCLAW-102 the channel is picked by a dropdown in the panel header;
// the series arriving here are already scoped to one channel, so keys are
// flat segment names like "total" / "ttft".
function defaultKey(): string {
  return props.series.find(s => s.key === 'total')?.key
    ?? props.series[0]?.key
    ?? ''
}

const selectedKey = ref<string>(defaultKey())

onMounted(() => {
  try {
    const raw = localStorage.getItem(props.storageKey)
    if (!raw) return
    if (props.series.some(s => s.key === raw)) selectedKey.value = raw
  }
  catch {
    /* SSR / privacy mode — stick with default */
  }
})

function select(key: string) {
  if (!props.series.some(s => s.key === key)) return
  selectedKey.value = key
  hoveredBarIdx.value = null
  try {
    localStorage.setItem(props.storageKey, key)
  }
  catch { /* ignore */ }
}

// ── Geometry ────────────────────────────────────────────────────────────────
const VIEWBOX_W = 640
const VIEWBOX_H = 240
const PAD_L = 32 // room for y-axis count labels (up to 4 digits)
const PAD_R = 14
const PAD_T = 68 // room for up to 4 rows of percentile labels (16px row pitch)
const PAD_B = 26

// Which bar (if any) is currently under the user's cursor — drives the
// hover-revealed count label and a subtle highlight on the bar itself.
const hoveredBarIdx = ref<number | null>(null)

function formatMs(ms: number): string {
  if (ms <= 0) return '0 ms'
  if (ms < 1) return '<1ms'
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)}s`
}

interface Bar {
  x1: number
  x2: number
  y: number
  count: number
  /** Center x for the count label */
  labelX: number
}
interface YTick {
  value: number
  y: number
}
interface PercentileMarker {
  x: number
  label: string
  value: number
  valueLabel: string
  /** y for the name label ("p50", "p99", "max") */
  nameY: number
  /** y for the value label (e.g. "1.00s") */
  valueY: number
}

/**
 * Catmull-Rom through `pts` with tension 0.5, rendered as cubic Beziers.
 * Produces the smooth density curve over the histogram bars.
 */
function catmullRomPath(pts: Array<{ x: number, y: number }>): string {
  if (pts.length === 0) return ''
  if (pts.length === 1) return `M${pts[0]!.x.toFixed(1)},${pts[0]!.y.toFixed(1)}`
  let d = `M${pts[0]!.x.toFixed(1)},${pts[0]!.y.toFixed(1)}`
  for (let i = 0; i < pts.length - 1; i++) {
    const p0 = pts[Math.max(0, i - 1)]!
    const p1 = pts[i]!
    const p2 = pts[i + 1]!
    const p3 = pts[Math.min(pts.length - 1, i + 2)]!
    const cp1x = p1.x + (p2.x - p0.x) / 6
    const cp1y = p1.y + (p2.y - p0.y) / 6
    const cp2x = p2.x - (p3.x - p1.x) / 6
    const cp2y = p2.y - (p3.y - p1.y) / 6
    d += ` C${cp1x.toFixed(1)},${cp1y.toFixed(1)} ${cp2x.toFixed(1)},${cp2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`
  }
  return d
}

const plot = computed(() => {
  const selected = props.series.find(s => s.key === selectedKey.value)
  if (!selected) return null

  const { min_ms, p50_ms, p99_ms, max_ms, count } = selected.histogram
  if (!count || !min_ms || !max_ms) return null

  const color = colorFor(selected.key)

  // Axis spans (min, max) of the recorded values plus breathing room on each
  // side so the min / max markers and edge buckets don't get clipped at the
  // plot edge. Padding is 10% of the data span with a 0.5 log-unit floor —
  // the floor matters most for near-uniform data (min≈max), where a literal
  // axis collapses the chart to a hairline.
  let minLog: number, maxLog: number
  if (min_ms >= max_ms) {
    minLog = Math.log2(min_ms) - 0.5
    maxLog = Math.log2(min_ms) + 0.5
  }
  else {
    const rawMin = Math.log2(min_ms)
    const rawMax = Math.log2(max_ms)
    const pad = Math.max((rawMax - rawMin) * 0.1, 0.5)
    minLog = rawMin - pad
    maxLog = rawMax + pad
  }
  const xSpan = Math.max(maxLog - minLog, 0.001)

  const plotW = VIEWBOX_W - PAD_L - PAD_R
  const plotH = VIEWBOX_H - PAD_T - PAD_B
  const baseline = PAD_T + plotH

  const xForLog = (log: number) =>
    PAD_L + Math.max(0, Math.min(1, (log - minLog) / xSpan)) * plotW
  const xForMs = (ms: number) => xForLog(Math.log2(ms))

  // Four markers: min, p50, p99, max. Skip zero-valued ones (e.g. p99 on
  // single-sample fixtures). Stagger label rows when consecutive markers
  // cluster within ~36 px so names / values never overlap.
  const percentileBase = ([
    ['min', min_ms],
    ['p50', p50_ms],
    ['p99', p99_ms],
    ['max', max_ms],
  ] as const)
    .filter(([, v]) => v > 0)
    .map(([label, v]) => ({ label, value: v, valueLabel: formatMs(v), x: xForMs(v) }))

  const LABEL_OVERLAP_THRESHOLD = 40
  const ROW_OFFSETS = [
    { nameDY: -12, valueDY: -3 }, // row 0 (closest to plot)
    { nameDY: -28, valueDY: -19 }, // row 1
    { nameDY: -44, valueDY: -35 }, // row 2
    { nameDY: -60, valueDY: -51 }, // row 3 (topmost, just inside viewBox)
  ]
  let row = 0
  const percentiles: PercentileMarker[] = percentileBase.map((m, i) => {
    if (i === 0) {
      row = 0
    }
    else {
      const distance = m.x - percentileBase[i - 1]!.x
      row = distance < LABEL_OVERLAP_THRESHOLD
        ? Math.min(row + 1, ROW_OFFSETS.length - 1)
        : 0
    }
    const offset = ROW_OFFSETS[row]!
    return {
      ...m,
      nameY: PAD_T + offset.nameDY,
      valueY: PAD_T + offset.valueDY,
    }
  })

  // Buckets: drop the 0..1ms bucket, then attach each remaining bucket's
  // lower bound (taken from the previous bucket's le_ms so we don't assume
  // any particular bucket spacing — the backend emits a 2^(1/4) grid, not
  // plain doublings). Retain only buckets that overlap with (min_ms, max_ms];
  // by definition all sample-containing ones do, so `sum(bar.count) === count`
  // is preserved. Bars extending past the axis get clipped by xForLog's clamp.
  const raw = (selected.histogram.buckets ?? []).filter(b => b.le_ms > 0)
  const withLow = raw.map((b, i) => ({
    ...b,
    lo_ms: i > 0 ? raw[i - 1]!.le_ms : 0,
  }))
  const relevant = withLow.filter(b => b.le_ms >= min_ms && b.lo_ms <= max_ms)

  let bars: Bar[] = []
  let maxCount = 0
  if (relevant.length > 0) {
    maxCount = Math.max(...relevant.map(b => b.count), 0)
    const scale = Math.max(maxCount, 1)
    bars = relevant.map((b) => {
      const logUpper = Math.log2(b.le_ms)
      // lo_ms=0 represents the open-ended left side of the first bucket —
      // log2(0) = -Infinity, which xForLog would clamp anyway, so use minLog
      // to render the bar flush with the plot's left edge.
      const logLower = b.lo_ms > 0 ? Math.log2(b.lo_ms) : minLog
      const x1 = xForLog(logLower)
      const x2 = xForLog(logUpper)
      return {
        x1,
        x2,
        y: baseline - (b.count / scale) * plotH,
        count: b.count,
        labelX: (x1 + x2) / 2,
      }
    })
  }

  // Curve: Catmull-Rom through bar-top midpoints, anchored to baseline at the
  // outer bar edges so the density curve reads as a bounded distribution.
  const curvePath = bars.length >= 1
    ? catmullRomPath([
        { x: bars[0]!.x1, y: baseline },
        ...bars.map(b => ({ x: (b.x1 + b.x2) / 2, y: b.y })),
        { x: bars[bars.length - 1]!.x2, y: baseline },
      ])
    : ''

  const ticks = [0, 0.333, 0.666, 1].map((f) => {
    const log = minLog + xSpan * f
    return { x: xForLog(log), label: formatMs(Math.pow(2, log)) }
  })

  // Y-axis ticks: 0, midpoint, max. Maps to the bar-height scale — top of the
  // tallest bar is at y=PAD_T, baseline is y=baseline. Omit the axis entirely
  // when there are no bars to describe.
  const yTicks: YTick[] = maxCount > 0
    ? [
        { value: 0, y: baseline },
        { value: Math.round(maxCount / 2), y: baseline - plotH / 2 },
        { value: maxCount, y: PAD_T },
      ]
    : []

  return {
    color,
    label: selected.label,
    count,
    bars,
    curvePath,
    percentiles,
    ticks,
    yTicks,
    baseline,
    plotW,
    plotH,
    // Pre-formatted stats for the footer — same values as the markers, but
    // always rendered (even if a marker was skipped for being zero).
    stats: {
      min: formatMs(min_ms),
      p50: formatMs(p50_ms),
      p99: formatMs(p99_ms),
      max: formatMs(max_ms),
    },
  }
})
</script>

<template>
  <div class="bg-surface-elevated border border-border p-3">
    <!-- Segment picker -->
    <div
      class="flex flex-wrap gap-1 mb-3"
      role="tablist"
      aria-label="Select latency segment"
      data-testid="latency-segment-picker"
    >
      <button
        v-for="s in series"
        :key="s.key"
        type="button"
        role="tab"
        :aria-selected="selectedKey === s.key"
        class="inline-flex items-center gap-1.5 px-2.5 py-1 text-xs border transition-colors select-none"
        :class="selectedKey === s.key
          ? 'border-border bg-muted text-fg-strong'
          : 'border-transparent text-fg-muted hover:text-fg-primary hover:bg-muted/50'"
        :data-series-key="s.key"
        @click="select(s.key)"
      >
        <span
          class="w-2 h-2 shrink-0 inline-block"
          :style="{
            backgroundColor: colorFor(s.key),
            opacity: selectedKey === s.key ? 1 : 0.55,
          }"
          aria-hidden="true"
        />
        {{ s.label }}
      </button>
    </div>

    <!-- Chart -->
    <div
      v-if="!plot"
      class="h-[240px] flex items-center justify-center text-xs text-fg-muted"
    >
      Not enough samples yet for this segment
    </div>

    <!-- NOSONAR(Web:S6819) — role="img" on inline SVG is the WAI-ARIA-recommended way to expose decorative-but-meaningful charts to screen readers (paired with aria-label); no semantic HTML alternative for a custom data plot. -->
    <svg
      v-else
      :viewBox="`0 0 ${VIEWBOX_W} ${VIEWBOX_H}`"
      class="w-full h-auto block"
      preserveAspectRatio="none"
      role="img"
      :aria-label="`${plot.label} latency distribution`"
    >
      <!-- baseline -->
      <line
        :x1="PAD_L"
        :x2="VIEWBOX_W - PAD_R"
        :y1="plot.baseline"
        :y2="plot.baseline"
        stroke="var(--color-border)"
        stroke-width="0.5"
      />

      <!-- y-axis: vertical line + count ticks -->
      <g
        data-testid="y-axis"
        aria-hidden="true"
      >
        <line
          :x1="PAD_L"
          :x2="PAD_L"
          :y1="PAD_T"
          :y2="plot.baseline"
          stroke="var(--color-border)"
          stroke-width="0.5"
        />
        <template
          v-for="(t, ti) in plot.yTicks"
          :key="`yt-${ti}`"
        >
          <line
            :x1="PAD_L - 3"
            :x2="PAD_L"
            :y1="t.y"
            :y2="t.y"
            stroke="var(--color-border)"
            stroke-width="0.5"
          />
          <text
            :x="PAD_L - 5"
            :y="t.y + 3"
            fill="var(--color-fg-muted)"
            font-size="9"
            font-family="ui-monospace, SFMono-Regular, monospace"
            text-anchor="end"
          >{{ t.value }}</text>
        </template>
      </g>

      <!-- histogram bars -->
      <rect
        v-for="(b, bi) in plot.bars"
        :key="`bar-${bi}`"
        :x="b.x1"
        :y="b.y"
        :width="Math.max(0.5, b.x2 - b.x1 - 1)"
        :height="plot.baseline - b.y"
        :fill="plot.color"
        :fill-opacity="hoveredBarIdx === bi ? 0.5 : 0.25"
      />

      <!-- smooth density curve -->
      <path
        v-if="plot.curvePath"
        :d="plot.curvePath"
        fill="none"
        :stroke="plot.color"
        stroke-width="1.75"
        stroke-linejoin="round"
        stroke-linecap="round"
      />

      <!-- Hover count label for the currently-hovered bar. Always shown on top
           of the curve so it remains readable over the density overlay. -->
      <g data-testid="bar-counts">
        <text
          v-if="hoveredBarIdx !== null && plot.bars[hoveredBarIdx]"
          :x="plot.bars[hoveredBarIdx]!.labelX"
          :y="Math.max(PAD_T + 10, plot.bars[hoveredBarIdx]!.y - 4)"
          fill="var(--color-fg-strong)"
          font-size="10"
          font-family="ui-monospace, SFMono-Regular, monospace"
          text-anchor="middle"
          font-weight="600"
        >{{ plot.bars[hoveredBarIdx]!.count }}</text>
      </g>

      <!-- Percentile markers — always visible since only one series is in view.
           Each marker has two stacked labels: name ("p50") and value ("5ms"). -->
      <g
        data-testid="percentile-markers"
        aria-hidden="true"
      >
        <template
          v-for="m in plot.percentiles"
          :key="m.label"
        >
          <line
            :x1="m.x"
            :x2="m.x"
            :y1="PAD_T"
            :y2="plot.baseline"
            :stroke="plot.color"
            stroke-width="1"
            stroke-dasharray="3 3"
            opacity="0.65"
          />
          <text
            :x="m.x"
            :y="m.nameY"
            :fill="plot.color"
            font-size="9"
            font-family="ui-monospace, SFMono-Regular, monospace"
            text-anchor="middle"
            font-weight="500"
          >{{ m.label }}</text>
          <text
            :x="m.x"
            :y="m.valueY"
            fill="var(--color-fg-muted)"
            font-size="9"
            font-family="ui-monospace, SFMono-Regular, monospace"
            text-anchor="middle"
          >{{ m.valueLabel }}</text>
        </template>
      </g>

      <!-- x-axis ticks -->
      <g>
        <text
          v-for="(t, i) in plot.ticks"
          :key="i"
          :x="t.x"
          :y="VIEWBOX_H - 8"
          fill="var(--color-fg-muted)"
          font-size="10"
          font-family="ui-monospace, SFMono-Regular, monospace"
          text-anchor="middle"
        >{{ t.label }}</text>
      </g>

      <!-- Transparent full-height hover targets. Kept last in the DOM so they
           sit above bars/curve/markers and always receive pointer events, even
           over zero-count buckets where no visible bar exists. -->
      <g data-testid="bar-hit-targets">
        <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions, vuejs-accessibility/mouse-events-have-key-events -- SVG chart hit targets for pointer-only tooltip; chart is decorative (stats visible below in dl) -->
        <rect
          v-for="(b, bi) in plot.bars"
          :key="`hit-${bi}`"
          :x="b.x1"
          :y="PAD_T"
          :width="Math.max(0.5, b.x2 - b.x1)"
          :height="plot.plotH"
          fill="transparent"
          style="cursor: crosshair"
          @mouseenter="hoveredBarIdx = bi"
          @mouseleave="hoveredBarIdx = null"
        />
      </g>
    </svg>

    <dl
      v-if="plot"
      class="flex flex-wrap items-center justify-center gap-x-3 gap-y-1 text-[10px] font-mono mt-2"
      data-testid="latency-stats-footer"
    >
      <div class="flex items-baseline gap-1.5">
        <dt class="text-fg-muted">
          n
        </dt>
        <dd class="text-fg-primary font-medium">
          {{ plot.count }}
        </dd>
      </div>
      <span
        class="text-border select-none"
        aria-hidden="true"
      >·</span>
      <div class="flex items-baseline gap-1.5">
        <dt class="text-fg-muted">
          min
        </dt>
        <dd class="text-fg-primary">
          {{ plot.stats.min }}
        </dd>
      </div>
      <span
        class="text-border select-none"
        aria-hidden="true"
      >·</span>
      <div class="flex items-baseline gap-1.5">
        <dt class="text-fg-muted">
          p50
        </dt>
        <dd class="text-fg-primary">
          {{ plot.stats.p50 }}
        </dd>
      </div>
      <span
        class="text-border select-none"
        aria-hidden="true"
      >·</span>
      <div class="flex items-baseline gap-1.5">
        <dt class="text-fg-muted">
          p99
        </dt>
        <dd class="text-fg-primary">
          {{ plot.stats.p99 }}
        </dd>
      </div>
      <span
        class="text-border select-none"
        aria-hidden="true"
      >·</span>
      <div class="flex items-baseline gap-1.5">
        <dt class="text-fg-muted">
          max
        </dt>
        <dd class="text-fg-primary">
          {{ plot.stats.max }}
        </dd>
      </div>
    </dl>
  </div>
</template>
