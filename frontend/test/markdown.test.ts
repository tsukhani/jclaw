import { describe, it, expect } from 'vitest'
// Exercise the real production renderer rather than a hand-rolled mirror —
// the previous version of this file inlined its own marked + DOMPurify
// pipeline which silently drifted from chat.vue's implementation. Now both
// chat.vue and conversations/[id].vue import this same function, and the
// test contract is the same code that ships.
import { renderMarkdown } from '~/utils/markdown'

describe('renderMarkdown', () => {
  it('returns empty string for empty input', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null as unknown as string)).toBe('')
    expect(renderMarkdown(undefined as unknown as string)).toBe('')
  })

  it('renders basic markdown', () => {
    const result = renderMarkdown('**bold** and *italic*')
    expect(result).toContain('<strong>bold</strong>')
    expect(result).toContain('<em>italic</em>')
  })

  it('renders code blocks', () => {
    const result = renderMarkdown('```js\nconsole.log("hi")\n```')
    expect(result).toContain('<code')
    expect(result).toContain('console.log')
  })

  it('renders lists', () => {
    const result = renderMarkdown('- item 1\n- item 2')
    expect(result).toContain('<li>')
    expect(result).toContain('item 1')
  })

  it('renders links', () => {
    const result = renderMarkdown('[example](https://example.com)')
    expect(result).toContain('<a')
    expect(result).toContain('https://example.com')
  })

  it('strips script tags (XSS prevention)', () => {
    const result = renderMarkdown('<script>alert("xss")</script>')
    expect(result).not.toContain('<script>')
    expect(result).not.toContain('alert')
  })

  it('strips onerror attributes (XSS prevention)', () => {
    const result = renderMarkdown('<img src=x onerror=alert(1)>')
    expect(result).not.toContain('onerror')
  })

  it('strips javascript: URLs (XSS prevention)', () => {
    const result = renderMarkdown('[click](javascript:alert(1))')
    expect(result).not.toContain('javascript:')
  })

  it('strips event handlers in HTML', () => {
    const result = renderMarkdown('<div onclick="alert(1)">click me</div>')
    expect(result).not.toContain('onclick')
  })

  it('preserves safe HTML elements', () => {
    const result = renderMarkdown('# Heading\n\nParagraph text')
    expect(result).toContain('<h1')
    expect(result).toContain('<p>')
  })

  it('handles GFM line breaks', () => {
    const result = renderMarkdown('line 1\nline 2')
    expect(result).toContain('<br')
  })

  it('renders file links whose destinations contain spaces', () => {
    // LLMs often emit `[name.docx](name.docx)` for workspace files. Without
    // normalization, marked leaves this as literal text because CommonMark
    // link destinations cannot contain whitespace unless wrapped in <...>.
    const result = renderMarkdown('[Shiva Play - Enhanced Version.docx](Shiva Play - Enhanced Version.docx)')
    expect(result).toContain('<a')
    // marked percent-encodes spaces inside link destinations.
    expect(result).toContain('href="Shiva%20Play%20-%20Enhanced%20Version.docx"')
    expect(result).toContain('Shiva Play - Enhanced Version.docx</a>')
  })

  it('leaves spaceless link destinations untouched', () => {
    const result = renderMarkdown('[docs](https://example.com/path)')
    expect(result).toContain('href="https://example.com/path"')
  })
})
