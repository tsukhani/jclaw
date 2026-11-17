<script setup lang="ts">
// Where the space goes (JF-496): each table's part of the data file and its change over a day, a week and 30
// days, from the one sample a day the backend keeps. The rows add up to the file: the tables, what no table's
// figure counts (its indexes, mostly), and the free space a compaction would remove.
import { formatDateTime } from '~/utils/schedule'
import { formatSize } from '~/utils/format'
import MetricSparkline from './MetricSparkline.vue'

interface Change { bytes: number, rows: number }
interface TableGrowth {
  name: string
  bytes: number
  rows: number
  share: number | null
  day: Change | null
  week: Change | null
  month: Change | null
  trend: number[]
}
interface FileDay { day: string, fileBytes: number | null, liveBytes: number | null, reclaimableBytes: number | null }
interface Growth {
  sampledOn: string | null
  takenAt: string | null
  historySince: string | null
  file: FileDay | null
  otherLiveBytes: number | null
  tables: TableGrowth[]
  fileTrend: FileDay[]
}

const props = defineProps<{ timezone: string | null }>()

const { data: growth, error } = useLazyFetch<Growth>('/api/system/database/growth')

const TOP = 10
const showOthers = ref(false)
const largest = computed(() => growth.value?.tables.slice(0, TOP) ?? [])
const others = computed(() => growth.value?.tables.slice(TOP) ?? [])

function sumChanges(changes: Array<Change | null>): Change | null {
  const known = changes.filter((c): c is Change => c !== null)
  if (!known.length) return null
  return { bytes: known.reduce((a, c) => a + c.bytes, 0), rows: known.reduce((a, c) => a + c.rows, 0) }
}

const othersRow = computed(() => {
  const rows = others.value
  if (!rows.length) return null
  return {
    bytes: rows.reduce((a, t) => a + t.bytes, 0),
    rows: rows.reduce((a, t) => a + t.rows, 0),
    share: rows.every(t => t.share !== null) ? rows.reduce((a, t) => a + (t.share ?? 0), 0) : null,
    day: sumChanges(rows.map(t => t.day)),
    week: sumChanges(rows.map(t => t.week)),
    month: sumChanges(rows.map(t => t.month)),
  }
})

const fileBytes = computed(() => growth.value?.file?.fileBytes ?? null)

function shareOf(bytes: number | null): number | null {
  const size = fileBytes.value
  return bytes === null || !size ? null : bytes / size
}

function percent(share: number | null): string {
  return share === null ? '—' : `${(share * 100).toFixed(1)}%`
}

const count = new Intl.NumberFormat('en-GB')

function signedSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  return `${bytes > 0 ? '+' : '−'}${formatSize(Math.abs(bytes))}`
}

function signedRows(rows: number): string {
  if (rows === 0) return 'no change in rows'
  return `${rows > 0 ? '+' : '−'}${count.format(Math.abs(rows))} ${Math.abs(rows) === 1 ? 'row' : 'rows'}`
}

function day(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
}

/** Fewer than 30 days kept: say when the history starts, so the empty columns read as not yet, not as nothing. */
const historyNote = computed(() => {
  const g = growth.value
  if (!g?.historySince || !g.sampledOn) return ''
  const days = Math.round((Date.parse(g.sampledOn) - Date.parse(g.historySince)) / 86_400_000)
  return days < 30 ? `History starts on ${day(g.historySince)}; the longer columns fill in as it grows.` : ''
})

// The file against its live data over the last 30 days, on one scale from zero: the gap is the free space.
const VIEW_W = 100
const VIEW_H = 24
const chartPoints = computed(() => (growth.value?.fileTrend ?? []).filter(p => p.fileBytes !== null && p.liveBytes !== null))
const chartMax = computed(() => Math.max(1, ...chartPoints.value.map(p => p.fileBytes ?? 0)))

function chartPath(pick: (p: FileDay) => number | null): string {
  const pts = chartPoints.value
  if (pts.length < 2) return ''
  const step = VIEW_W / (pts.length - 1)
  return pts.map((p, i) => {
    const y = VIEW_H - ((pick(p) ?? 0) / chartMax.value) * VIEW_H
    return `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

const chartSummary = computed(() => {
  const pts = chartPoints.value
  if (pts.length < 2) return ''
  const first = pts[0]!
  const last = pts.at(-1)!
  return `From ${day(first.day)} to ${day(last.day)}, the file went from ${formatSize(first.fileBytes ?? 0)} to `
    + `${formatSize(last.fileBytes ?? 0)}, and its live data from ${formatSize(first.liveBytes ?? 0)} to `
    + `${formatSize(last.liveBytes ?? 0)}.`
})
</script>

<template>
  <div
    class="bg-surface-elevated border border-border"
    data-testid="db-growth"
  >
    <div class="px-4 py-2.5 border-b border-border">
      <span class="text-sm font-medium text-fg-strong">Where the space goes</span>
      <p
        class="text-xs text-fg-muted mt-0.5"
        data-testid="db-growth-state"
      >
        <template v-if="error">
          Could not read the size history: {{ error.message }}
        </template>
        <template v-else-if="!growth">
          Loading…
        </template>
        <template v-else-if="!growth.sampledOn">
          No sizes recorded yet. JClaw records each table's size once a day, the first time shortly after it starts.
        </template>
        <template v-else>
          As of {{ growth.takenAt ? formatDateTime(growth.takenAt, props.timezone) : day(growth.sampledOn) }}, recorded
          once a day. {{ historyNote }}
        </template>
      </p>
    </div>

    <div
      v-if="chartSummary"
      class="px-4 py-2.5 border-b border-border"
      data-testid="db-growth-file"
    >
      <svg
        :viewBox="`0 0 ${VIEW_W} ${VIEW_H}`"
        class="w-full h-12"
        preserveAspectRatio="none"
        role="img"
        :aria-label="chartSummary"
      >
        <line
          x1="0"
          :y1="VIEW_H - 0.5"
          :x2="VIEW_W"
          :y2="VIEW_H - 0.5"
          stroke="var(--color-border)"
          stroke-width="0.5"
          vector-effect="non-scaling-stroke"
        />
        <path
          :d="chartPath(p => p.fileBytes)"
          fill="none"
          stroke="var(--color-fg-primary)"
          stroke-width="1.5"
          vector-effect="non-scaling-stroke"
          stroke-linejoin="round"
        />
        <path
          :d="chartPath(p => p.liveBytes)"
          fill="none"
          stroke="var(--color-fg-muted)"
          stroke-width="1.5"
          stroke-dasharray="3 2"
          vector-effect="non-scaling-stroke"
          stroke-linejoin="round"
        />
      </svg>
      <p class="mt-1 text-xs text-fg-muted">
        <span class="text-fg-strong">Solid:</span> the file. <span class="text-fg-strong">Dashed:</span> its live
        data. The gap between them is what a compaction would free. {{ chartSummary }}
      </p>
    </div>

    <div
      v-if="growth?.sampledOn"
      class="overflow-x-auto"
    >
      <table
        class="w-full text-xs tabular-nums"
        data-testid="db-growth-table"
      >
        <thead>
          <tr class="text-fg-muted text-left border-b border-border">
            <th
              scope="col"
              class="px-4 py-1.5 font-normal"
            >
              Table
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right"
            >
              Size
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right"
            >
              Share
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right"
            >
              Rows
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right"
            >
              1 day
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right"
            >
              7 days
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right"
            >
              30 days
            </th>
            <th
              scope="col"
              class="px-4 py-1.5 font-normal w-24"
            >
              Trend
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="t in largest"
            :key="t.name"
            :data-testid="`db-growth-row-${t.name}`"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-mono font-normal text-left text-fg-primary"
            >
              {{ t.name }}
            </th>
            <td class="px-2 py-1.5 text-right text-fg-strong whitespace-nowrap">
              {{ formatSize(t.bytes) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              {{ percent(t.share) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              {{ count.format(t.rows) }}
            </td>
            <td
              v-for="(c, i) in [t.day, t.week, t.month]"
              :key="i"
              class="px-2 py-1.5 text-right whitespace-nowrap"
            >
              <template v-if="c">
                <span class="text-fg-strong">{{ signedSize(c.bytes) }}</span>
                <span class="block text-fg-muted">{{ signedRows(c.rows) }}</span>
              </template>
              <span
                v-else
                class="text-fg-muted"
              ><span aria-hidden="true">—</span><span class="sr-only">no sample that old</span></span>
            </td>
            <td class="px-4 py-1.5">
              <MetricSparkline
                :points="t.trend"
                :label="`${t.name} size over the last 30 days`"
              />
            </td>
          </tr>
          <template v-if="othersRow">
            <tr data-testid="db-growth-others">
              <th
                scope="row"
                class="px-4 py-1.5 font-normal text-left"
              >
                <button
                  class="text-fg-primary hover:underline"
                  :aria-expanded="showOthers"
                  @click="showOthers = !showOthers"
                >
                  {{ others.length }} other {{ others.length === 1 ? 'table' : 'tables' }}
                </button>
              </th>
              <td class="px-2 py-1.5 text-right text-fg-strong whitespace-nowrap">
                {{ formatSize(othersRow.bytes) }}
              </td>
              <td class="px-2 py-1.5 text-right whitespace-nowrap">
                {{ percent(othersRow.share) }}
              </td>
              <td class="px-2 py-1.5 text-right whitespace-nowrap">
                {{ count.format(othersRow.rows) }}
              </td>
              <td
                v-for="(c, i) in [othersRow.day, othersRow.week, othersRow.month]"
                :key="i"
                class="px-2 py-1.5 text-right whitespace-nowrap"
              >
                <span
                  v-if="c"
                  class="text-fg-strong"
                >{{ signedSize(c.bytes) }}</span>
                <span
                  v-else
                  class="text-fg-muted"
                ><span aria-hidden="true">—</span><span class="sr-only">no sample that old</span></span>
              </td>
              <td />
            </tr>
            <template v-if="showOthers">
              <tr
                v-for="t in others"
                :key="t.name"
                class="text-fg-muted"
                :data-testid="`db-growth-row-${t.name}`"
              >
                <th
                  scope="row"
                  class="pl-8 pr-4 py-1 font-mono font-normal text-left"
                >
                  {{ t.name }}
                </th>
                <td class="px-2 py-1 text-right whitespace-nowrap">
                  {{ formatSize(t.bytes) }}
                </td>
                <td class="px-2 py-1 text-right whitespace-nowrap">
                  {{ percent(t.share) }}
                </td>
                <td class="px-2 py-1 text-right whitespace-nowrap">
                  {{ count.format(t.rows) }}
                </td>
                <td
                  v-for="(c, i) in [t.day, t.week, t.month]"
                  :key="i"
                  class="px-2 py-1 text-right whitespace-nowrap"
                >
                  {{ c ? signedSize(c.bytes) : '—' }}
                </td>
                <td />
              </tr>
            </template>
          </template>
          <tr
            v-if="growth.otherLiveBytes !== null"
            data-testid="db-growth-uncounted"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-normal text-left text-fg-muted"
            >
              Indexes and other
            </th>
            <td class="px-2 py-1.5 text-right text-fg-strong whitespace-nowrap">
              {{ formatSize(growth.otherLiveBytes) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              {{ percent(shareOf(growth.otherLiveBytes)) }}
            </td>
            <td colspan="5" />
          </tr>
          <tr
            v-if="growth.file?.reclaimableBytes != null"
            data-testid="db-growth-free"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-normal text-left text-fg-muted"
            >
              Free space a compaction would remove
            </th>
            <td class="px-2 py-1.5 text-right text-fg-strong whitespace-nowrap">
              {{ formatSize(growth.file.reclaimableBytes) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              {{ percent(shareOf(growth.file.reclaimableBytes)) }}
            </td>
            <td colspan="5" />
          </tr>
        </tbody>
        <tfoot v-if="fileBytes !== null">
          <tr
            class="border-t border-border"
            data-testid="db-growth-total"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-medium text-left text-fg-strong"
            >
              The data file
            </th>
            <td class="px-2 py-1.5 text-right font-medium text-fg-strong whitespace-nowrap">
              {{ formatSize(fileBytes) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              100%
            </td>
            <td colspan="5" />
          </tr>
        </tfoot>
      </table>
    </div>
  </div>
</template>
