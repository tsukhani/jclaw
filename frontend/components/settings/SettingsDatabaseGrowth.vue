<script setup lang="ts">
// Where the space goes (JF-496, JF-525): each table's part of the data file and its change over a day, a week and
// up to 30 days, from the one sample a day the backend keeps. The rows add up to the file: the tables, what no
// table's figure counts (its indexes, mostly), and the free space a compaction would remove.
import { formatDateTime } from '~/utils/schedule'
import { formatSize } from '~/utils/format'
import {
  DB_FREE_FILL, DB_INDEXES_COLOR, DB_IN_USE_COLOR, DB_OTHER_TABLES_COLOR, DB_TABLE_COLORS,
} from '~/utils/database-growth'
import DatabaseFileHistory from './DatabaseFileHistory.vue'

interface Change { bytes: number, rows: number }
interface TableGrowth {
  name: string
  bytes: number
  rows: number
  share: number | null
  day: Change | null
  week: Change | null
  growth: Change | null
}
interface FileDay { day: string, fileBytes: number | null, liveBytes: number | null, reclaimableBytes: number | null }
interface Growth {
  sampledOn: string | null
  takenAt: string | null
  historySince: string | null
  growthDays: number
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
    growth: sumChanges(rows.map(t => t.growth)),
  }
})

const fileBytes = computed(() => growth.value?.file?.fileBytes ?? null)
const liveBytes = computed(() => growth.value?.file?.liveBytes ?? null)
const freeBytes = computed(() => growth.value?.file?.reclaimableBytes ?? null)

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

function day(iso: string, withYear = true): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-GB', {
    day: 'numeric', month: 'short', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC',
  })
}

function addDays(iso: string, n: number): string {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + n)
  return d.toISOString().slice(0, 10)
}

/** Fewer than 30 days kept: say when the history starts, so the empty columns read as not yet, not as nothing. */
const historyNote = computed(() => {
  const g = growth.value
  if (!g?.historySince || !g.sampledOn) return ''
  const days = Math.round((Date.parse(g.sampledOn) - Date.parse(g.historySince)) / 86_400_000)
  return days < 30 ? `History starts on ${day(g.historySince)}; the longer columns fill in as it grows.` : ''
})

const knownDays = computed(() => (growth.value?.fileTrend ?? []).filter(p => p.fileBytes !== null && p.liveBytes !== null))

interface Figure { key: string, label: string, value: string, note: string, swatch?: string, free?: boolean }
const figures = computed<Figure[] | null>(() => {
  const file = fileBytes.value
  const live = liveBytes.value
  if (file === null || live === null) return null
  const first = knownDays.value[0]
  const since = first && first.day !== growth.value?.sampledOn ? first : null
  const sinceText = (delta: number) => (since ? `${signedSize(delta)} since ${day(since.day, false)}` : '')
  const free = freeBytes.value ?? file - live
  return [
    { key: 'file', label: 'Data file', value: formatSize(file), note: since ? sinceText(file - since.fileBytes!) : '' },
    {
      key: 'live',
      label: 'In use',
      swatch: DB_IN_USE_COLOR,
      value: formatSize(live),
      note: [`${percent(live / file)} of the file`, since ? sinceText(live - since.liveBytes!) : ''].filter(Boolean).join(' · '),
    },
    {
      key: 'free',
      label: 'Free space',
      free: true,
      value: formatSize(free),
      note: `${percent(free / file)} of the file. A compaction would remove it.`,
    },
  ]
})

// The four largest tables keep a color of their own; every other table shares one grey, in the bar and in its row.
const COLORED = DB_TABLE_COLORS.length

function tableColor(index: number): string {
  return DB_TABLE_COLORS[index] ?? DB_OTHER_TABLES_COLOR
}

function tableGroup(index: number, name: string): string {
  return index < COLORED ? name : 'others'
}

interface Part { key: string, label: string, bytes: number, color: string, free?: boolean }
const parts = computed<Part[]>(() => {
  const g = growth.value
  if (!g || fileBytes.value === null) return []
  const out: Part[] = g.tables.slice(0, COLORED)
    .map((t, i) => ({ key: t.name, label: t.name, bytes: t.bytes, color: tableColor(i) }))
  const rest = g.tables.slice(COLORED)
  if (rest.length) {
    out.push({ key: 'others', label: 'All other tables', bytes: rest.reduce((a, t) => a + t.bytes, 0), color: DB_OTHER_TABLES_COLOR })
  }
  if (g.otherLiveBytes !== null) out.push({ key: 'index', label: 'Indexes and other', bytes: g.otherLiveBytes, color: DB_INDEXES_COLOR })
  if (freeBytes.value !== null) {
    out.push({ key: 'free', label: 'Free space a compaction would remove', bytes: freeBytes.value, color: DB_FREE_FILL, free: true })
  }
  return out.filter(p => p.bytes > 0)
})

const activePart = ref<string | null>(null)
const partReadout = computed(() => {
  const p = parts.value.find(x => x.key === activePart.value)
  return p ? `${p.label}: ${formatSize(p.bytes)}, ${percent(shareOf(p.bytes))} of the file` : null
})

// One scale for every row's growth bar, zero-based, so the eye compares tables rather than each row with itself.
const growthScale = computed(() => {
  const values = [...(growth.value?.tables ?? []).map(t => t.growth?.bytes ?? 0), othersRow.value?.growth?.bytes ?? 0]
  const max = Math.max(0, ...values)
  const min = Math.min(0, ...values)
  const span = max - min || 1
  return { span, zero: (-min / span) * 100 }
})

function growthBar(c: Change) {
  const { span, zero } = growthScale.value
  const width = (Math.abs(c.bytes) / span) * 100
  return {
    left: c.bytes >= 0 ? zero : Math.max(0, zero - width),
    width: c.bytes === 0 ? 0 : Math.max(width, 0.6),
    grows: c.bytes >= 0,
  }
}

const growthHeader = computed(() => {
  const g = growth.value
  const days = g?.growthDays ?? 0
  if (!g?.sampledOn || !(days > 0 && days < 30)) return '30 days'
  return `Since ${day(addDays(g.sampledOn, -days), false)}`
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
      v-if="figures"
      class="grid grid-cols-1 sm:grid-cols-3 border-b border-border divide-y sm:divide-y-0 sm:divide-x divide-border"
      data-testid="db-growth-figures"
    >
      <div
        v-for="f in figures"
        :key="f.key"
        class="px-4 py-3 grid gap-0.5 content-start"
        :data-testid="`db-growth-figure-${f.key}`"
      >
        <span class="text-xs text-fg-muted inline-flex items-center gap-1.5">
          <span
            v-if="f.swatch || f.free"
            class="size-2.5 rounded-[2px]"
            :class="f.free ? 'ring-1 ring-inset ring-fg-muted' : ''"
            :style="{ background: f.free ? DB_FREE_FILL : f.swatch }"
            aria-hidden="true"
          />{{ f.label }}
        </span>
        <span class="text-xl font-semibold text-fg-strong leading-tight">{{ f.value }}</span>
        <span
          v-if="f.note"
          class="text-xs text-fg-muted"
        >{{ f.note }}</span>
      </div>
    </div>

    <div
      v-if="knownDays.length && growth?.sampledOn"
      class="px-4 py-3 border-b border-border"
    >
      <DatabaseFileHistory
        :points="knownDays"
        :today="growth.sampledOn"
      />
    </div>

    <div
      v-if="parts.length"
      class="px-4 py-2.5 border-b border-border grid gap-1"
    >
      <!-- One fixed height, or the bar and the table move out from under the pointer that changed the text (JF-526). -->
      <p
        class="h-[2lh] line-clamp-2 text-xs text-fg-muted"
        aria-live="polite"
        data-testid="db-growth-parts-readout"
      >
        {{ partReadout ?? 'The file today, by part. Each row below wears its part\'s color.' }}
      </p>
      <ul
        class="flex items-center gap-0.5 h-6"
        aria-label="The data file today, by part"
        data-testid="db-growth-parts"
      >
        <li
          v-for="(p, i) in parts"
          :key="p.key"
          class="flex min-w-0.5 basis-0"
          :style="{ flexGrow: p.bytes }"
        >
          <!-- A tap leaves the pointer before the click arrives, so the click keeps the part named until focus moves on. -->
          <button
            type="button"
            class="relative block h-2 w-full transition-opacity motion-reduce:transition-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-fg-strong after:absolute after:inset-x-0 after:-inset-y-2"
            :class="[i === 0 ? 'rounded-l-full' : '', i === parts.length - 1 ? 'rounded-r-full' : '', p.free ? 'ring-1 ring-inset ring-fg-muted' : '', activePart && activePart !== p.key ? 'opacity-30' : '']"
            :style="{ background: p.color }"
            :aria-label="`${p.label}: ${formatSize(p.bytes)}, ${percent(shareOf(p.bytes))} of the file`"
            :data-testid="`db-growth-part-${p.key}`"
            :data-bytes="p.bytes"
            @pointerenter="activePart = p.key"
            @pointerleave="activePart = null"
            @click="activePart = p.key"
            @focus="activePart = p.key"
            @blur="activePart = null"
          />
        </li>
      </ul>
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
              class="px-2 py-1.5 font-normal text-right whitespace-nowrap"
            >
              Size
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right whitespace-nowrap"
            >
              Share
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right whitespace-nowrap"
            >
              Rows
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right whitespace-nowrap"
            >
              1 day
            </th>
            <th
              scope="col"
              class="px-2 py-1.5 font-normal text-right whitespace-nowrap"
            >
              7 days
            </th>
            <th
              scope="col"
              class="px-4 py-1.5 font-normal w-[26%]"
              data-testid="db-growth-growth-header"
            >
              {{ growthHeader }}
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="(t, i) in largest"
            :key="t.name"
            :class="activePart === tableGroup(i, t.name) ? 'bg-muted' : ''"
            :data-testid="`db-growth-row-${t.name}`"
            @pointerenter="activePart = tableGroup(i, t.name)"
            @pointerleave="activePart = null"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-mono font-normal text-left text-fg-primary"
            >
              <span class="inline-flex items-center gap-2">
                <span
                  class="size-2.5 rounded-[2px] shrink-0"
                  :style="{ background: tableColor(i) }"
                  aria-hidden="true"
                  data-testid="db-growth-swatch"
                />{{ t.name }}
              </span>
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
              v-for="(c, k) in [t.day, t.week]"
              :key="k"
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
              <div
                v-if="t.growth"
                class="grid grid-cols-[5rem_minmax(3rem,1fr)] items-start gap-2.5"
              >
                <span class="text-right whitespace-nowrap text-fg-strong">{{ signedSize(t.growth.bytes) }}</span>
                <span
                  class="relative mt-1.5 h-1.5"
                  aria-hidden="true"
                >
                  <span
                    class="absolute -inset-y-1 w-px bg-fg-muted"
                    :style="{ left: `${growthScale.zero}%` }"
                  />
                  <span
                    v-if="growthBar(t.growth).width"
                    class="absolute inset-y-0"
                    :class="growthBar(t.growth).grows ? 'rounded-r-full' : 'rounded-l-full'"
                    :style="{ left: `${growthBar(t.growth).left}%`, width: `${growthBar(t.growth).width}%`, background: DB_IN_USE_COLOR }"
                    data-testid="db-growth-bar"
                  />
                </span>
              </div>
              <span
                v-else
                class="text-fg-muted"
              ><span aria-hidden="true">—</span><span class="sr-only">no sample that old</span></span>
            </td>
          </tr>
          <template v-if="othersRow">
            <tr
              :class="activePart === 'others' ? 'bg-muted' : ''"
              data-testid="db-growth-others"
              @pointerenter="activePart = 'others'"
              @pointerleave="activePart = null"
            >
              <th
                scope="row"
                class="px-4 py-1.5 font-normal text-left"
              >
                <button
                  class="text-fg-primary hover:underline inline-flex items-center gap-2"
                  :aria-expanded="showOthers"
                  @click="showOthers = !showOthers"
                >
                  <span
                    class="size-2.5 rounded-[2px] shrink-0"
                    :style="{ background: DB_OTHER_TABLES_COLOR }"
                    aria-hidden="true"
                  />{{ others.length }} other {{ others.length === 1 ? 'table' : 'tables' }}
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
                v-for="(c, k) in [othersRow.day, othersRow.week]"
                :key="k"
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
              <td class="px-4 py-1.5">
                <div
                  v-if="othersRow.growth"
                  class="grid grid-cols-[5rem_minmax(3rem,1fr)] items-start gap-2.5"
                >
                  <span class="text-right whitespace-nowrap text-fg-strong">{{ signedSize(othersRow.growth.bytes) }}</span>
                  <span
                    class="relative mt-1.5 h-1.5"
                    aria-hidden="true"
                  >
                    <span
                      class="absolute -inset-y-1 w-px bg-fg-muted"
                      :style="{ left: `${growthScale.zero}%` }"
                    />
                    <span
                      v-if="growthBar(othersRow.growth).width"
                      class="absolute inset-y-0"
                      :class="growthBar(othersRow.growth).grows ? 'rounded-r-full' : 'rounded-l-full'"
                      :style="{ left: `${growthBar(othersRow.growth).left}%`, width: `${growthBar(othersRow.growth).width}%`, background: DB_IN_USE_COLOR }"
                      data-testid="db-growth-bar"
                    />
                  </span>
                </div>
                <span
                  v-else
                  class="text-fg-muted"
                ><span aria-hidden="true">—</span><span class="sr-only">no sample that old</span></span>
              </td>
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
                  v-for="(c, k) in [t.day, t.week]"
                  :key="k"
                  class="px-2 py-1 text-right whitespace-nowrap"
                >
                  {{ c ? signedSize(c.bytes) : '—' }}
                </td>
                <td class="px-4 py-1">
                  <div class="grid grid-cols-[5rem_minmax(3rem,1fr)] items-start gap-2.5">
                    <span class="text-right whitespace-nowrap">{{ t.growth ? signedSize(t.growth.bytes) : '—' }}</span>
                    <span
                      v-if="t.growth && growthBar(t.growth).width"
                      class="relative mt-1.5 h-1"
                      aria-hidden="true"
                    >
                      <span
                        class="absolute inset-y-0"
                        :style="{ left: `${growthBar(t.growth).left}%`, width: `${growthBar(t.growth).width}%`, background: DB_IN_USE_COLOR }"
                        data-testid="db-growth-bar"
                      />
                    </span>
                  </div>
                </td>
              </tr>
            </template>
          </template>
          <tr
            v-if="growth.otherLiveBytes !== null"
            :class="activePart === 'index' ? 'bg-muted' : ''"
            data-testid="db-growth-uncounted"
            @pointerenter="activePart = 'index'"
            @pointerleave="activePart = null"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-normal text-left text-fg-muted"
            >
              <span class="inline-flex items-center gap-2">
                <span
                  class="size-2.5 rounded-[2px] shrink-0"
                  :style="{ background: DB_INDEXES_COLOR }"
                  aria-hidden="true"
                />Indexes and other
              </span>
            </th>
            <td class="px-2 py-1.5 text-right text-fg-strong whitespace-nowrap">
              {{ formatSize(growth.otherLiveBytes) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              {{ percent(shareOf(growth.otherLiveBytes)) }}
            </td>
            <td colspan="4" />
          </tr>
          <tr
            v-if="growth.file?.reclaimableBytes != null"
            :class="activePart === 'free' ? 'bg-muted' : ''"
            data-testid="db-growth-free"
            @pointerenter="activePart = 'free'"
            @pointerleave="activePart = null"
          >
            <th
              scope="row"
              class="px-4 py-1.5 font-normal text-left text-fg-muted"
            >
              <span class="inline-flex items-center gap-2">
                <span
                  class="size-2.5 rounded-[2px] shrink-0 ring-1 ring-inset ring-fg-muted"
                  :style="{ background: DB_FREE_FILL }"
                  aria-hidden="true"
                />Free space a compaction would remove
              </span>
            </th>
            <td class="px-2 py-1.5 text-right text-fg-strong whitespace-nowrap">
              {{ formatSize(growth.file.reclaimableBytes) }}
            </td>
            <td class="px-2 py-1.5 text-right whitespace-nowrap">
              {{ percent(shareOf(growth.file.reclaimableBytes)) }}
            </td>
            <td colspan="4" />
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
            <td colspan="4" />
          </tr>
        </tfoot>
      </table>
    </div>
  </div>
</template>
