import { describe, it, expect } from 'vitest'
import { normalizeMarkdownLinks, renderMarkdown, renderMarkdownStreaming, formatTokensPerSec } from '~/utils/chat-markdown'
import type { MessageUsage } from '~/utils/usage-cost'

describe('normalizeMarkdownLinks', () => {
  it('wraps whitespace destinations in angle brackets', () => {
    expect(normalizeMarkdownLinks('[a](b c.docx)')).toBe('[a](<b c.docx>)')
  })

  it('leaves already-angle-wrapped destinations untouched', () => {
    expect(normalizeMarkdownLinks('[a](<b c.docx>)')).toBe('[a](<b c.docx>)')
  })

  it('leaves spaceless destinations untouched', () => {
    expect(normalizeMarkdownLinks('[a](https://x.test/p)')).toBe('[a](https://x.test/p)')
  })
})

describe('renderMarkdown /api/ allow-list', () => {
  it('keeps img src pointing at our API', () => {
    const html = renderMarkdown('![img](/api/attachments/abc.png)')
    expect(html).toContain('src="/api/attachments/abc.png"')
  })

  it('keeps download href pointing at our API', () => {
    const html = renderMarkdown('<a href="/api/agents/1/files/x.pdf" download>x</a>')
    expect(html).toContain('href="/api/agents/1/files/x.pdf"')
  })

  it('rewrites relative workspace links when an agentId is supplied', () => {
    const html = renderMarkdown('[report.pdf](report.pdf)', 7)
    expect(html).toContain('/api/agents/7/files/')
  })
})

describe('renderMarkdownStreaming', () => {
  it('returns empty string for empty input', () => {
    expect(renderMarkdownStreaming('')).toBe('')
  })

  it('produces the same HTML as renderMarkdown for a given input (cache bypass aside)', () => {
    const text = '**bold** [x](/api/z)'
    expect(renderMarkdownStreaming(text)).toBe(renderMarkdown(text))
  })
})

describe('formatTokensPerSec', () => {
  it('returns null when duration or completion is missing', () => {
    expect(formatTokensPerSec({ durationMs: 0, completion: 10 } as MessageUsage)).toBeNull()
    expect(formatTokensPerSec({ durationMs: 1000, completion: 0 } as MessageUsage)).toBeNull()
  })

  it('formats tokens per second to one decimal', () => {
    expect(formatTokensPerSec({ durationMs: 2000, completion: 50 } as MessageUsage)).toBe('25.0 tok/s')
  })
})
