import { describe, it, expect } from 'vitest'
import { renderMarkdown } from '~/utils/chat-markdown'

// KaTeX keeps its input in the MathML annotation, so a formula reads back as the TeX it was given.
const tex = (math: Element) => math.querySelector('annotation')?.textContent ?? ''

/** Each top-level block of the rendered HTML: display math as its TeX, anything else as its tag and text. */
function blocks(html: string): string[] {
  const root = document.createElement('div')
  root.innerHTML = html
  return [...root.children].map((block) => {
    if (block.classList.contains('katex-display')) return `math: ${tex(block)}`
    for (const math of block.querySelectorAll('.katex')) math.replaceWith(`⟨${tex(math)}⟩`)
    return `${block.tagName.toLowerCase()}: ${block.textContent}`
  })
}

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

  // A line of only = or - under text is a setext heading underline, which split this reply's equations into headings.
  it('typesets a \\[ block whose lines hold only =, + or -', () => {
    const reply = 'A minimal model is:\n\n\\[\nH_{t+1}\n=\n(1-\\delta_H)H_t\n+\n\\alpha P_t\n-\n\\eta C_t\n\\]\n\nHere:'
    expect(blocks(renderMarkdown(reply))).toEqual([
      'p: A minimal model is:',
      'math: H_{t+1}\n=\n(1-\\delta_H)H_t\n+\n\\alpha P_t\n-\n\\eta C_t',
      'p: Here:',
    ])
  })

  it('typesets a $$ block whose lines hold only = or -', () => {
    expect(blocks(renderMarkdown('$$\nS_{t+1}\n=\nS_t\n-\n\\lambda H_t\n$$'))).toEqual([
      'math: S_{t+1}\n=\nS_t\n-\n\\lambda H_t',
    ])
  })

  it('keeps a \\[ or $$ block that holds a blank line as one formula', () => {
    expect(blocks(renderMarkdown('\\[\na = b\n\nc = d\n\\]'))).toEqual(['math: a = b\n\nc = d'])
    expect(blocks(renderMarkdown('$$\na = b\n\nc = d\n$$'))).toEqual(['math: a = b\n\nc = d'])
  })

  it('ends a paragraph at a \\[ or $$ line, so the formula below it is not read as a heading', () => {
    expect(blocks(renderMarkdown('The update is\n\\[\nx\n=\ny\n\\]'))).toEqual(['p: The update is', 'math: x\n=\ny'])
    expect(blocks(renderMarkdown('The update is\n$$\nx\n=\ny\n$$'))).toEqual(['p: The update is', 'math: x\n=\ny'])
  })

  it('still reads a heading underline when no formula opens above it', () => {
    expect(blocks(renderMarkdown('Results for $x$\n=\n\n\\[\ny\n\\]'))).toEqual(['h1: Results for ⟨x⟩', 'math: y'])
  })

  it('typesets \\[…\\] inside a paragraph where it stands', () => {
    expect(blocks(renderMarkdown('so \\[x^2\\] holds here'))).toEqual(['p: so ⟨x^2⟩ holds here'])
  })

  it('leaves a \\[ whose closing \\] has text after it to its paragraph, and typesets the next block apart', () => {
    expect(blocks(renderMarkdown('\\[a\\] and b\n\n\\[\nc\n\\]'))).toEqual(['p: ⟨a⟩ and b', 'math: c'])
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
