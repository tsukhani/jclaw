import { afterEach, describe, expect, it } from 'vitest'
import { renderMarkdown, renderMarkdownStreaming } from '~/utils/chat-markdown'
import { visualEnabled } from '~/utils/visual/spec'
import { CHART } from './visual-fixtures'

const parse = (html: string) => new DOMParser().parseFromString(html, 'text/html')
const BODY = JSON.stringify(CHART, null, 2)
const fence = (body: string) => `Here:\n\n\`\`\`visual\n${body}\n\`\`\`\n\nAfter the figure.`

describe('renderMarkdown visual fence (JF-195)', () => {
  afterEach(() => {
    visualEnabled.value = true
  })

  it('wraps a finished fence in a figure that keeps its code block, with the caption below', () => {
    const doc = parse(renderMarkdown(fence(BODY)))
    const figure = doc.querySelector('figure.visual-view')!
    expect(figure.classList.contains('visual-pending')).toBe(false)
    expect(figure.getAttribute('data-info')).toBe('visual')
    expect(figure.querySelector(':scope > .code-block code')!.textContent).toBe(`${BODY}\n`)
    expect(figure.querySelector(':scope > figcaption')!.textContent).toBe('Committed against delivered, by sprint')
    expect(figure.lastElementChild!.tagName).toBe('FIGCAPTION')
  })

  it('captions a figure with its title when the spec has no caption', () => {
    const doc = parse(renderMarkdown(fence(JSON.stringify({ ...CHART, caption: undefined }))))
    expect(doc.querySelector('figure.visual-view figcaption')!.textContent).toBe('Sprint velocity')
  })

  it('shows invalid JSON as a code block with the reason, and renders the rest of the reply', () => {
    const doc = parse(renderMarkdown(fence('{"kind": "chart",')))
    expect(doc.querySelector('figure')).toBeNull()
    expect(doc.querySelector('code.language-visual')!.textContent).toBe('{"kind": "chart",\n')
    expect(doc.querySelector('p.visual-error')!.textContent!.startsWith('The visual block is not valid JSON: ')).toBe(true)
    expect(doc.body.textContent).toContain('After the figure.')
  })

  it('shows a refused spec with the validator\'s message', () => {
    const doc = parse(renderMarkdown(fence(JSON.stringify({ ...CHART, kind: 'pie' }))))
    expect(doc.querySelector('p.visual-error')!.textContent).toBe('Unknown visual kind "pie". Use chart, diagram or explore.')
  })

  it('leaves a fence that is still streaming as a plain code block', () => {
    const doc = parse(renderMarkdownStreaming(`\`\`\`visual\n${BODY}`))
    expect(doc.querySelector('figure')).toBeNull()
    expect(doc.querySelector('code.language-visual')).not.toBeNull()
  })

  it('keeps a streaming reply\'s finished fence pending, with the note', () => {
    const doc = parse(renderMarkdownStreaming(fence(BODY)))
    const figure = doc.querySelector('figure.visual-view')!
    expect(figure.classList.contains('visual-pending')).toBe(true)
    expect(figure.hasAttribute('data-info')).toBe(false)
    expect(figure.querySelector(':scope > .code-block')).not.toBeNull()
    expect(figure.querySelector('figcaption')!.textContent).toBe('Committed against delivered, by sprint')
    expect(figure.querySelector('p.visual-note')!.textContent).toBe('The visual appears when the reply is complete.')
  })

  it('renders a plain code block while visuals are off, and a figure again once they are back on', () => {
    const text = fence(BODY)
    expect(parse(renderMarkdown(text)).querySelector('figure.visual-view')).not.toBeNull()
    visualEnabled.value = false
    const off = parse(renderMarkdown(text))
    expect(off.querySelector('figure')).toBeNull()
    expect(off.querySelector('.visual-error')).toBeNull()
    expect(off.querySelector('code.language-visual')!.textContent).toBe(`${BODY}\n`)
    expect(parse(renderMarkdownStreaming(text)).querySelector('figure')).toBeNull()
    visualEnabled.value = true
    expect(parse(renderMarkdown(text)).querySelector('figure.visual-view')).not.toBeNull()
  })
})
