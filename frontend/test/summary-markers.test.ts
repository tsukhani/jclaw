import { describe, it, expect } from 'vitest'
import type { ConversationCompaction, Message } from '~/types/api'
import {
  BEFORE_FIRST_MESSAGE, mergedListLabel, placeSummaryMarkers, summaryHeadline, summaryMarkdown, summaryStatusLabel,
  summaryStatusSentence,
} from '~/utils/summary-markers'

function message(id: number, at: string): Message {
  return { id, role: id % 2 ? 'user' : 'assistant', content: `m${id}`, createdAt: at } as Message
}

function summary(id: number, over: Partial<ConversationCompaction> = {}): ConversationCompaction {
  return {
    id, rows: 10, summaryTokens: 200, model: 'ollama-cloud/gpt-oss:120b', summary: `SUMMARY ${id}`,
    compactedAt: '2026-11-14T10:30:00Z', coversFrom: '2026-11-14T10:00:00Z', coversTo: '2026-11-14T10:02:00Z',
    mergedIntoId: null, status: 'live', ...over,
  }
}

const MESSAGES = [
  message(1, '2026-11-14T10:00:00Z'),
  message(2, '2026-11-14T10:01:00Z'),
  message(3, '2026-11-14T10:02:00Z'),
  message(4, '2026-11-14T10:03:00Z'),
  message(5, '2026-11-14T10:04:00Z'),
]

describe('placeSummaryMarkers', () => {
  it('places a summary after the last message it covers, the message at its end included', () => {
    const placed = placeSummaryMarkers(MESSAGES, [summary(7)])

    expect([...placed.keys()]).toEqual([2])
    expect(placed.get(2)!.map(m => m.number)).toEqual([1])
  })

  it('keeps every summary, numbered in the order written, with what each merged and was merged into', () => {
    const first = summary(7, { coversTo: '2026-11-14T10:01:00Z', status: 'merged', mergedIntoId: 9 })
    const second = summary(8, { coversFrom: '2026-11-14T10:02:00Z', coversTo: '2026-11-14T10:03:00Z', status: 'merged', mergedIntoId: 9 })
    const merge = summary(9, { coversTo: '2026-11-14T10:03:00Z', rows: 20 })

    const placed = placeSummaryMarkers(MESSAGES, [first, second, merge])

    expect(placed.get(1)!.map(m => [m.number, m.mergedInto])).toEqual([[1, 3]])
    expect(placed.get(3)!.map(m => [m.number, m.mergedInto, m.merges])).toEqual([[2, 3, []], [3, null, [1, 2]]])
  })

  it('places a summary of rows older than every message above them, and an undated one by when it was written', () => {
    const older = summary(7, { coversTo: '2026-11-14T09:00:00Z' })
    const undated = summary(8, { coversFrom: null, coversTo: null, compactedAt: '2026-11-14T10:03:30Z' })

    const placed = placeSummaryMarkers(MESSAGES, [older, undated])

    expect(placed.get(BEFORE_FIRST_MESSAGE)!.map(m => m.number)).toEqual([1])
    expect(placed.get(3)!.map(m => m.number)).toEqual([2])
  })
})

describe('summary marker wording', () => {
  const [merged, live] = placeSummaryMarkers(MESSAGES, [
    summary(7, { status: 'merged', mergedIntoId: 8, rows: 1 }),
    summary(8, { rows: 24 }),
  ]).get(2)!

  it('names each summary, how many messages it covers and whether the agent reads it', () => {
    expect(summaryHeadline(merged!)).toBe('Summary 1 · 1 earlier message summarized')
    expect(summaryHeadline(live!)).toBe('Summary 2 · 24 earlier messages summarized')
    expect(summaryStatusLabel(merged!)).toBe('Merged into summary 2')
    expect(summaryStatusLabel(live!)).toBe('In use')
    expect(summaryStatusSentence(merged!)).toBe('Summary 2 took its place, so the agent reads that one instead.')
    expect(summaryStatusSentence(live!)).toBe('The agent reads this summary in place of the messages it covers.')
  })

  it('says a summary a /reset set aside is no longer read', () => {
    const [reset] = placeSummaryMarkers(MESSAGES, [summary(7, { status: 'reset' })]).get(2)!
    expect(summaryStatusLabel(reset!)).toBe('Set aside by /reset')
    expect(summaryStatusSentence(reset!)).toBe('A /reset set it aside, so the agent no longer reads it.')
  })

  it('lists merged summaries in plain English', () => {
    expect(mergedListLabel([1])).toBe('Summary 1')
    expect(mergedListLabel([1, 2])).toBe('Summaries 1 and 2')
    expect(mergedListLabel([1, 2, 3])).toBe('Summaries 1, 2 and 3')
  })

  it('exports a summary as a quoted block with its headline, status, author and text', () => {
    const block = summaryMarkdown(live!)
    const lines = block.trimEnd().split('\n')

    expect(lines[0]).toBe('> **Summary 2 · 24 earlier messages summarized** · In use')
    expect(lines[1]).toMatch(/^> Written .+ by ollama-cloud\/gpt-oss:120b · covers .+ – .+$/)
    expect(lines.slice(2)).toEqual(['>', '> SUMMARY 8'])
  })
})
