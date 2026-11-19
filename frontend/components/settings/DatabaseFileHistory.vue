<script setup lang="ts">
// The data file's last 30 days in Where the space goes (JF-525): one column per sampled day split into the live
// data and the free space above it, or the same days as a stacked area. Hand-rolled SVG like LatencyOverlayChart;
// the SVG is redrawn at its measured width so its labels keep their size instead of stretching with a viewBox.
import { formatSize } from '~/utils/format'
import { DB_FREE_FILL, DB_IN_USE_COLOR } from '~/utils/database-growth'

interface FileDay { day: string, fileBytes: number | null, liveBytes: number | null, reclaimableBytes: number | null }

const props = defineProps<{
  /** Oldest first, as the growth route sends them. */
  points: FileDay[]
  /** The newest sample's day, ISO: the axis's right end. */
  today: string
}>()

type Form = 'columns' | 'area'
const STORAGE_KEY = 'jclaw:db-growth:chart-form'
const form = ref<Form>('columns')
onMounted(() => {
  try {
    if (localStorage.getItem(STORAGE_KEY) === 'area') form.value = 'area'
  }
  catch { /* storage blocked: Columns */ }
})
function choose(next: Form) {
  form.value = next
  try {
    localStorage.setItem(STORAGE_KEY, next)
  }
  catch { /* storage blocked: the choice lasts this visit */ }
}

const MiB = 1024 * 1024
const SLOTS = 30 // DatabaseGrowth.TREND_DAYS
const H = 188
const ML = 52
const MT = 10
const MB_BAND = 26
const PLOT_H = H - MT - MB_BAND

const box = ref<HTMLElement | null>(null)
const width = ref(640)
let observer: ResizeObserver | null = null
onMounted(() => {
  const el = box.value
  if (!el) return
  if (el.clientWidth > 0) width.value = el.clientWidth
  if (typeof ResizeObserver === 'undefined') return
  observer = new ResizeObserver(([entry]) => {
    if (entry && entry.contentRect.width > 0) width.value = Math.round(entry.contentRect.width)
  })
  observer.observe(el)
})
onBeforeUnmount(() => observer?.disconnect())

const wide = computed(() => width.value >= 600)
const mr = computed(() => (wide.value ? 116 : 14))
const plotW = computed(() => Math.max(1, width.value - ML - mr.value))
const slotW = computed(() => plotW.value / SLOTS)
const barW = computed(() => Math.min(20, slotW.value * 0.64))

function addDays(iso: string, n: number): string {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}
function dayLabel(iso: string, withYear = false): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC',
  })
}

interface Slot { i: number, day: string, file: number | null, live: number | null }
const slots = computed<Slot[]>(() => {
  const byDay = new Map(props.points.map(p => [p.day, p]))
  return Array.from({ length: SLOTS }, (_, i) => {
    const day = addDays(props.today, i - (SLOTS - 1))
    const p = byDay.get(day)
    const known = p?.fileBytes != null && p.liveBytes != null
    return { i, day, file: known ? p!.fileBytes : null, live: known ? p!.liveBytes : null }
  })
})
const recorded = computed(() => slots.value.filter((s): s is Slot & { file: number, live: number } => s.file !== null))

// 1, 2, 2.5 or 5 times a power of ten, so the axis takes at most four steps and every tick reads as a round size.
function niceStep(max: number): number {
  const raw = max / 4
  const pow = 10 ** Math.floor(Math.log10(raw))
  return ([1, 2, 2.5, 5, 10].find(m => m * pow >= raw) ?? 10) * pow
}
const axis = computed(() => {
  const maxBytes = Math.max(1, ...recorded.value.map(s => s.file))
  const unit = maxBytes >= 2048 * MiB ? { name: 'GB', bytes: 1024 * MiB } : { name: 'MB', bytes: MiB }
  const step = niceStep(maxBytes / unit.bytes)
  const top = Math.ceil(maxBytes / unit.bytes / step) * step
  const ticks: Array<{ y: number, label: string }> = []
  for (let k = 0; k * step <= top + step / 1000; k++) {
    const v = k * step
    ticks.push({ y: Math.round(MT + PLOT_H * (1 - v / top)) + 0.5, label: `${Number(v.toFixed(2))} ${unit.name}` })
  }
  return { top: top * unit.bytes, ticks }
})
const y = (bytes: number) => MT + PLOT_H * (1 - bytes / axis.value.top)
const cx = (i: number) => ML + slotW.value * (i + 0.5)

const xTicks = computed(() => (wide.value ? [1, 8, 15, 22, 29] : [1, 15, 29]).map(i => ({
  x: !wide.value && i === SLOTS - 1 ? ML + plotW.value : cx(i),
  anchor: !wide.value && i === SLOTS - 1 ? 'end' : 'middle',
  label: dayLabel(slots.value[i]!.day),
})))

const emptyNote = computed(() => {
  const first = recorded.value[0]
  if (!first || first.i === 0 || slotW.value * first.i < 150) return null
  return { x: ML + (slotW.value * first.i) / 2, label: `No samples before ${dayLabel(first.day)}` }
})

const columns = computed(() => recorded.value.map((s) => {
  const x = ML + slotW.value * s.i + (slotW.value - barW.value) / 2
  const yLive = y(s.live)
  const yFile = y(s.file)
  const freeH = yLive - 2 - yFile
  return {
    i: s.i,
    inUse: { x, y: yLive, width: barW.value, height: Math.max(0, y(0) - yLive) },
    free: freeH > 1 ? { x: x + 0.5, y: yFile + 0.5, width: barW.value - 1, height: freeH - 1 } : null,
  }
}))

const area = computed(() => {
  const pts = recorded.value.map(s => ({ x: cx(s.i), yLive: y(s.live), yFile: y(s.file) }))
  const line = (k: 'yLive' | 'yFile') => pts.map((p, j) => `${j ? 'L' : 'M'}${p.x.toFixed(1)},${p[k].toFixed(1)}`).join(' ')
  const back = (k: 'yLive' | 'yFile') => pts.slice().reverse().map(p => `L${p.x.toFixed(1)},${p[k].toFixed(1)}`).join(' ')
  const base = y(0).toFixed(1)
  const drawn = pts.length > 1
  return {
    free: drawn ? `${line('yFile')} ${back('yLive')} Z` : '',
    inUse: drawn ? `${line('yLive')} L${pts.at(-1)!.x.toFixed(1)},${base} L${pts[0]!.x.toFixed(1)},${base} Z` : '',
    fileLine: drawn ? line('yFile') : '',
    liveLine: drawn ? line('yLive') : '',
    // A marker on every sample while they are few enough to read as points; past a week, only today's.
    dots: pts.length <= 7 ? pts : pts.slice(-1),
  }
})

const endLabels = computed(() => {
  const last = slots.value.at(-1)!
  if (!wide.value || last.file === null || last.live === null) return null
  const x = ML + plotW.value + 10
  const freeY = Math.max(MT + 10, (y(last.file) + y(last.live)) / 2 - 4)
  const liveY = Math.max(freeY + 30, (y(last.live) + y(0)) / 2 - 4)
  return {
    x,
    free: { y: freeY, value: formatSize(last.file - last.live) },
    live: { y: liveY, value: formatSize(last.live) },
  }
})

const TIP_W = 224
const pct = (part: number, whole: number) => `${((part / whole) * 100).toFixed(1)}%`
const active = ref<number | null>(null)
const activeSlot = computed(() => (active.value === null ? null : slots.value[active.value]!))
const tipLeft = computed(() => {
  if (active.value === null) return 0
  const x = cx(active.value) + 14
  return x + TIP_W > width.value ? cx(active.value) - 14 - TIP_W : x
})

function onPointer(e: PointerEvent) {
  const svg = e.currentTarget as SVGElement
  const left = svg.getBoundingClientRect().left
  active.value = Math.max(0, Math.min(SLOTS - 1, Math.floor((e.clientX - left - ML) / slotW.value)))
}
// The range steps through the sampled days only; a day the pointer picked without a sample reads as the newest.
const dayIndex = computed(() => {
  const at = recorded.value.findIndex(s => s.i === active.value)
  return at === -1 ? recorded.value.length - 1 : at
})
const dayText = computed(() => {
  const s = recorded.value[dayIndex.value]
  if (!s) return ''
  return `${dayLabel(s.day, true)}: data file ${formatSize(s.file)}, in use ${formatSize(s.live)}, free space `
    + `${formatSize(s.file - s.live)}, ${pct(s.file - s.live, s.file)}`
})
function onDay(e: Event) {
  active.value = recorded.value[Number((e.target as HTMLInputElement).value)]?.i ?? null
}
function onFocus() {
  if (active.value === null) active.value = recorded.value.at(-1)?.i ?? null
}

const summary = computed(() => {
  const a = recorded.value[0]
  const z = recorded.value.at(-1)
  if (!a || !z) return ''
  if (a === z) {
    return `The data file on ${dayLabel(z.day, true)}: ${formatSize(z.file)}, of which ${formatSize(z.live)} in use `
      + `and ${formatSize(z.file - z.live)} free.`
  }
  return `The data file by day, ${dayLabel(a.day)} to ${dayLabel(z.day, true)}: from ${formatSize(a.file)} to `
    + `${formatSize(z.file)}; in use from ${formatSize(a.live)} to ${formatSize(z.live)}; free space from `
    + `${formatSize(a.file - a.live)} to ${formatSize(z.file - z.live)}.`
})
</script>

<template>
  <div
    class="grid gap-2"
    data-testid="db-growth-file"
    :data-form="form"
  >
    <div class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
      <div class="flex flex-wrap gap-x-4 gap-y-1 text-xs text-fg-primary">
        <span class="inline-flex items-center gap-1.5">
          <span
            class="size-2.5 rounded-[2px]"
            :style="{ background: DB_IN_USE_COLOR }"
            aria-hidden="true"
          />In use
        </span>
        <span class="inline-flex items-center gap-1.5">
          <span
            class="size-2.5 rounded-[2px] ring-1 ring-inset ring-fg-muted"
            :style="{ background: DB_FREE_FILL }"
            aria-hidden="true"
          />Free space a compaction would remove
        </span>
      </div>
      <fieldset
        class="inline-flex border border-border text-xs"
        aria-label="Chart form"
      >
        <button
          v-for="f in (['columns', 'area'] as const)"
          :key="f"
          type="button"
          class="px-2.5 py-0.5 first:border-r first:border-border focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fg-strong"
          :class="form === f ? 'bg-fg-strong text-surface-elevated' : 'text-fg-primary hover:bg-muted'"
          :aria-pressed="form === f"
          :data-testid="`db-growth-form-${f}`"
          @click="choose(f)"
        >
          {{ f === 'columns' ? 'Columns' : 'Area' }}
        </button>
      </fieldset>
    </div>

    <div
      ref="box"
      class="relative focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-fg-strong"
    >
      <svg
        :viewBox="`0 0 ${width} ${H}`"
        :width="width"
        :height="H"
        class="block w-full overflow-visible"
        role="img"
        :aria-label="summary"
        @pointermove="onPointer"
        @pointerleave="active = null"
      >
        <g
          v-for="t in axis.ticks"
          :key="t.label"
        >
          <line
            :x1="ML"
            :x2="ML + plotW"
            :y1="t.y"
            :y2="t.y"
            stroke="var(--color-border)"
            :stroke-opacity="t === axis.ticks[0] ? 1 : 0.45"
          />
          <text
            :x="ML - 8"
            :y="t.y + 4"
            text-anchor="end"
            font-size="11"
            fill="var(--color-fg-muted)"
          >{{ t.label }}</text>
        </g>
        <text
          v-for="t in xTicks"
          :key="t.label"
          :x="t.x"
          :y="H - 8"
          :text-anchor="t.anchor"
          font-size="11"
          fill="var(--color-fg-muted)"
        >{{ t.label }}</text>
        <text
          v-if="emptyNote"
          :x="emptyNote.x"
          :y="MT + PLOT_H / 2 + 4"
          text-anchor="middle"
          font-size="12"
          fill="var(--color-fg-muted)"
        >{{ emptyNote.label }}</text>

        <rect
          v-if="form === 'columns' && active !== null"
          :x="ML + slotW * active"
          :y="MT"
          :width="slotW"
          :height="PLOT_H"
          fill="var(--color-fg-strong)"
          fill-opacity="0.06"
        />
        <template v-if="form === 'columns'">
          <g
            v-for="c in columns"
            :key="c.i"
            data-testid="db-growth-column"
          >
            <rect
              v-bind="c.inUse"
              :fill="DB_IN_USE_COLOR"
            />
            <rect
              v-if="c.free"
              v-bind="c.free"
              rx="2"
              :fill="DB_FREE_FILL"
              stroke="var(--color-fg-muted)"
            />
          </g>
        </template>
        <template v-else>
          <path
            v-if="area.free"
            :d="area.free"
            :fill="DB_FREE_FILL"
            data-testid="db-growth-area-free"
          />
          <path
            v-if="area.inUse"
            :d="area.inUse"
            :fill="DB_IN_USE_COLOR"
            fill-opacity="0.5"
            data-testid="db-growth-area-in-use"
          />
          <path
            v-if="area.fileLine"
            :d="area.fileLine"
            fill="none"
            stroke="var(--color-fg-muted)"
            stroke-width="1.5"
            stroke-linejoin="round"
          />
          <path
            v-if="area.liveLine"
            :d="area.liveLine"
            fill="none"
            :stroke="DB_IN_USE_COLOR"
            stroke-width="2"
            stroke-linejoin="round"
          />
          <line
            v-if="active !== null"
            :x1="cx(active)"
            :x2="cx(active)"
            :y1="MT"
            :y2="MT + PLOT_H"
            stroke="var(--color-fg-muted)"
          />
          <g
            v-for="p in area.dots"
            :key="p.x"
            data-testid="db-growth-area-point"
          >
            <circle
              :cx="p.x"
              :cy="p.yFile"
              r="4"
              fill="var(--color-fg-muted)"
              stroke="var(--color-surface-elevated)"
              stroke-width="2"
            />
            <circle
              :cx="p.x"
              :cy="p.yLive"
              r="4"
              :fill="DB_IN_USE_COLOR"
              stroke="var(--color-surface-elevated)"
              stroke-width="2"
            />
          </g>
        </template>

        <g
          v-if="endLabels"
          font-size="11"
          fill="var(--color-fg-primary)"
        >
          <text
            :x="endLabels.x"
            :y="endLabels.free.y"
          ><tspan
            font-weight="600"
            fill="var(--color-fg-strong)"
          >{{ endLabels.free.value }}</tspan><tspan
            :x="endLabels.x"
            dy="14"
          >free</tspan></text>
          <text
            :x="endLabels.x"
            :y="endLabels.live.y"
          ><tspan
            font-weight="600"
            fill="var(--color-fg-strong)"
          >{{ endLabels.live.value }}</tspan><tspan
            :x="endLabels.x"
            dy="14"
          >in use</tspan></text>
        </g>
      </svg>
      <input
        v-if="recorded.length"
        type="range"
        class="sr-only"
        aria-label="Day shown in the chart"
        min="0"
        :max="recorded.length - 1"
        step="1"
        :value="dayIndex"
        :aria-valuetext="dayText"
        data-testid="db-growth-day"
        @input="onDay"
        @focus="onFocus"
        @blur="active = null"
        @keydown.esc="active = null"
      >

      <!-- Hidden from screen readers: the range above speaks the same day, and a live region would say it twice. -->
      <div
        v-if="activeSlot"
        class="pointer-events-none absolute z-10 border border-border bg-surface px-2.5 py-2 text-xs tabular-nums whitespace-nowrap shadow-lg"
        :style="{ left: `${tipLeft}px`, top: `${MT}px`, width: `${TIP_W}px` }"
        aria-hidden="true"
        data-testid="db-growth-tip"
      >
        <div class="mb-0.5 font-medium text-fg-strong">
          {{ dayLabel(activeSlot.day, true) }}
        </div>
        <template v-if="activeSlot.file !== null && activeSlot.live !== null">
          <div class="flex justify-between gap-3">
            <span>Data file</span><span>{{ formatSize(activeSlot.file) }}</span>
          </div>
          <div class="flex justify-between gap-3">
            <span>In use</span><span>{{ formatSize(activeSlot.live) }}</span>
          </div>
          <div class="flex justify-between gap-3">
            <span>Free space</span><span>{{ formatSize(activeSlot.file - activeSlot.live) }} · {{ pct(activeSlot.file - activeSlot.live, activeSlot.file) }}</span>
          </div>
        </template>
        <div v-else>
          No sample this day
        </div>
      </div>
    </div>

    <p
      v-if="form === 'area'"
      class="text-xs text-fg-muted"
      data-testid="db-growth-area-note"
    >
      <template v-if="recorded.length < 2">
        The area starts with the second day's sample; until then each day shows as points.
      </template>
      <template v-else>
        The area joins one sample a day with straight lines, so it draws any change between two days as a slope,
        a compaction included.
      </template>
    </p>
  </div>
</template>
