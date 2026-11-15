import type { ConversationCompaction, Message } from '~/types/api'

/** One stored summary as the conversation page shows it. */
export interface SummaryMarker {
  compaction: ConversationCompaction
  /** 1-based, in the order the summaries were written. */
  number: number
  /** The number of the summary this one was merged into, once it was. */
  mergedInto: number | null
  /** The numbers of the summaries this one merged. */
  merges: number[]
}

/** Before the first message: a summary of rows older than every message the page holds. */
export const BEFORE_FIRST_MESSAGE = -1

/**
 * Each summary keyed by the index of the message it follows, the last one it covers; several summaries can follow
 * one message, a merge and the last summary it folded in among them, and keep the order they were written in.
 * A summary too old to have been dated is placed by when it was written.
 */
export function placeSummaryMarkers(messages: Message[], compactions: ConversationCompaction[]): Map<number, SummaryMarker[]> {
  const numberOf = new Map(compactions.map((c, i) => [c.id, i + 1]))
  const placed = new Map<number, SummaryMarker[]>()
  compactions.forEach((compaction, i) => {
    const end = Date.parse(compaction.coversTo ?? compaction.compactedAt)
    let at = BEFORE_FIRST_MESSAGE
    messages.forEach((m, j) => {
      if (Date.parse(m.createdAt) <= end) at = j
    })
    const marker: SummaryMarker = {
      compaction,
      number: i + 1,
      mergedInto: compaction.mergedIntoId != null ? numberOf.get(compaction.mergedIntoId) ?? null : null,
      merges: compactions.filter(o => o.mergedIntoId === compaction.id).map(o => numberOf.get(o.id)!),
    }
    placed.set(at, [...(placed.get(at) ?? []), marker])
  })
  return placed
}

function messagesLabel(rows: number): string {
  return `${rows.toLocaleString()} earlier message${rows === 1 ? '' : 's'}`
}

/** "Summary 2 · 24 earlier messages summarized". */
export function summaryHeadline(marker: SummaryMarker): string {
  return `Summary ${marker.number} · ${messagesLabel(marker.compaction.rows)} summarized`
}

/** The short status beside the headline. */
export function summaryStatusLabel(marker: SummaryMarker): string {
  if (marker.compaction.status === 'merged') {
    return marker.mergedInto != null ? `Merged into summary ${marker.mergedInto}` : 'Merged'
  }
  if (marker.compaction.status === 'reset') return 'Set aside by /reset'
  return 'In use'
}

/** What the status means for what the agent reads. */
export function summaryStatusSentence(marker: SummaryMarker): string {
  if (marker.compaction.status === 'merged') {
    return marker.mergedInto != null
      ? `Summary ${marker.mergedInto} took its place, so the agent reads that one instead.`
      : 'A later summary took its place, so the agent reads that one instead.'
  }
  if (marker.compaction.status === 'reset') return 'A /reset set it aside, so the agent no longer reads it.'
  return 'The agent reads this summary in place of the messages it covers.'
}

/** "Summaries 1 and 2", "Summaries 1, 2 and 3", "Summary 1". */
export function mergedListLabel(numbers: number[]): string {
  if (numbers.length === 1) return `Summary ${numbers[0]}`
  return `Summaries ${numbers.slice(0, -1).join(', ')} and ${numbers.at(-1)}`
}

/** The span of messages a summary covers, or null when it was written too long ago to have been dated. */
export function summaryCoversLabel(compaction: ConversationCompaction): string | null {
  if (!compaction.coversFrom || !compaction.coversTo) return null
  return `${new Date(compaction.coversFrom).toLocaleString()} – ${new Date(compaction.coversTo).toLocaleString()}`
}

/** The summary as a quoted block in the conversation's Markdown export. */
export function summaryMarkdown(marker: SummaryMarker): string {
  const c = marker.compaction
  const details = [`Written ${new Date(c.compactedAt).toLocaleString()} by ${c.model}`]
  const covers = summaryCoversLabel(c)
  if (covers) details.push(`covers ${covers}`)
  if (marker.merges.length) details.push(`merges ${mergedListLabel(marker.merges).toLowerCase()}`)
  const lines = [
    `**${summaryHeadline(marker)}** · ${summaryStatusLabel(marker)}`,
    details.join(' · '),
    '',
    ...c.summary.split('\n'),
  ]
  const quoted = lines.map(line => (line ? `> ${line}` : '>')).join('\n')
  return `${quoted}\n`
}
