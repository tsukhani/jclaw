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
 *   in request-lifetime order, with `prologue_*` children nested under
 *   their parent (`isChild: true`). Terminal delivery sits immediately
 *   above Total so the summary row remains the last thing a reader scans.
 *   Unknown keys still surface so data never silently disappears.
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
 * Canonical top-level segment order. Terminal delivery is rendered just
 * above Total (JCLAW-102) — Total is the summary row and belongs last.
 */
export const TOP_LEVEL_ORDER = [
  'queue_wait',
  'prologue',
  'dispatcher_wait',
  'ttft',
  'stream_body',
  'tool_exec',
  'tool_round_count',
  'persist',
  'terminal_tail',
  'total',
] as const

export const TOP_LEVEL_LABELS: Record<string, string> = {
  queue_wait: 'Queue wait',
  prologue: 'Prologue',
  dispatcher_wait: 'Dispatcher wait',
  ttft: 'Time to first token',
  stream_body: 'Stream body',
  tool_exec: 'Tool execution',
  persist: 'Persist',
  total: 'Total',
  tool_round_count: 'Tool rounds / turn',
  terminal_tail: 'Terminal delivery',
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

/**
 * Voice-pipeline group (JCLAW-800). The `voice_*` segments render as a "Voice"
 * parent carrying the full-turn total ({@link VOICE_PARENT_SEGMENT} = voice_turn)
 * with the stage breakdown nested under it as children, mirroring the Prologue
 * grouping. Emitted just above Total so Total stays the final summary row.
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
    rows.push({ key: child, label: VOICE_LABELS[child] ?? child, h: ch, isChild: true })
    seen.add(child)
  }
}

export function buildLatencyRows<H extends { count: number } = LatencyHistogram>(
  metrics: Record<string, H | undefined>,
): LatencyRow<H>[] {
  const rows: LatencyRow<H>[] = []
  const seen = new Set<string>()

  for (const key of TOP_LEVEL_ORDER) {
    // The voice-pipeline group renders just above Total so Total stays last (JCLAW-800).
    if (key === 'total') appendVoiceGroup(metrics, rows, seen)
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
      appendPrologueChildren(metrics, rows, seen)
    }
  }

  for (const [key, h] of Object.entries(metrics)) {
    if (seen.has(key) || !hasSamples(h)) continue
    rows.push({ key, label: key, h, isChild: false })
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
