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

  it('leaves an angle-wrapped destination containing parens untouched', () => {
    // Truncating at the first `)` re-wrapped the fragment as `(<<a (1>)/b.epub>)`,
    // which marked then rendered as literal text instead of an anchor.
    const src = '[b.epub](<books/A Title (1234)/b.epub>)'
    expect(normalizeMarkdownLinks(src)).toBe(src)
  })

  it('wraps a bare destination whose balanced parens precede a space', () => {
    expect(normalizeMarkdownLinks('[b.epub](books/A Title (1234)/b.epub)'))
      .toBe('[b.epub](<books/A Title (1234)/b.epub>)')
  })
})

describe('renderMarkdown workspace file links', () => {
  const href = '/api/agents/7/files/books/A%20Title%20(1234)/b.epub'

  it('renders a parenthesised, space-bearing filename as a real anchor', () => {
    const html = renderMarkdown('[b.epub](<books/A Title (1234)/b.epub>)', 7)
    expect(html).toContain(`<a href="${href}"`)
    expect(html).not.toContain('&lt;')
  })

  it('renders the same file written without angle brackets', () => {
    // Asserted on the whole path, not just the prefix: the truncating pattern
    // still produced an anchor here, just one pointing at half the filename.
    const html = renderMarkdown('[b.epub](books/A Title (1234)/b.epub)', 7)
    expect(html).toContain(`<a href="${href}"`)
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

describe('renderMarkdown code-block copy button', () => {
  it('wraps a fenced block and emits a copy button that survives sanitization', () => {
    const html = renderMarkdown('```js\nconsole.log("hi")\n```')
    expect(html).toContain('<div class="code-block">')
    expect(html).toContain('<button type="button" class="code-copy">Copy</button>')
  })

  it('leaves the code text free of the button label', () => {
    const doc = new DOMParser().parseFromString(
      renderMarkdown('```py\na = 1\nb = 2\n```'),
      'text/html',
    )
    expect(doc.querySelector('.code-block pre code')?.textContent).toBe('a = 1\nb = 2\n')
  })

  it('does not decorate inline code spans', () => {
    const html = renderMarkdown('use `yield` here')
    expect(html).not.toContain('code-copy')
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

// prose-chat.css hides a task item's marker by its shape: the checkbox first in the item, or first in its paragraph.
describe('renderMarkdown task lists', () => {
  it('puts a tight task item\'s checkbox first in the item', () => {
    expect(renderMarkdown('- [ ] open\n- [x] done')).toBe(
      '<ul>\n<li><input disabled="" type="checkbox"> open</li>\n<li><input checked="" disabled="" type="checkbox"> done</li>\n</ul>\n')
  })

  it('puts a loose task item\'s checkbox first in its paragraph', () => {
    expect(renderMarkdown('- [ ] open\n\n- [x] done')).toBe(
      '<ul>\n<li><p><input disabled="" type="checkbox"> open</p>\n</li>\n<li><p><input checked="" disabled="" type="checkbox"> done</p>\n</li>\n</ul>\n')
  })
})
