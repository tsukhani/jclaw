import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '~/utils/chat-markdown'

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

  it('typesets $…$ math, keeping its underscores away from emphasis', () => {
    const result = renderMarkdown('$N = 4(p_1\\cdots p_n) - 1$, so it is odd.')
    expect(result).toContain('class="katex"')
    expect(result).not.toContain('<em>')
    expect(result).toContain('<annotation encoding="application/x-tex">N = 4(p_1\\cdots p_n) - 1</annotation>')
  })

  it('typesets \\(…\\) inline and $$…$$ and \\[…\\] as display math', () => {
    expect(renderMarkdown('Inline \\(a^2+b^2=c^2\\) here.')).toContain('class="katex"')
    expect(renderMarkdown('$$P = p_1 p_2$$')).toContain('katex-display')
    expect(renderMarkdown('\\[\n\\int_0^1 x\\,dx\n\\]')).toContain('katex-display')
  })

  it('closes math right before a bracket, and keeps it out of the next pair', () => {
    const result = renderMarkdown('(since $N$ is odd, none can be $2$), so each $q_j$ is odd.')
    expect(result).not.toContain('katex-error')
    expect(result).toContain('<annotation encoding="application/x-tex">2</annotation>')
    expect(result).toContain('<annotation encoding="application/x-tex">q_j</annotation>')
  })

  it('keeps an inline $$…$$ in its paragraph', () => {
    const result = renderMarkdown('so $$x^2$$ holds here')
    expect(result.match(/<p>/g)).toHaveLength(1)
    expect(result).toContain('katex-display')
  })

  it('typesets a $$ block on its own lines', () => {
    expect(renderMarkdown('Let\n\n$$\nP = p_1 p_2\n$$\n\nthen')).toContain('katex-display')
  })

  it('leaves dollar amounts in prose alone', () => {
    expect(renderMarkdown('It costs $5 and $10 today.')).toBe('<p>It costs $5 and $10 today.</p>\n')
    expect(renderMarkdown('Budget $20,000 and $30,000.')).not.toContain('katex')
  })

  it('shows malformed TeX as an error span instead of throwing', () => {
    expect(renderMarkdown('Bad: $\\frac{1}{$ end')).toContain('katex-error')
  })

  it('does not let math smuggle a javascript: link', () => {
    // KaTeX refuses \\href without `trust`; the TeX survives only as annotation text.
    const result = renderMarkdown('$\\href{javascript:alert(1)}{x}$ done')
    expect(result).not.toMatch(/href=|<a[\s>]/)
  })
})
