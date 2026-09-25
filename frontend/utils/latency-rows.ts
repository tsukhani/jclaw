/**
 * Row-building helpers for the dashboard Chat Performance panel.
 *
 * The backend partitions histograms by channel (JCLAW-102) and the panel
 * renders a single table whose content rotates via a channel dropdown —
 * so these helpers operate on a flat segment map (one channel's data at
 * a time) plus one enumeration helper that lists the channels available
 * for the dropdown.
 *
 * Three consumer-facing functions:
 *
 * - **`buildLatencyRows(metrics)`** — build the table rows for one channel,
 *   with `prologue_*` children nested under their parent (`isChild: true`).
 *   The order is fixed (JCLAW-870):
 *
 *     1. the Voice group, when present — `voice_turn` *encloses* the LLM leg
 *        rather than sitting beside it, and `voice_stt` precedes every chat
 *        segment, so the enclosing span leads its children
 *     2. the chat pipeline in request-lifetime order, `queue_wait` through
 *        `terminal_tail` — the additive chain Total sums
 *     3. any segment this file has no ordering for, keyed by its raw name so
 *        data never silently disappears
 *     4. **Total, always last** — structurally, not by luck
 *
 *   Rule 4 is the invariant worth protecting: Total is the summary row and a
 *   reader scans to the bottom for it. It used to hold only because
 *   `TOP_LEVEL_ORDER` happened to enumerate everything the backend emitted, so
 *   a segment the frontend didn't know about pushed itself *below* the summary.
 *
 * - **`buildChartSeries(metrics)`** — top-level segments only, for the
 *   overlay chart. `prologue_*` children are suppressed because their
 *   contribution is already represented by the `Prologue` line they
 *   sum to.
 *
 * - **`listAvailableChannels(payload)`** — enumerate channels with at
 *   least one sampled segment, ordered web → telegram → task → webhook
 *   → anything else alphabetical. Powers the dropdown.
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

export interface LatencyRow<H extends { count: number } = LatencyHistogram> {
  key: string
  label: string
  h: H
  /** True when this row is a `prologue_*` child decomposition. Drives indentation. */
  isChild: boolean
}

export interface ChannelOption {
  key: string
  label: string
}

/**
 * Map the backend's channel identifier (matches `Conversation.channelType`)
 * to the label shown in the Chat Performance dropdown. Unknown channels
 * fall through to a title-cased version of the raw string — new channels
 * surface without a code change, they just get an auto-generated label.
 */
const CHANNEL_LABELS: Record<string, string> = {
  web: 'Web',
  telegram: 'Telegram',
  task: 'Scheduled tasks',
  webhook: 'Webhook',
}

/** Order channels render in the dropdown. Unknown channels append alphabetically. */
const CHANNEL_ORDER = ['web', 'telegram', 'task', 'webhook'] as const

/**
 * The `LatencyStats.UNKNOWN_CHANNEL` fallback bucket the backend records for
 * callers with no chat-channel context (embedding recall, slash compaction,
 * skill promotion). These are system-internal LLM calls, not chats, so the
 * value is suppressed from the Chat Performance channel selector.
 */
export const UNKNOWN_CHANNEL = 'unknown'

function labelForChannel(channel: string): string {
  if (CHANNEL_LABELS[channel]) return CHANNEL_LABELS[channel]
  if (!channel) return 'Unknown'
  return channel.charAt(0).toUpperCase() + channel.slice(1)
}

/**
 * Canonical order for the additive chat chain. Terminal delivery is the last
 * stage before the summary (JCLAW-102); unknown segments render between it and
 * Total (JCLAW-870), so the two are adjacent only when there are none.
 */
export const TOP_LEVEL_ORDER = [
  'queue_wait',
  'prologue',
  'dispatcher_wait',
  'ttft',
  'stream_body',
  'tool_exec',
  'persist',
  'terminal_tail',
  'total',
] as const

/** Membership test for {@link TOP_LEVEL_ORDER}, so the unknown-segment pass can
 *  tell "this file has no ordering for it" from "its turn in the walk hasn't
 *  come up yet". */
const TOP_LEVEL_KEYS: ReadonlySet<string> = new Set(TOP_LEVEL_ORDER)

export const TOP_LEVEL_LABELS: Record<string, string> = {
  queue_wait: 'Queue wait',
  prologue: 'Prologue',
  dispatcher_wait: 'Dispatcher wait',
  // TTFT is the wait until the model emits anything at all, which is what the
  // term means everywhere else. The older segment stops at the first token the
  // reader sees, so on a turn that reasons or calls a tool first it also covers
  // that work — named for the text to keep the two apart.
  ttft_first_signal: 'TTFT',
  ttft: 'Time to first text',
  stream_body: 'Stream body',
  // Named so the arithmetic reads off the table: Time to first text = TTFT plus
  // whichever of these the turn emitted. Each covers the same span — from the
  // model's first output to the first text a reader sees — so a turn emits one or
  // neither, never both. "Reasoning time" and "Tool round gap" said neither what
  // the span was nor that it ended at the text.
  tool_gap: 'Tool rounds before text',
  think_time: 'Reasoning before text',
  tool_exec: 'Tool execution',
  persist: 'Persist',
  total: 'Total',
  terminal_tail: 'Terminal delivery',
  memory_recall: 'Memory recall',
  // The only segment on the `browser` channel: the SPA's own INP reports, never part of a turn.
  inp: 'Interaction to next paint (INP)',
}

/**
 * Wire key to sentence case, for a segment none of the maps here name. The
 * backend adds segments without this file changing — `memory_recall` shipped and
 * rendered to operators as `memory_recall` — so the fallback has to be a label
 * rather than the raw key. Sentence case, not title case, to match the maps.
 */
export function humanizeSegment(key: string): string {
  const words = key.replaceAll('_', ' ').trim()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/**
 * Segments whose samples are cardinalities, not durations (JCLAW-882, split out
 * by JCLAW-884). They ride the same histogram pipeline as the latency segments —
 * that is what gives them percentiles and the agent/channel filters for free —
 * but they do not belong in the same *table*.
 *
 * Two reasons they are their own view rather than rows with a different unit:
 * the latency table's Total sums the additive chain, and a cardinality has no
 * relationship to that summary; and counts want an aggregate durations do not,
 * the sum across the window, which is the cost number for LLM calls and is
 * meaningless for a duration.
 *
 * The `_count` suffix is honoured as well as the explicit list so a segment the
 * backend adds later lands in the right view instead of appearing as an unknown
 * row in the latency table. `llm_call_cached` and `tool_verify_failed` are named
 * for what they count rather than suffixed, so they are listed explicitly —
 * without that, they render as duration rows and get summed into Total.
 */
const COUNT_SEGMENTS: ReadonlySet<string> = new Set([
  'tool_round_count',
  'llm_call_count',
  'llm_call_cached',
  'tool_verify_failed',
])

export function isCountSegment(key: string): boolean {
  return COUNT_SEGMENTS.has(key) || key.endsWith('_count')
}

/** Order of the counts view. Calls first — it is the number the JCLAW-833
 *  efficiency NFR is written against — then what it decomposes into. */
export const COUNT_ORDER = [
  'llm_call_count',
  'llm_call_cached',
  'tool_round_count',
  'tool_verify_count',
  'tool_verify_failed',
] as const

export const COUNT_LABELS: Record<string, string> = {
  llm_call_count: 'LLM calls / turn',
  llm_call_cached: 'Cache-served calls / turn',
  tool_round_count: 'Tool rounds / turn',
  tool_verify_count: 'Tool results checked / turn',
  tool_verify_failed: 'Tool results flagged / turn',
  memory_recall_count: 'Memory recalls / turn',
}

/** Histograms carry a windowed sum alongside the percentiles. Meaningless for a
 *  duration, which is why nothing surfaced it before; for a count it is the total
 *  over the window. */
interface HasSum { count: number, sum_ms?: number }

/**
 * Share of LLM calls served from the provider's prompt cache, or null when there
 * is nothing to divide.
 *
 * A ratio of SUMS, deliberately — not a difference of percentiles, and not a
 * ratio of sample counts. Both segments are suppressed at zero and the backend
 * clamps recorded values to a minimum of 1, so a turn with no cache-served call
 * emits no `llm_call_cached` sample at all. Comparing p50s or sample counts would
 * therefore compare different populations; only the summed cardinalities are
 * commensurable. Do not "simplify" this into a percentile comparison.
 */
export function cachedCallShare<H extends HasSum>(metrics: Record<string, H | undefined>): number | null {
  const total = metrics.llm_call_count?.sum_ms ?? 0
  const cached = metrics.llm_call_cached?.sum_ms ?? 0
  if (!total) return null
  return Math.min(1, cached / total)
}

/**
 * Build the rows for the counts view. Mirrors {@link buildLatencyRows} but over
 * {@link COUNT_ORDER}, and surfaces any unrecognised count-suffixed segment after
 * the known ones so a new backend counter shows up rather than vanishing between
 * the two views (the JCLAW-870 rule, applied per-kind).
 */
export function buildCountRows<H extends { count: number } = LatencyHistogram>(
  metrics: Record<string, H | undefined>,
): LatencyRow<H>[] {
  const rows: LatencyRow<H>[] = []
  const seen = new Set<string>()
  for (const key of COUNT_ORDER) {
    const h = metrics[key]
    if (!hasSamples(h)) continue
    rows.push({ key, label: COUNT_LABELS[key] ?? humanizeSegment(key), h, isChild: false })
    seen.add(key)
  }
  for (const [key, h] of Object.entries(metrics)) {
    if (seen.has(key) || !isCountSegment(key) || !hasSamples(h)) continue
    rows.push({ key, label: COUNT_LABELS[key] ?? humanizeSegment(key), h, isChild: false })
  }
  return rows
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
  // The three below decompose Prompt rather than continuing the chain, so they
  // follow it and do not sum with their siblings.
  'prologue_assemble',
  'prologue_rewrite',
  'prologue_embed_query',
] as const

export const PROLOGUE_CHILD_LABELS: Record<string, string> = {
  prologue_parse: 'Parse',
  prologue_conv: 'Conversation',
  prologue_tools: 'Tools',
  prologue_prompt: 'Prompt',
  prologue_assemble: 'Assemble',
  prologue_rewrite: 'Rewrite',
  prologue_embed_query: 'Query embedding',
}

function isPrologueChildKey(key: string): boolean {
  return key.startsWith('prologue_')
}

function labelForChild(key: string): string {
  return PROLOGUE_CHILD_LABELS[key] ?? key.replace(/^prologue_/, '')
}

/**
 * Voice-pipeline group (JCLAW-800). The `voice_*` segments render as a "Voice"
 * parent carrying the full-turn total ({@link VOICE_PARENT_SEGMENT} = voice_turn)
 * with the stage breakdown nested under it as children, mirroring the Prologue
 * grouping.
 *
 * <p>Rendered at the TOP of the table (JCLAW-870), which is where the spans put
 * it. These are cumulative-from-endpoint measurements, not disjoint stages that
 * sum toward Total: `voice_stt` is endpoint → transcript and so precedes every
 * chat segment, while `voice_turn` is endpoint → turn complete and *encloses*
 * the whole LLM leg below it — measured at p50 6.4&nbsp;s against a chat Total of
 * 2.1&nbsp;s. It was previously emitted just above Total, which read as if STT
 * happened after terminal delivery and split Terminal delivery from the summary
 * row it feeds.
 */
const VOICE_PARENT_SEGMENT = 'voice_turn'
export const VOICE_CHILDREN_ORDER = ['voice_stt', 'voice_tts_synth', 'voice_reply'] as const
export const VOICE_LABELS: Record<string, string> = {
  voice_turn: 'Voice',
  voice_stt: 'STT',
  voice_tts_synth: 'TTS synthesis',
  voice_reply: 'First audio',
}

function hasSamples<H extends { count: number }>(h: H | undefined | null): h is H {
  return !!h && typeof h.count === 'number' && h.count > 0
}

/**
 * Build the flat row list for a single channel's histograms.
 * `prologue_*` rows are emitted immediately after the `prologue` row
 * (if present), in PROLOGUE_CHILDREN_ORDER followed by any unknown
 * prologue_* keys in encounter order. Pass `metrics` for the currently
 * selected channel only — callers pick the channel via the dropdown,
 * this helper is single-channel.
 */
/**
 * Append all prologue_* children for the prologue parent: the well-known
 * children in {@link PROLOGUE_CHILDREN_ORDER} first, then any unknown
 * prologue_* keys in encounter order. Mutates {@code rows} and {@code seen}.
 */
function appendPrologueChildren<H extends { count: number }>(
  metrics: Record<string, H | undefined>,
  rows: LatencyRow<H>[],
  seen: Set<string>,
): void {
  const emitChild = (key: string, h: H | undefined) => {
    if (!hasSamples(h)) return
    rows.push({ key, label: labelForChild(key), h, isChild: true })
    seen.add(key)
  }

  for (const child of PROLOGUE_CHILDREN_ORDER) emitChild(child, metrics[child])
  for (const [mk, mh] of Object.entries(metrics)) {
    if (seen.has(mk) || !isPrologueChildKey(mk)) continue
    emitChild(mk, mh)
  }
}

/**
 * Emit the voice-pipeline group (JCLAW-800): a "Voice" parent row carrying the
 * full-turn total ({@link VOICE_PARENT_SEGMENT} = voice_turn), with the stage
 * breakdown (voice_stt / voice_tts_synth / voice_reply) nested as children.
 * Skipped when there is no completed-turn voice data — a partial run's stray
 * voice_* keys then fall through to the unknown-key catch-all so nothing
 * disappears. Mirrors {@link appendPrologueChildren}. Mutates {@code rows}/{@code seen}.
 */
function appendVoiceGroup<H extends { count: number }>(
  metrics: Record<string, H | undefined>,
  rows: LatencyRow<H>[],
  seen: Set<string>,
): void {
  const parent = metrics[VOICE_PARENT_SEGMENT]
  if (!hasSamples(parent)) return
  rows.push({ key: 'voice', label: VOICE_LABELS[VOICE_PARENT_SEGMENT]!, h: parent, isChild: false })
  seen.add(VOICE_PARENT_SEGMENT)
  for (const child of VOICE_CHILDREN_ORDER) {
    const ch = metrics[child]
    if (!hasSamples(ch)) continue
    rows.push({ key: child, label: VOICE_LABELS[child] ?? humanizeSegment(child), h: ch, isChild: true })
    seen.add(child)
  }
}

/**
 * Emit every sampled segment this file has no ordering for, keyed by its raw
 * name. Called immediately before Total (JCLAW-870) rather than after the
 * canonical walk, so an unrecognised segment can never displace the summary row.
 *
 * <p>Deliberately not a hard-coded skip list: a segment added backend-first,
 * before the frontend learns its label, should still be visible — just not
 * below the row that is supposed to close the table. Mutates {@code rows} and
 * {@code seen}.
 */
function appendUnknownSegments<H extends { count: number }>(
  metrics: Record<string, H | undefined>,
  rows: LatencyRow<H>[],
  seen: Set<string>,
): void {
  for (const [key, h] of Object.entries(metrics)) {
    // `seen` is not enough on its own: this runs partway through the canonical
    // walk, so Total — and any known segment still ahead of it — has not been
    // emitted yet and would otherwise be mistaken for an unknown and duplicated.
    if (seen.has(key) || TOP_LEVEL_KEYS.has(key) || !hasSamples(h)) continue
    // A cardinality belongs to the counts view (JCLAW-884). Without this it would
    // land here as an unknown row, which is exactly the mixing the split removes —
    // and it would sit inside a table whose Total does not summarise it.
    if (isCountSegment(key)) continue
    rows.push({ key, label: TOP_LEVEL_LABELS[key] ?? humanizeSegment(key), h, isChild: false })
    seen.add(key)
  }
}

export function buildLatencyRows<H extends { count: number } = LatencyHistogram>(
  metrics: Record<string, H | undefined>,
): LatencyRow<H>[] {
  const rows: LatencyRow<H>[] = []
  const seen = new Set<string>()

  // The enclosing span leads (JCLAW-870): a voice turn starts with STT and ends
  // after the chat pipeline it wraps, so the group belongs above that pipeline
  // rather than wedged between its last stage and its summary.
  appendVoiceGroup(metrics, rows, seen)

  for (const key of TOP_LEVEL_ORDER) {
    // Anything unrecognised goes here — immediately before Total, never after.
    if (key === 'total') appendUnknownSegments(metrics, rows, seen)
    const h = metrics[key]
    const parentEmitted = hasSamples(h)
    if (parentEmitted) {
      rows.push({ key, label: TOP_LEVEL_LABELS[key] ?? humanizeSegment(key), h, isChild: false })
      seen.add(key)
    }
    // Nest prologue children immediately under the parent — only when the
    // parent was actually emitted. If prologue is absent, any stray
    // prologue_* keys fall through to the unknown-segment block so the
    // operator still sees the data instead of it silently disappearing.
    if (key === 'prologue' && parentEmitted) {
      appendPrologueChildren(metrics, rows, seen)
    }
  }

  return rows
}

/**
 * Enumerate channels that have at least one sampled segment. Drives the
 * Chat Performance dropdown; empty channels are suppressed so the user
 * never sees a selector option that would render an empty table.
 */
export function listAvailableChannels<H extends { count: number } = LatencyHistogram>(
  payload: Record<string, Record<string, H | undefined> | undefined>,
): ChannelOption[] {
  const hasAnySample = (channel: string): boolean => {
    const metrics = payload[channel]
    if (!metrics) return false
    for (const h of Object.values(metrics)) {
      if (hasSamples(h)) return true
    }
    return false
  }

  const seen = new Set<string>()
  const options: ChannelOption[] = []

  for (const channel of CHANNEL_ORDER) {
    if (seen.has(channel)) continue
    if (hasAnySample(channel)) {
      options.push({ key: channel, label: labelForChannel(channel) })
      seen.add(channel)
    }
  }
  // Any channel not in CHANNEL_ORDER, alphabetically. The UNKNOWN_CHANNEL
  // bucket is suppressed (see its doc) so the per-channel chat view stays
  // clean; the data still lives in LatencyStats for raw inspection if needed.
  const remaining = Object.keys(payload)
    .filter(c => !seen.has(c) && c !== UNKNOWN_CHANNEL)
    .sort((a, b) => a.localeCompare(b))
  for (const channel of remaining) {
    if (hasAnySample(channel)) {
      options.push({ key: channel, label: labelForChannel(channel) })
      seen.add(channel)
    }
  }

  return options
}

/**
 * Build the series list the overlay chart consumes for a single channel's
 * segments. Suppresses `prologue_*` children (their contribution is already
 * the Prologue sum) but keeps the voice stage rows, which are distinct metrics.
 */
export function buildChartSeries<H extends { count: number } = LatencyHistogram>(
  metrics: Record<string, H | undefined>,
): Array<{ key: string, label: string, histogram: H }> {
  return buildLatencyRows<H>(metrics)
    // Drop only prologue_* children (their contribution is already the Prologue
    // sum); keep the voice stage rows, which are distinct non-summed metrics.
    .filter(r => !(r.isChild && isPrologueChildKey(r.key)))
    .map(r => ({ key: r.key, label: r.label, histogram: r.h }))
}
