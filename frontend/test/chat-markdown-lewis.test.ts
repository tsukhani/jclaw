import { beforeAll, describe, it, expect, vi } from 'vitest'
import SmilesDrawer from 'smiles-drawer'
import { renderMarkdown, renderMarkdownStreaming } from '~/utils/chat-markdown'
import { isLewisLoaded, lewisVersion, renderLewis } from '~/utils/lewis'

// SmilesDrawer loads once per module instance, so the first test here sees it not loaded yet.
describe('renderMarkdown lewis fence', () => {
  const fence = (smiles: string) => `Water:\n\n\`\`\`lewis\n${smiles}\n\`\`\`\n`
  const parseHtml = (html: string) => new DOMParser().parseFromString(html, 'text/html')

  beforeAll(() => {
    // jsdom has no canvas; SmilesDrawer then estimates label widths instead of logging each miss.
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
  })

  it('shows the code block until SmilesDrawer loads, and the render starts the load', async () => {
    expect(isLewisLoaded()).toBe(false)
    const html = renderMarkdown(fence('[H]O[H]'))
    expect(html).toContain('<code class="language-lewis">[H]O[H]')
    expect(html).not.toContain('<svg')
    await vi.waitFor(() => expect(lewisVersion.value).toBe(1), { timeout: 10_000 })
  })

  it('draws the same message once loaded, past the cached code block', () => {
    const doc = parseHtml(renderMarkdown(fence('[H]O[H]')))
    const svg = doc.querySelector('figure.lewis > svg')!
    expect(svg).not.toBeNull()
    expect(doc.querySelector('pre')).toBeNull()
    expect(svg.getAttribute('role')).toBe('img')
    expect(svg.getAttribute('aria-label')).toBe('Lewis structure of H2O, SMILES [H]O[H]')
    expect(svg.getAttribute('viewBox')).toMatch(/^-?[\d.]+ -?[\d.]+ [\d.]+ [\d.]+$/)
    expect(svg.querySelectorAll('circle')).toHaveLength(4)
    expect([...svg.querySelectorAll('text')].map(t => t.textContent).sort()).toEqual(['H', 'H', 'O'])
    expect(svg.querySelector('g')!.getAttribute('stroke')).toBe('currentColor')
    expect(svg.querySelectorAll('line')[0]!.getAttribute('x1')).not.toBeNull()
  })

  it('passes the drawing through DOMPurify with every element and attribute intact', () => {
    const shape = (root: Element) => [root, ...root.querySelectorAll('*')].map(el =>
      `${el.localName}[${[...el.attributes].map(a => `${a.name}=${a.value}`).sort().join(' ')}]${el.children.length ? '' : el.textContent}`)
    const drawn = renderLewis('O=[O+][O-]')
    const raw = new DOMParser().parseFromString('svg' in drawn ? drawn.svg : '', 'image/svg+xml').documentElement
    const sanitized = parseHtml(renderMarkdown(fence('O=[O+][O-]'))).querySelector('figure.lewis > svg')!
    expect(shape(sanitized)).toEqual(shape(raw))
  })

  it.each([
    ['a capitalized hint', '```Lewis\n[H]O[H]\n```\n'],
    ['words after the hint', '```lewis water\n[H]O[H]\n```\n'],
  ])('draws a fence with %s', (_, text) => {
    expect(parseHtml(renderMarkdown(text)).querySelector('figure.lewis > svg')).not.toBeNull()
  })

  it('parses only the first word of the fence, as OpenSMILES ends a SMILES at whitespace', () => {
    const svg = parseHtml(renderMarkdown(fence('[H]O[H] water'))).querySelector('figure.lewis > svg')
    expect(svg?.getAttribute('aria-label')).toBe('Lewis structure of H2O, SMILES [H]O[H]')
  })

  it.each([
    ['a ~~~ fence closed by ~~~', '~~~lewis\n[H]O[H]\n~~~\n', true],
    ['a closer longer than the opener', '```lewis\n[H]O[H]\n````\n', true],
    ['a ``` fence "closed" by ~~~', '```lewis\n[H]O[H]\n~~~\n', false],
    ['a closer indented four spaces', '```lewis\n[H]O[H]\n    ```\n', false],
  ])('treats %s as closed: %s', (_, text, drawn) => {
    const doc = parseHtml(renderMarkdown(text))
    expect(doc.querySelector('figure.lewis > svg') !== null).toBe(drawn)
    expect(doc.querySelector('code.language-lewis') !== null).toBe(!drawn)
  })

  it('leaves a fence that is still streaming as code, with no layout', () => {
    const draw = vi.spyOn(SmilesDrawer.SvgDrawer.prototype, 'draw')
    try {
      for (const text of ['```lewis\nO=C=O', '```lewis\nO=C=O\n', '```lewis\nO=C=O\n``']) {
        const html = renderMarkdownStreaming(text)
        expect(html, text).toContain('<code class="language-lewis">')
        expect(html, text).not.toContain('<svg')
      }
      expect(draw).not.toHaveBeenCalled()
      expect(renderMarkdownStreaming('```lewis\nO=C=O\n```')).toContain('<svg')
      expect(draw).toHaveBeenCalled()
    }
    finally {
      draw.mockRestore()
    }
  })

  it('keeps the code block and adds a one-line reason for SMILES it cannot draw', () => {
    const doc = parseHtml(renderMarkdown(fence('[H]O(')))
    expect(doc.querySelector('pre code.language-lewis')?.textContent).toBe('[H]O(\n')
    expect(doc.querySelector('p.lewis-error')?.textContent).toMatch(/^Invalid SMILES: .*parenthes/)
    expect(doc.querySelector('svg')).toBeNull()
  })
})
